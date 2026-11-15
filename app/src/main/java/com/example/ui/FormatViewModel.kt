package com.example.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.format.ActiveFormatJobConfig
import com.example.format.DriveFormatCoordinator
import com.example.format.Fat32Formatter
import com.example.format.FormatJobState
import com.example.format.MbrWriter
import com.example.format.NtfsFormatter
import com.example.format.TargetFilesystem
import com.example.format.UsbDriveInfo
import com.example.format.UsbScsiBlockDevice
import com.example.service.SplitTransferService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FormatUiState(
  val drives: List<UsbDriveInfo> = emptyList(),
  val selectedDeviceId: Int? = null,
  val filesystem: TargetFilesystem = TargetFilesystem.MBR_NTFS,
  val volumeLabel: String = "PS3USB",
  val clusterSizeBytes: Int = TargetFilesystem.MBR_NTFS.defaultClusterBytes,
  val showConfirmDialog: Boolean = false,
  val confirmTypedText: String = "",
  val jobState: FormatJobState = FormatJobState.Idle,
) {
  val selectedDrive: UsbDriveInfo?
    get() = drives.firstOrNull { it.deviceId == selectedDeviceId } ?: drives.firstOrNull()

  val isFormatting: Boolean
    get() = jobState is FormatJobState.Formatting || jobState is FormatJobState.Probing

  val isConfirmPhraseMatched: Boolean
    get() = confirmTypedText.trim() == REQUIRED_CONFIRM_PHRASE

  companion object {
    const val REQUIRED_CONFIRM_PHRASE = "FORMAT"
  }
}

class FormatViewModel(application: Application) : AndroidViewModel(application) {

  private val _drives = MutableStateFlow<List<UsbDriveInfo>>(emptyList())
  private val _selectedDeviceId = MutableStateFlow<Int?>(null)
  private val _filesystem = MutableStateFlow(TargetFilesystem.MBR_NTFS)
  private val _volumeLabel = MutableStateFlow("PS3USB")
  private val _clusterSizeBytes = MutableStateFlow(TargetFilesystem.MBR_NTFS.defaultClusterBytes)
  private val _showConfirmDialog = MutableStateFlow(false)
  private val _confirmTypedText = MutableStateFlow("")

  private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
  val events: SharedFlow<String> = _events.asSharedFlow()

  val uiState: StateFlow<FormatUiState> =
    combine(
      _drives,
      _selectedDeviceId,
      _filesystem,
      _volumeLabel,
      _clusterSizeBytes,
      _showConfirmDialog,
      _confirmTypedText,
      DriveFormatCoordinator.formatState,
    ) { values ->
      @Suppress("UNCHECKED_CAST")
      val drives = values[0] as List<UsbDriveInfo>
      val selectedId = values[1] as Int?
      val fs = values[2] as TargetFilesystem
      val label = values[3] as String
      val cluster = values[4] as Int
      val showConfirm = values[5] as Boolean
      val typed = values[6] as String
      val jobState = values[7] as FormatJobState
      FormatUiState(
        drives = drives,
        selectedDeviceId = selectedId,
        filesystem = fs,
        volumeLabel = label,
        clusterSizeBytes = cluster,
        showConfirmDialog = showConfirm,
        confirmTypedText = typed,
        jobState = jobState,
      )
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = FormatUiState(),
    )

  init {
    refreshDevices()
  }

  fun refreshDevices() {
    val app = getApplication<Application>()
    val existingById = _drives.value.associateBy { it.deviceId }
    val detected =
      UsbScsiBlockDevice.listAttachedMassStorageDevices(app).map { fresh ->
        val prev = existingById[fresh.deviceId]
        if (prev != null && prev.hasPermission == fresh.hasPermission && prev.probed) {
          fresh.copy(
            probed = prev.probed,
            sectorSizeBytes = prev.sectorSizeBytes,
            totalSectors = prev.totalSectors,
            detectedScheme = prev.detectedScheme,
            probeError = prev.probeError,
          )
        } else {
          fresh
        }
      }
    _drives.value = detected
    if (_selectedDeviceId.value == null || detected.none { it.deviceId == _selectedDeviceId.value }) {
      _selectedDeviceId.value = detected.firstOrNull()?.deviceId
    }

    // Automatically probe permitted drives that haven't been probed yet
    detected.filter { it.hasPermission && !it.probed }.forEach { drive ->
      probeDrive(drive.deviceId)
    }
  }

  fun selectDrive(deviceId: Int) {
    _selectedDeviceId.value = deviceId
  }

  fun requestUsbPermission(deviceId: Int) {
    val app = getApplication<Application>()
    val usbManager = app.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
    val usbDevice = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }
    if (usbDevice == null) {
      _events.tryEmit("USB drive no longer connected")
      refreshDevices()
      return
    }
    if (usbManager.hasPermission(usbDevice)) {
      probeDrive(deviceId)
      return
    }
    val flags =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      } else {
        PendingIntent.FLAG_UPDATE_CURRENT
      }
    val permIntent =
      PendingIntent.getBroadcast(
        app,
        deviceId,
        Intent(ACTION_USB_PERMISSION).setPackage(app.packageName),
        flags,
      )
    usbManager.requestPermission(usbDevice, permIntent)
  }

  fun onUsbPermissionResult(device: UsbDevice?, granted: Boolean) {
    refreshDevices()
    if (granted && device != null) {
      probeDrive(device.deviceId)
    } else if (!granted) {
      _events.tryEmit("USB Host permission denied")
    }
  }

  fun probeDrive(deviceId: Int) {
    val app = getApplication<Application>()
    val usbManager = app.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
    val usbDevice = usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId } ?: return
    if (!usbManager.hasPermission(usbDevice)) return

    viewModelScope.launch {
      val currentDrive = _drives.value.firstOrNull { it.deviceId == deviceId }
      DriveFormatCoordinator.setState(
        FormatJobState.Probing(currentDrive?.displayTitle ?: usbDevice.deviceName)
      )
      try {
        UsbScsiBlockDevice.open(app, usbDevice).use { blockDev ->
          val sec0 = blockDev.readSectors(0L, 1)
          val sec1 = if (blockDev.totalSectors > 1L) blockDev.readSectors(1L, 1) else null
          val scheme = MbrWriter.inspectPartitionScheme(sec0, sec1)

          _drives.value =
            _drives.value.map { d ->
              if (d.deviceId == deviceId) {
                d.copy(
                  hasPermission = true,
                  probed = true,
                  sectorSizeBytes = blockDev.sectorSizeBytes,
                  totalSectors = blockDev.totalSectors,
                  detectedScheme = scheme,
                  probeError = null,
                  vendorName = d.vendorName.ifBlank { blockDev.scsiVendor },
                  productName = d.productName.ifBlank { blockDev.scsiProduct },
                )
              } else {
                d
              }
            }
          DriveFormatCoordinator.setState(FormatJobState.Idle)
        }
      } catch (t: Throwable) {
        _drives.value =
          _drives.value.map { d ->
            if (d.deviceId == deviceId) {
              d.copy(probed = false, probeError = t.message ?: "SCSI probe failed")
            } else {
              d
            }
          }
        DriveFormatCoordinator.setState(FormatJobState.Idle)
        _events.tryEmit("Could not read USB drive: ${t.message ?: "I/O error"}")
      }
    }
  }

  fun selectFilesystem(fs: TargetFilesystem) {
    _filesystem.value = fs
    if (_clusterSizeBytes.value !in fs.clusterOptionsBytes) {
      _clusterSizeBytes.value = fs.defaultClusterBytes
    }
  }

  fun updateVolumeLabel(raw: String) {
    _volumeLabel.value = raw.take(32)
  }

  fun selectClusterSize(bytes: Int) {
    _clusterSizeBytes.value = bytes
  }

  fun openConfirmDialog() {
    val drive = uiState.value.selectedDrive
    if (drive == null) {
      _events.tryEmit("Connect a USB OTG drive first")
      return
    }
    if (!drive.hasPermission) {
      requestUsbPermission(drive.deviceId)
      return
    }
    if (!drive.probed || drive.totalSectors <= 0L) {
      probeDrive(drive.deviceId)
      return
    }
    _confirmTypedText.value = ""
    _showConfirmDialog.value = true
  }

  fun dismissConfirmDialog() {
    _showConfirmDialog.value = false
    _confirmTypedText.value = ""
  }

  fun updateConfirmTypedText(text: String) {
    _confirmTypedText.value = text
  }

  fun executeConfirmedFormat() {
    val state = uiState.value
    if (!state.isConfirmPhraseMatched) return
    val drive = state.selectedDrive ?: return
    val app = getApplication<Application>()
    val usbManager = app.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
    val usbDevice = usbManager.deviceList.values.firstOrNull { it.deviceId == drive.deviceId }
    if (usbDevice == null) {
      _showConfirmDialog.value = false
      _events.tryEmit("USB drive disconnected before formatting")
      refreshDevices()
      return
    }

    val sanitizedLabel =
      when (state.filesystem) {
        TargetFilesystem.MBR_NTFS -> NtfsFormatter.sanitizeVolumeLabel(state.volumeLabel)
        TargetFilesystem.MBR_FAT32 -> Fat32Formatter.sanitizeVolumeLabel(state.volumeLabel)
      }

    _showConfirmDialog.value = false
    _confirmTypedText.value = ""

    SplitTransferService.startFormat(
      context = app,
      config =
        ActiveFormatJobConfig(
          usbDevice = usbDevice,
          deviceTitle = drive.displayTitle,
          filesystem = state.filesystem,
          volumeLabel = sanitizedLabel,
          clusterSizeBytes = state.clusterSizeBytes,
        ),
    )
  }

  fun dismissJobState() {
    if (!uiState.value.isFormatting) {
      DriveFormatCoordinator.setState(FormatJobState.Idle)
      refreshDevices()
    }
  }

  companion object {
    const val ACTION_USB_PERMISSION = "com.example.USB_HOST_PERMISSION"
  }
}
