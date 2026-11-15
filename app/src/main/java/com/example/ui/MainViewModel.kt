package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.SettingsRepository
import com.example.engine.SafFileHelper
import com.example.engine.ScannedDocument
import com.example.engine.SplitNaming
import com.example.model.AppTab
import com.example.model.ChunkSizePreset
import com.example.model.JoinGroup
import com.example.model.SmallFileAction
import com.example.model.SplitNamingFormat
import com.example.model.SplitQueueItem
import com.example.model.ThemeMode
import com.example.model.TransferProgressState
import com.example.model.UserSettings
import com.example.service.ActiveJoinJobConfig
import com.example.service.ActiveSplitJobConfig
import com.example.service.SplitTransferService
import com.example.service.TransferCoordinator
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
  val activeTab: AppTab = AppTab.SPLIT,
  val settings: UserSettings = UserSettings(),
  val splitQueue: List<SplitQueueItem> = emptyList(),
  val joinGroups: List<JoinGroup> = emptyList(),
  val progressState: TransferProgressState = TransferProgressState.Idle,
  val isScanningSource: Boolean = false,
  val showCustomSizeDialog: Boolean = false,
) {
  val isTransferRunning: Boolean
    get() = progressState is TransferProgressState.Running

  val oversizedCount: Int
    get() = splitQueue.count { it.needsSplit(settings.effectiveChunkSizeBytes) }

  val totalEstimatedOutputParts: Int
    get() {
      val chunk = settings.effectiveChunkSizeBytes
      return splitQueue.sumOf { item ->
        if (item.needsSplit(chunk)) {
          SplitNaming.calculatePartCount(item.sizeBytes, chunk)
        } else if (settings.smallFileAction == SmallFileAction.COPY) {
          1
        } else {
          0
        }
      }
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

  private val settingsRepo = SettingsRepository(application)

  private val _activeTab = MutableStateFlow(AppTab.SPLIT)
  private val _isScanning = MutableStateFlow(false)
  private val _showCustomSizeDialog = MutableStateFlow(false)

  private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
  val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

  val uiState: StateFlow<MainUiState> =
    combine(
      _activeTab,
      settingsRepo.settingsFlow,
      TransferCoordinator.splitQueue,
      TransferCoordinator.joinGroups,
      TransferCoordinator.progressState,
      _isScanning,
      _showCustomSizeDialog,
    ) { values ->
      val tab = values[0] as AppTab
      val settings = values[1] as UserSettings
      @Suppress("UNCHECKED_CAST")
      val queue = values[2] as List<SplitQueueItem>
      @Suppress("UNCHECKED_CAST")
      val joins = values[3] as List<JoinGroup>
      val progress = values[4] as TransferProgressState
      val scanning = values[5] as Boolean
      val customDialog = values[6] as Boolean
      MainUiState(
        activeTab = tab,
        settings = settings,
        splitQueue = queue,
        joinGroups = joins,
        progressState = progress,
        isScanningSource = scanning,
        showCustomSizeDialog = customDialog,
      )
    }.stateIn(
      scope = viewModelScope,
      started = SharingStarted.WhileSubscribed(5_000),
      initialValue = MainUiState(),
    )

  fun selectTab(tab: AppTab) {
    _activeTab.value = tab
  }

  fun onSplitFilesPicked(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val app = getApplication<Application>()
    viewModelScope.launch {
      _isScanning.value = true
      try {
        val current = TransferCoordinator.splitQueue.value.toMutableList()
        val existingIds = current.mapTo(HashSet()) { it.id }
        var added = 0
        for (uri in uris) {
          SafFileHelper.takePersistablePermission(app, uri, writeAccess = false)
          val item = SafFileHelper.querySingleDocument(app, uri)
          if (item != null && existingIds.add(item.id)) {
            current.add(item)
            added++
          }
        }
        TransferCoordinator.setSplitQueue(current)
        if (TransferCoordinator.progressState.value !is TransferProgressState.Running) {
          TransferCoordinator.setProgress(TransferProgressState.Idle)
        }
        if (added > 0) {
          _snackbarEvents.tryEmit("Added $added file(s) to split queue")
        }
      } catch (t: Throwable) {
        _snackbarEvents.tryEmit("Failed to inspect file: ${t.message ?: "Unknown error"}")
      } finally {
        _isScanning.value = false
      }
    }
  }

  fun onSplitFolderPicked(treeUri: Uri) {
    val app = getApplication<Application>()
    viewModelScope.launch {
      _isScanning.value = true
      try {
        SafFileHelper.takePersistablePermission(app, treeUri, writeAccess = false)
        val scanned = SafFileHelper.scanTreeDocuments(app, treeUri, recursive = true)
        val items =
          scanned.map { doc ->
            SplitQueueItem(
              id = doc.uri.toString(),
              uri = doc.uri,
              displayName = doc.displayName,
              relativePath = doc.relativePath,
              sizeBytes = doc.sizeBytes,
            )
          }
        TransferCoordinator.setSplitQueue(items)
        if (TransferCoordinator.progressState.value !is TransferProgressState.Running) {
          TransferCoordinator.setProgress(TransferProgressState.Idle)
        }
        val limit = uiState.value.settings.effectiveChunkSizeBytes
        val oversized = items.count { it.needsSplit(limit) }
        _snackbarEvents.tryEmit(
          "Scanned ${items.size} file(s) • $oversized exceed chunk threshold"
        )
      } catch (t: Throwable) {
        _snackbarEvents.tryEmit("Folder scan error: ${t.message ?: "Unknown error"}")
      } finally {
        _isScanning.value = false
      }
    }
  }

  fun removeSplitItem(id: String) {
    TransferCoordinator.setSplitQueue(TransferCoordinator.splitQueue.value.filterNot { it.id == id })
  }

  fun clearSplitQueue() {
    TransferCoordinator.setSplitQueue(emptyList())
    if (TransferCoordinator.progressState.value !is TransferProgressState.Running) {
      TransferCoordinator.setProgress(TransferProgressState.Idle)
    }
  }

  fun onJoinFilesPicked(uris: List<Uri>) {
    if (uris.isEmpty()) return
    val app = getApplication<Application>()
    viewModelScope.launch {
      _isScanning.value = true
      try {
        val docs = mutableListOf<ScannedDocument>()
        for (uri in uris) {
          SafFileHelper.takePersistablePermission(app, uri, writeAccess = false)
          val item = SafFileHelper.querySingleDocument(app, uri) ?: continue
          docs.add(
            ScannedDocument(
              uri = item.uri,
              displayName = item.displayName,
              relativePath = item.relativePath,
              sizeBytes = item.sizeBytes,
            )
          )
        }
        val groups = SafFileHelper.buildJoinGroups(docs)
        TransferCoordinator.setJoinGroups(groups)
        if (TransferCoordinator.progressState.value !is TransferProgressState.Running) {
          TransferCoordinator.setProgress(TransferProgressState.Idle)
        }
        if (groups.isEmpty()) {
          _snackbarEvents.tryEmit("No .666xx, .001, or .000 split parts detected in selection")
        } else {
          _snackbarEvents.tryEmit("Detected ${groups.size} split file set(s)")
        }
      } catch (t: Throwable) {
        _snackbarEvents.tryEmit("Scan error: ${t.message ?: "Unknown error"}")
      } finally {
        _isScanning.value = false
      }
    }
  }

  fun onJoinFolderPicked(treeUri: Uri) {
    val app = getApplication<Application>()
    viewModelScope.launch {
      _isScanning.value = true
      try {
        SafFileHelper.takePersistablePermission(app, treeUri, writeAccess = false)
        val docs = SafFileHelper.scanTreeDocuments(app, treeUri, recursive = true)
        val groups = SafFileHelper.buildJoinGroups(docs)
        TransferCoordinator.setJoinGroups(groups)
        if (TransferCoordinator.progressState.value !is TransferProgressState.Running) {
          TransferCoordinator.setProgress(TransferProgressState.Idle)
        }
        if (groups.isEmpty()) {
          _snackbarEvents.tryEmit("No .666xx, .001, or .000 split parts found in folder")
        } else {
          _snackbarEvents.tryEmit("Detected ${groups.size} split file set(s)")
        }
      } catch (t: Throwable) {
        _snackbarEvents.tryEmit("Folder scan error: ${t.message ?: "Unknown error"}")
      } finally {
        _isScanning.value = false
      }
    }
  }

  fun removeJoinGroup(id: String) {
    TransferCoordinator.setJoinGroups(TransferCoordinator.joinGroups.value.filterNot { it.id == id })
  }

  fun clearJoinGroups() {
    TransferCoordinator.setJoinGroups(emptyList())
    if (TransferCoordinator.progressState.value !is TransferProgressState.Running) {
      TransferCoordinator.setProgress(TransferProgressState.Idle)
    }
  }

  fun onOutputFolderPicked(treeUri: Uri) {
    val app = getApplication<Application>()
    SafFileHelper.takePersistablePermission(app, treeUri, writeAccess = true)
    val label = SafFileHelper.resolveTreeDisplayName(app, treeUri)
    viewModelScope.launch {
      settingsRepo.updateOutputDestination(treeUri.toString(), label)
      _snackbarEvents.tryEmit("Destination set to $label")
    }
  }

  fun startSplitJob() {
    val state = uiState.value
    if (state.isTransferRunning) return
    val queue = state.splitQueue
    if (queue.isEmpty()) {
      _snackbarEvents.tryEmit("Add files or scan a folder first")
      return
    }
    val outUriStr = state.settings.outputTreeUri
    if (outUriStr.isNullOrBlank()) {
      _snackbarEvents.tryEmit("Select a destination folder (e.g. FAT32 USB or Downloads)")
      return
    }
    val hasWork =
      queue.any {
        it.needsSplit(state.settings.effectiveChunkSizeBytes) ||
          state.settings.smallFileAction == SmallFileAction.COPY
      }
    if (!hasWork) {
      _snackbarEvents.tryEmit("All queued files are already within the FAT32 chunk limit")
      return
    }

    TransferCoordinator.resetPendingStatusesForSplit()
    SplitTransferService.startSplit(
      context = getApplication(),
      config =
        ActiveSplitJobConfig(
          items = queue,
          outputTreeUri = Uri.parse(outUriStr),
          chunkSizeBytes = state.settings.effectiveChunkSizeBytes,
          namingFormat = state.settings.namingFormat,
          smallFileAction = state.settings.smallFileAction,
          verifyChecksums = state.settings.verifyChecksums,
          overwriteExisting = state.settings.overwriteExisting,
        ),
    )
  }

  fun startJoinJob() {
    val state = uiState.value
    if (state.isTransferRunning) return
    val readyGroups = state.joinGroups.filter { it.isContiguous }
    if (readyGroups.isEmpty()) {
      _snackbarEvents.tryEmit("No complete split part sets ready to merge")
      return
    }
    val outUriStr = state.settings.outputTreeUri
    if (outUriStr.isNullOrBlank()) {
      _snackbarEvents.tryEmit("Select a destination folder for the merged file")
      return
    }

    TransferCoordinator.resetPendingStatusesForJoin()
    SplitTransferService.startJoin(
      context = getApplication(),
      config =
        ActiveJoinJobConfig(
          groups = readyGroups,
          outputTreeUri = Uri.parse(outUriStr),
          verifyChecksums = state.settings.verifyChecksums,
          overwriteExisting = state.settings.overwriteExisting,
        ),
    )
  }

  fun cancelTransfer() {
    SplitTransferService.cancel(getApplication())
  }

  fun dismissProgressState() {
    if (TransferCoordinator.progressState.value !is TransferProgressState.Running) {
      TransferCoordinator.setProgress(TransferProgressState.Idle)
    }
  }

  fun selectChunkPreset(preset: ChunkSizePreset) {
    if (preset == ChunkSizePreset.CUSTOM) {
      _showCustomSizeDialog.value = true
    } else {
      viewModelScope.launch { settingsRepo.updateChunkPreset(preset) }
    }
  }

  fun openCustomSizeDialog() {
    _showCustomSizeDialog.value = true
  }

  fun dismissCustomSizeDialog() {
    _showCustomSizeDialog.value = false
  }

  fun saveCustomSizeMib(mib: Long) {
    _showCustomSizeDialog.value = false
    viewModelScope.launch { settingsRepo.updateCustomMib(mib) }
  }

  fun selectNamingFormat(format: SplitNamingFormat) {
    viewModelScope.launch { settingsRepo.updateNamingFormat(format) }
  }

  fun selectSmallFileAction(action: SmallFileAction) {
    viewModelScope.launch { settingsRepo.updateSmallFileAction(action) }
  }

  fun toggleVerifyChecksums(enabled: Boolean) {
    viewModelScope.launch { settingsRepo.updateVerifyChecksums(enabled) }
  }

  fun toggleOverwriteExisting(enabled: Boolean) {
    viewModelScope.launch { settingsRepo.updateOverwriteExisting(enabled) }
  }

  fun selectThemeMode(mode: ThemeMode) {
    viewModelScope.launch { settingsRepo.updateThemeMode(mode) }
  }
}
