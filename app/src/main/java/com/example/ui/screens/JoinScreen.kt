package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
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
import com.example.model.JoinGroup
import com.example.model.QueueItemStatus
import com.example.ui.MainUiState
import com.example.ui.components.DestinationFolderBar
import com.example.ui.components.TransferProgressHud

@Composable
fun JoinScreen(
  uiState: MainUiState,
  onPickPartFiles: () -> Unit,
  onScanFolderForParts: () -> Unit,
  onPickOutputFolder: () -> Unit,
  onRemoveGroup: (String) -> Unit,
  onClearGroups: () -> Unit,
  onStartJoin: () -> Unit,
  onCancelTransfer: () -> Unit,
  onDismissProgress: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isRunning = uiState.isTransferRunning
  val settings = uiState.settings
  val readyGroups = uiState.joinGroups.filter { it.isContiguous }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(modifier = Modifier.fillMaxSize().widthIn(max = 840.dp)) {
      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item(key = "join_header_card") {
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          ) {
            Column(modifier = Modifier.padding(18.dp)) {
              Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.extraSmall,
              ) {
                Text(
                  text = "REVERSE REASSEMBLY",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onPrimaryContainer,
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
              }
              Spacer(modifier = Modifier.height(10.dp))
              Text(
                text = "Merge .666xx & .001 Parts",
                style = MaterialTheme.typography.headlineSmall,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text =
                  "Recombine split segments back into the exact original ISO, PKG, or video file with continuity validation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        item(key = "join_hud") {
          TransferProgressHud(
            state = uiState.progressState,
            onCancel = onCancelTransfer,
            onDismiss = onDismissProgress,
          )
        }

        item(key = "join_pickers") {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedCard(
              onClick = onPickPartFiles,
              enabled = !isRunning && !uiState.isScanningSource,
              modifier = Modifier.weight(1f).heightIn(min = 68.dp).testTag("pick_join_files_button"),
              shape = MaterialTheme.shapes.medium,
              colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Rounded.NoteAdd,
                  contentDescription = "Select part files",
                  tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                  Text("Select Parts", style = MaterialTheme.typography.titleSmall)
                  Text(
                    ".66600, .001...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }

            OutlinedCard(
              onClick = onScanFolderForParts,
              enabled = !isRunning && !uiState.isScanningSource,
              modifier = Modifier.weight(1f).heightIn(min = 68.dp).testTag("scan_join_folder_button"),
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
                  contentDescription = "Scan folder for parts",
                  tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                  Text("Scan Folder", style = MaterialTheme.typography.titleSmall)
                  Text(
                    "Auto-group sets",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }

        if (uiState.isScanningSource) {
          item(key = "join_scanning") {
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
                Text("Grouping split part sequences...", style = MaterialTheme.typography.bodyMedium)
              }
            }
          }
        }

        item(key = "join_dest") {
          DestinationFolderBar(
            destinationName = settings.outputTreeDisplayName,
            enabled = !isRunning,
            onChooseDestination = onPickOutputFolder,
          )
        }

        item(key = "join_list_header") {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "DETECTED SETS (${uiState.joinGroups.size})",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (uiState.joinGroups.isNotEmpty() && !isRunning) {
              TextButton(
                onClick = onClearGroups,
                modifier = Modifier.heightIn(min = 48.dp).testTag("clear_join_button"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.DeleteSweep,
                  contentDescription = "Clear detected sets",
                  modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Clear")
              }
            }
          }
        }

        if (uiState.joinGroups.isEmpty()) {
          item(key = "join_empty") {
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
                  imageVector = Icons.AutoMirrored.Rounded.CallMerge,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.size(36.dp),
                )
                Text("No split sets loaded", style = MaterialTheme.typography.titleMedium)
                Text(
                  text =
                    "Pick all parts of a split archive (or scan a folder) to verify sequence continuity and reassemble them into an exFAT / NTFS / internal storage destination.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        } else {
          items(items = uiState.joinGroups, key = { it.id }) { group ->
            JoinGroupCard(
              group = group,
              canRemove = !isRunning,
              onRemove = { onRemoveGroup(group.id) },
            )
          }
        }
      }

      if (uiState.joinGroups.isNotEmpty()) {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 4.dp,
          shadowElevation = 6.dp,
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Button(
              onClick = onStartJoin,
              enabled = !isRunning && readyGroups.isNotEmpty() && !settings.outputTreeUri.isNullOrBlank(),
              modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("start_join_button"),
              shape = MaterialTheme.shapes.medium,
            ) {
              Icon(imageVector = Icons.AutoMirrored.Rounded.CallMerge, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text =
                  when {
                    isRunning -> "Merging in progress..."
                    settings.outputTreeUri.isNullOrBlank() -> "Select Output Folder Above"
                    readyGroups.isEmpty() -> "No Complete Part Sets"
                    else -> "Merge ${readyGroups.size} File Set${if (readyGroups.size == 1) "" else "s"}"
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
private fun JoinGroupCard(
  group: JoinGroup,
  canRemove: Boolean,
  onRemove: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("join_group_card"),
    shape = MaterialTheme.shapes.medium,
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    border =
      BorderStroke(
        1.dp,
        when {
          group.status == QueueItemStatus.COMPLETED -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)
          !group.isContiguous -> MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
          else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        },
      ),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = group.baseFileName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text =
              "${group.parts.size} part(s) • ${group.format.displayName} • ${SplitNaming.formatBytesHuman(group.totalBytes)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (canRemove) {
          IconButton(
            onClick = onRemove,
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(imageVector = Icons.Rounded.Close, contentDescription = "Remove ${group.baseFileName}")
          }
        }
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        if (group.status == QueueItemStatus.COMPLETED) {
          Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.extraSmall) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(13.dp),
              )
              Text(
                text = "MERGED • CRC32 ${group.crc32Hex ?: "OK"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
              )
            }
          }
        } else if (group.isContiguous) {
          Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraSmall) {
            Text(
              text = "CONTIGUOUS SEQUENCE READY",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        } else {
          Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.extraSmall) {
            Row(
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(13.dp),
              )
              Text(
                text = "MISSING PART(S): ${group.missingIndices.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
            }
          }
        }
      }

      if (group.status == QueueItemStatus.PROCESSING && group.totalBytes > 0L) {
        val frac = (group.bytesProcessed.toDouble() / group.totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        LinearProgressIndicator(
          progress = { frac },
          modifier = Modifier.fillMaxWidth().height(5.dp),
        )
      }
    }
  }
}
