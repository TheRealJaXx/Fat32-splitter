package com.example.format

import android.hardware.usb.UsbDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ActiveFormatJobConfig(
  val usbDevice: UsbDevice,
  val deviceTitle: String,
  val filesystem: TargetFilesystem,
  val volumeLabel: String,
  val clusterSizeBytes: Int,
)

object DriveFormatCoordinator {

  private val _formatState = MutableStateFlow<FormatJobState>(FormatJobState.Idle)
  val formatState: StateFlow<FormatJobState> = _formatState.asStateFlow()

  @Volatile var pendingFormatJob: ActiveFormatJobConfig? = null

  fun setState(state: FormatJobState) {
    _formatState.value = state
  }

  /**
   * Executes MBR partitioning + NTFS/FAT32 quick format on any BlockDevice.
   * Extracted for full reuse across real USB OTG hardware and JVM unit tests.
   */
  suspend fun formatBlockDevice(
    device: BlockDevice,
    deviceTitle: String,
    filesystem: TargetFilesystem,
    volumeLabel: String,
    clusterSizeBytes: Int,
    onProgress: suspend (stage: String, progress: Float) -> Unit,
  ): FormatJobState.Completed {
    val startMs = System.nanoTime() / 1_000_000L
    val layout =
      MbrWriter.computeLayout(
        totalSectors = device.totalSectors,
        sectorSizeBytes = device.sectorSizeBytes,
        filesystem = filesystem,
      )

    MbrWriter.writeCleanMbr(device, layout) { stage, pct ->
      onProgress(stage, pct)
    }

    val finalLabel: String
    when (filesystem) {
      TargetFilesystem.MBR_NTFS -> {
        finalLabel = NtfsFormatter.sanitizeVolumeLabel(volumeLabel)
        NtfsFormatter.format(
          device = device,
          layout = layout,
          volumeLabel = finalLabel,
          requestedClusterBytes = clusterSizeBytes,
          onProgress = onProgress,
        )
      }
      TargetFilesystem.MBR_FAT32 -> {
        finalLabel = Fat32Formatter.sanitizeVolumeLabel(volumeLabel)
        Fat32Formatter.format(
          device = device,
          layout = layout,
          volumeLabel = finalLabel,
          requestedClusterBytes = clusterSizeBytes,
          onProgress = onProgress,
        )
      }
    }

    onProgress("Verifying MBR & boot sector...", 0.99f)
    val verifyMbr = device.readSectors(0L, 1)
    check(MbrWriter.inspectPartitionScheme(verifyMbr, null) == PartitionScheme.MBR) {
      "Post-format verification failed: MBR signature not persisted on drive"
    }
    val verifyVbr = device.readSectors(layout.partitionStartLba, 1)
    val expectedOem =
      when (filesystem) {
        TargetFilesystem.MBR_NTFS -> "NTFS    "
        TargetFilesystem.MBR_FAT32 -> "MSDOS5.0"
      }
    val actualOem = String(verifyVbr, 3, 8, Charsets.US_ASCII)
    check(actualOem == expectedOem) {
      "Post-format verification failed: Expected $expectedOem boot record, found '$actualOem'"
    }

    val elapsed = ((System.nanoTime() / 1_000_000L) - startMs).coerceAtLeast(1L)
    return FormatJobState.Completed(
      deviceTitle = deviceTitle,
      filesystem = filesystem,
      volumeLabel = finalLabel,
      partitionStartLba = layout.partitionStartLba,
      partitionSectors = layout.partitionSectors,
      sectorSizeBytes = device.sectorSizeBytes,
      clusterSizeBytes = clusterSizeBytes,
      elapsedMillis = elapsed,
    )
  }
}
