package com.example.service

import android.net.Uri
import com.example.model.JoinGroup
import com.example.model.QueueItemStatus
import com.example.model.SmallFileAction
import com.example.model.SplitNamingFormat
import com.example.model.SplitQueueItem
import com.example.model.TransferProgressState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ActiveSplitJobConfig(
  val items: List<SplitQueueItem>,
  val outputTreeUri: Uri,
  val chunkSizeBytes: Long,
  val namingFormat: SplitNamingFormat,
  val smallFileAction: SmallFileAction,
  val verifyChecksums: Boolean,
  val overwriteExisting: Boolean,
)

data class ActiveJoinJobConfig(
  val groups: List<JoinGroup>,
  val outputTreeUri: Uri,
  val verifyChecksums: Boolean,
  val overwriteExisting: Boolean,
)

/**
 * In-process reactive bridge between SplitTransferService and MainViewModel.
 */
object TransferCoordinator {

  private val _splitQueue = MutableStateFlow<List<SplitQueueItem>>(emptyList())
  val splitQueue: StateFlow<List<SplitQueueItem>> = _splitQueue.asStateFlow()

  private val _joinGroups = MutableStateFlow<List<JoinGroup>>(emptyList())
  val joinGroups: StateFlow<List<JoinGroup>> = _joinGroups.asStateFlow()

  private val _progressState = MutableStateFlow<TransferProgressState>(TransferProgressState.Idle)
  val progressState: StateFlow<TransferProgressState> = _progressState.asStateFlow()

  @Volatile var pendingSplitJob: ActiveSplitJobConfig? = null
  @Volatile var pendingJoinJob: ActiveJoinJobConfig? = null
  @Volatile var cancelRequested: Boolean = false

  fun setSplitQueue(items: List<SplitQueueItem>) {
    _splitQueue.value = items
  }

  fun updateSplitItem(id: String, transform: (SplitQueueItem) -> SplitQueueItem) {
    _splitQueue.update { list -> list.map { if (it.id == id) transform(it) else it } }
  }

  fun resetPendingStatusesForSplit() {
    _splitQueue.update { list ->
      list.map {
        it.copy(
          status = QueueItemStatus.PENDING,
          bytesProcessed = 0L,
          currentPartIndex = 0,
          crc32Hex = null,
          errorMessage = null,
        )
      }
    }
  }

  fun setJoinGroups(groups: List<JoinGroup>) {
    _joinGroups.value = groups
  }

  fun updateJoinGroup(id: String, transform: (JoinGroup) -> JoinGroup) {
    _joinGroups.update { list -> list.map { if (it.id == id) transform(it) else it } }
  }

  fun resetPendingStatusesForJoin() {
    _joinGroups.update { list ->
      list.map {
        it.copy(
          status = QueueItemStatus.PENDING,
          bytesProcessed = 0L,
          crc32Hex = null,
          errorMessage = null,
        )
      }
    }
  }

  fun setProgress(state: TransferProgressState) {
    _progressState.value = state
  }

  fun requestCancel() {
    cancelRequested = true
  }
}
