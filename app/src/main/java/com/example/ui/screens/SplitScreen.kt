package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.NoteAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.engine.SplitNaming
import com.example.model.ChunkSizePreset
import com.example.model.QueueItemStatus
import com.example.model.SmallFileAction
import com.example.model.SplitNamingFormat
import com.example.model.SplitQueueItem
import com.example.ui.MainUiState
import com.example.ui.components.DestinationFolderBar
import com.example.ui.components.HeroBannerCard
import com.example.ui.components.TransferProgressHud

@Composable
fun SplitScreen(
  uiState: MainUiState,
  onPickFiles: () -> Unit,
  onScanFolder: () -> Unit,
  onPickOutputFolder: () -> Unit,
  onSelectChunkPreset: (ChunkSizePreset) -> Unit,
  onSelectNamingFormat: (SplitNamingFormat) -> Unit,
  onSelectSmallFileAction: (SmallFileAction) -> Unit,
  onToggleVerifyChecksum: (Boolean) -> Unit,
  onRemoveItem: (String) -> Unit,
  onClearQueue: () -> Unit,
  onStartSplit: () -> Unit,
  onCancelTransfer: () -> Unit,
  onDismissProgress: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val settings = uiState.settings
  val chunkLimit = settings.effectiveChunkSizeBytes
  val isRunning = uiState.isTransferRunning

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(modifier = Modifier.fillMaxSize().widthIn(max = 840.dp)) {
      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item(key = "hero") {
          HeroBannerCard(
            chunkLimitBytes = chunkLimit,
            namingSample = "FORMAT ${settings.namingFormat.sampleSuffix}",
            compact = uiState.splitQueue.isNotEmpty(),
          )
        }

        item(key = "hud") {
          TransferProgressHud(
            state = uiState.progressState,
            onCancel = onCancelTransfer,
            onDismiss = onDismissProgress,
          )
        }

        item(key = "pickers") {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedCard(
              onClick = onPickFiles,
              enabled = !isRunning && !uiState.isScanningSource,
              modifier = Modifier.weight(1f).heightIn(min = 68.dp).testTag("pick_files_button"),
              shape = MaterialTheme.shapes.medium,
              colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Rounded.NoteAdd,
                  contentDescription = "Pick files",
                  tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                  Text("Pick Files", style = MaterialTheme.typography.titleSmall)
                  Text(
                    "ISO, PKG, MKV",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }

            OutlinedCard(
              onClick = onScanFolder,
              enabled = !isRunning && !uiState.isScanningSource,
              modifier = Modifier.weight(1f).heightIn(min = 68.dp).testTag("scan_folder_button"),
              shape = MaterialTheme.shapes.medium,
              colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.Rounded.FolderOpen,
                  contentDescription = "Scan folder",
                  tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                  Text("Scan Folder", style = MaterialTheme.typography.titleSmall)
                  Text(
                    "PS3_GAME tree",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }

        if (uiState.isScanningSource) {
          item(key = "scanning") {
            Surface(
              modifier = Modifier.fillMaxWidth(),
              shape = MaterialTheme.shapes.medium,
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
                Text("Scanning storage documents...", style = MaterialTheme.typography.bodyMedium)
              }
            }
          }
        }

        item(key = "destination") {
          DestinationFolderBar(
            destinationName = settings.outputTreeDisplayName,
            enabled = !isRunning,
            onChooseDestination = onPickOutputFolder,
          )
        }

        item(key = "config_card") {
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          ) {
            Column(
              modifier = Modifier.padding(14.dp),
              verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "CHUNK THRESHOLD",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                  text = SplitNaming.formatBytesExact(chunkLimit),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.primary,
                )
              }

              Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                ChunkSizePreset.entries.forEach { preset ->
                  val selected = settings.chunkPreset == preset
                  val label =
                    if (preset == ChunkSizePreset.CUSTOM && selected) {
                      "${settings.customSizeMib} MiB"
                    } else {
                      preset.shortLabel
                    }
                  FilterChip(
                    selected = selected,
                    onClick = { if (!isRunning) onSelectChunkPreset(preset) },
                    enabled = !isRunning,
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("preset_${preset.id}"),
                    colors =
                      FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                      ),
                  )
                }
              }

              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

              Text(
                text = "PART NAMING CONVENTION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                SplitNamingFormat.entries.forEach { format ->
                  val selected = settings.namingFormat == format
                  FilterChip(
                    selected = selected,
                    onClick = { if (!isRunning) onSelectNamingFormat(format) },
                    enabled = !isRunning,
                    label = {
                      Text(
                        text = "${format.sampleSuffix} ${if (format == SplitNamingFormat.PS3_666) "PS3" else ""}".trim(),
                        style = MaterialTheme.typography.labelMedium,
                      )
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("format_${format.id}"),
                    colors =
                      FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                      ),
                  )
                }
              }

              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  modifier = Modifier.weight(1f),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                  )
                  Column {
                    Text("Mirror files ≤ limit", style = MaterialTheme.typography.titleSmall)
                    Text(
                      "Copy smaller files into destination tree",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
                Switch(
                  checked = settings.smallFileAction == SmallFileAction.COPY,
                  onCheckedChange = { copy ->
                    onSelectSmallFileAction(if (copy) SmallFileAction.COPY else SmallFileAction.SKIP)
                  },
                  enabled = !isRunning,
                  modifier = Modifier.testTag("mirror_small_files_switch"),
                )
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  modifier = Modifier.weight(1f),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                  )
                  Column {
                    Text("Verify CRC32 & byte count", style = MaterialTheme.typography.titleSmall)
                    Text(
                      "Validate stream integrity on completion",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
                Switch(
                  checked = settings.verifyChecksums,
                  onCheckedChange = onToggleVerifyChecksum,
                  enabled = !isRunning,
                  modifier = Modifier.testTag("verify_crc_switch"),
                )
              }
            }
          }
        }

        item(key = "queue_header") {
          Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column {
              Text(
                text = "QUEUED FILES (${uiState.splitQueue.size})",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              if (uiState.splitQueue.isNotEmpty()) {
                Text(
                  text = "${uiState.oversizedCount} oversized • ${uiState.totalEstimatedOutputParts} output part(s)",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
            if (uiState.splitQueue.isNotEmpty() && !isRunning) {
              TextButton(
                onClick = onClearQueue,
                modifier = Modifier.heightIn(min = 48.dp).testTag("clear_split_queue_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.DeleteSweep,
                  contentDescription = "Clear queue",
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Clear")
              }
            }
          }
        }

        if (uiState.splitQueue.isEmpty()) {
          item(key = "empty_queue") {
            Surface(
              modifier = Modifier.fillMaxWidth(),
              shape = MaterialTheme.shapes.medium,
              color = MaterialTheme.colorScheme.surfaceContainerLow,
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Storage,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(36.dp),
                )
                Text(
                  text = "No files in split queue",
                  style = MaterialTheme.typography.titleMedium,
                )
                Text(
                  text =
                    "Select a large .ISO / .PKG / video file or scan an entire PS3 game folder. Files larger than ${SplitNaming.formatBytesHuman(chunkLimit)} will automatically slice into ${settings.namingFormat.sampleSuffix} segments.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        } else {
          items(items = uiState.splitQueue, key = { it.id }) { item ->
            SplitQueueItemCard(
              item = item,
              chunkLimitBytes = chunkLimit,
              namingFormat = settings.namingFormat,
              smallFileAction = settings.smallFileAction,
              canRemove = !isRunning,
              onRemove = { onRemoveItem(item.id) },
            )
          }
        }
      }

      // Docked Bottom Action Bar
      if (uiState.splitQueue.isNotEmpty()) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 4.dp,
          shadowElevation = 6.dp,
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Button(
              onClick = onStartSplit,
              enabled = !isRunning && uiState.totalEstimatedOutputParts > 0 && !settings.outputTreeUri.isNullOrBlank(),
              modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("start_split_button"),
              shape = MaterialTheme.shapes.medium,
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primary,
                  contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
              Icon(
                imageVector = if (isRunning) Icons.AutoMirrored.Rounded.CallSplit else Icons.Rounded.PlayArrow,
                contentDescription = null,
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text =
                  when {
                    isRunning -> "Splitting in progress..."
                    settings.outputTreeUri.isNullOrBlank() -> "Select Output Folder Above"
                    uiState.totalEstimatedOutputParts == 0 -> "No Files Exceed Threshold"
                    else -> "Split Now (${uiState.totalEstimatedOutputParts} output file${if (uiState.totalEstimatedOutputParts == 1) "" else "s"})"
                  },
                style = MaterialTheme.typography.labelLarge,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SplitQueueItemCard(
  item: SplitQueueItem,
  chunkLimitBytes: Long,
  namingFormat: SplitNamingFormat,
  smallFileAction: SmallFileAction,
  canRemove: Boolean,
  onRemove: () -> Unit,
) {
  val needsSplit = item.needsSplit(chunkLimitBytes)
  val estParts = if (needsSplit) SplitNaming.calculatePartCount(item.sizeBytes, chunkLimitBytes) else 1
  val firstPartName = SplitNaming.partFileName(item.displayName, 0, namingFormat)
  val lastPartName = SplitNaming.partFileName(item.displayName, (estParts - 1).coerceAtLeast(0), namingFormat)

  Card(
    modifier = Modifier.fillMaxWidth().testTag("split_item_card"),
    shape = MaterialTheme.shapes.medium,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    border =
      BorderStroke(
        1.dp,
        when (item.status) {
          QueueItemStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
          QueueItemStatus.PROCESSING -> MaterialTheme.colorScheme.primary
          QueueItemStatus.FAILED -> MaterialTheme.colorScheme.error
          else ->
            if (needsSplit) MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outlineVariant
        },
      ),
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = item.relativePath,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "${SplitNaming.formatBytesHuman(item.sizeBytes)} (${SplitNaming.formatBytesExact(item.sizeBytes)})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (canRemove) {
          IconButton(
            onClick = onRemove,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("remove_split_item"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Close,
              contentDescription = "Remove ${item.displayName}",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        when (item.status) {
          QueueItemStatus.COMPLETED -> {
            StatusPill(
              text = if (needsSplit) "SPLIT COMPLETE ($estParts parts)" else "COPIED INTACT",
              containerColor = MaterialTheme.colorScheme.tertiaryContainer,
              contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
              icon = Icons.Rounded.CheckCircle,
            )
            item.crc32Hex?.let { crc ->
              Text(
                text = "CRC32 $crc",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
              )
            }
          }
          QueueItemStatus.SKIPPED -> {
            StatusPill(
              text = "SKIPPED (FAT32 OK)",
              containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
              contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          QueueItemStatus.PROCESSING -> {
            StatusPill(
              text = "WRITING PART ${item.currentPartIndex + 1}/$estParts",
              containerColor = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
          else -> {
            if (needsSplit) {
              StatusPill(
                text = "SPLIT → $estParts PARTS",
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
              )
              Text(
                text = "$firstPartName … ${lastPartName.substringAfterLast('.')}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
              )
            } else {
              StatusPill(
                text = if (smallFileAction == SmallFileAction.COPY) "FAT32 SAFE (MIRROR)" else "FAT32 SAFE (SKIP)",
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      if (item.status == QueueItemStatus.PROCESSING && item.sizeBytes > 0L) {
        Spacer(modifier = Modifier.height(10.dp))
        val frac = (item.bytesProcessed.toDouble() / item.sizeBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        LinearProgressIndicator(
          progress = { frac },
          modifier = Modifier.fillMaxWidth().height(5.dp),
          color = MaterialTheme.colorScheme.primary,
          trackColor = MaterialTheme.colorScheme.outlineVariant,
        )
      }
    }
  }
}

@Composable
private fun StatusPill(
  text: String,
  containerColor: androidx.compose.ui.graphics.Color,
  contentColor: androidx.compose.ui.graphics.Color,
  icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
  Surface(
    color = containerColor,
    shape = MaterialTheme.shapes.extraSmall,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = contentColor,
          modifier = Modifier.size(13.dp),
        )
      }
      Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
      )
    }
  }
}
