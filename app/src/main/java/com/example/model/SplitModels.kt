package com.example.model

import android.net.Uri

enum class AppTab {
  SPLIT,
  JOIN,
  FORMAT,
  SPECS,
}

enum class ThemeMode(val label: String) {
  SYSTEM("System"),
  DARK("Obsidian Dark"),
  LIGHT("Lab Light"),
}

enum class SplitNamingFormat(
  val id: String,
  val displayName: String,
  val sampleSuffix: String,
  val description: String,
) {
  PS3_666(
    id = "ps3_666",
    displayName = "PS3 / multiMAN",
    sampleSuffix = ".66600",
    description = "Classic .66600, .66601 format recognized by multiMAN, webMAN & irisMAN",
  ),
  SEQUENTIAL_001(
    id = "seq_001",
    displayName = "Standard (.001)",
    sampleSuffix = ".001",
    description = "Universal 1-based .001, .002 binary split parts",
  ),
  ZERO_BASED_000(
    id = "seq_000",
    displayName = "Zero-based (.000)",
    sampleSuffix = ".000",
    description = "0-based .000, .001 numbered split sequence",
  );

  companion object {
    fun fromId(id: String?): SplitNamingFormat =
      entries.firstOrNull { it.id == id } ?: PS3_666
  }
}

enum class ChunkSizePreset(
  val id: String,
  val label: String,
  val shortLabel: String,
  val sizeBytes: Long,
  val subtitle: String,
) {
  PS3_SAFE_4095_MIB(
    id = "4095_mib",
    label = "4095 MiB (Safe FAT32)",
    shortLabel = "4095 MiB",
    sizeBytes = 4_293_918_720L,
    subtitle = "1 MiB safety margin under the 4 GiB FAT32 ceiling",
  ),
  FAT32_EXACT(
    id = "fat32_max",
    label = "4 GiB - 1 B (FAT32 Max)",
    shortLabel = "4 GiB - 1 B",
    sizeBytes = 4_294_967_295L,
    subtitle = "Exact 32-bit unsigned FAT32 maximum file limit",
  ),
  PS3_4000_MB(
    id = "4000_mb",
    label = "4,000 MB (PS3Splitter)",
    shortLabel = "4000 MB",
    sizeBytes = 4_000_000_000L,
    subtitle = "Classic PS3Splitter desktop default chunk size",
  ),
  TWO_GIB(
    id = "2_gib",
    label = "2 GiB",
    shortLabel = "2 GiB",
    sizeBytes = 2_147_483_648L,
    subtitle = "Compatible with older 2 GiB file-size parsers",
  ),
  ONE_GIB(
    id = "1_gib",
    label = "1 GiB",
    shortLabel = "1 GiB",
    sizeBytes = 1_073_741_824L,
    subtitle = "Smaller segments for resilient USB OTG transfers",
  ),
  CUSTOM(
    id = "custom",
    label = "Custom Size",
    shortLabel = "Custom",
    sizeBytes = 100L * 1024L * 1024L,
    subtitle = "Set any custom MiB size (capped at FAT32 limit)",
  );

  companion object {
    const val FAT32_MAX_BYTES: Long = 4_294_967_295L

    fun fromId(id: String?): ChunkSizePreset =
      entries.firstOrNull { it.id == id } ?: PS3_SAFE_4095_MIB
  }
}

enum class SmallFileAction(val id: String, val label: String, val description: String) {
  SKIP(
    id = "skip",
    label = "Skip FAT32-safe files",
    description = "Only split files larger than the chunk threshold",
  ),
  COPY(
    id = "copy",
    label = "Mirror smaller files",
    description = "Copy files within FAT32 limits alongside split parts",
  );

  companion object {
    fun fromId(id: String?): SmallFileAction =
      entries.firstOrNull { it.id == id } ?: SKIP
  }
}

enum class QueueItemStatus {
  PENDING,
  SKIPPED,
  PROCESSING,
  COMPLETED,
  FAILED,
  CANCELLED,
}

data class SplitQueueItem(
  val id: String,
  val uri: Uri,
  val displayName: String,
  val relativePath: String,
  val sizeBytes: Long,
  val status: QueueItemStatus = QueueItemStatus.PENDING,
  val bytesProcessed: Long = 0L,
  val currentPartIndex: Int = 0,
  val totalParts: Int = 1,
  val crc32Hex: String? = null,
  val errorMessage: String? = null,
) {
  fun needsSplit(chunkLimitBytes: Long): Boolean = sizeBytes > chunkLimitBytes
}

data class JoinPartItem(
  val uri: Uri,
  val displayName: String,
  val partIndex: Int,
  val sizeBytes: Long,
)

data class JoinGroup(
  val id: String,
  val baseFileName: String,
  val format: SplitNamingFormat,
  val parts: List<JoinPartItem>,
  val totalBytes: Long,
  val missingIndices: List<Int>,
  val status: QueueItemStatus = QueueItemStatus.PENDING,
  val bytesProcessed: Long = 0L,
  val crc32Hex: String? = null,
  val errorMessage: String? = null,
) {
  val isContiguous: Boolean
    get() = missingIndices.isEmpty() && parts.isNotEmpty()
}

sealed interface TransferProgressState {
  data object Idle : TransferProgressState

  data class Running(
    val isJoinMode: Boolean,
    val currentFileName: String,
    val currentPartLabel: String,
    val fileIndex: Int,
    val totalFiles: Int,
    val bytesProcessed: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val elapsedMillis: Long,
  ) : TransferProgressState {
    val overallProgress: Float
      get() = if (totalBytes <= 0L) 0f else (bytesProcessed.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
  }

  data class Completed(
    val isJoinMode: Boolean,
    val filesProcessed: Int,
    val filesSkipped: Int,
    val partsCreated: Int,
    val totalBytesProcessed: Long,
    val elapsedMillis: Long,
    val verifiedChecksums: Boolean,
  ) : TransferProgressState

  data class Cancelled(
    val isJoinMode: Boolean,
    val filesCompletedBeforeCancel: Int,
  ) : TransferProgressState

  data class Failed(
    val isJoinMode: Boolean,
    val message: String,
  ) : TransferProgressState
}

data class UserSettings(
  val chunkPreset: ChunkSizePreset = ChunkSizePreset.PS3_SAFE_4095_MIB,
  val customSizeMib: Long = 100L,
  val namingFormat: SplitNamingFormat = SplitNamingFormat.PS3_666,
  val smallFileAction: SmallFileAction = SmallFileAction.SKIP,
  val verifyChecksums: Boolean = true,
  val overwriteExisting: Boolean = true,
  val outputTreeUri: String? = null,
  val outputTreeDisplayName: String? = null,
  val themeMode: ThemeMode = ThemeMode.DARK,
) {
  val effectiveChunkSizeBytes: Long
    get() =
      when (chunkPreset) {
        ChunkSizePreset.CUSTOM ->
          (customSizeMib.coerceAtLeast(1L) * 1024L * 1024L)
            .coerceAtMost(ChunkSizePreset.FAT32_MAX_BYTES)
        else -> chunkPreset.sizeBytes
      }
}
