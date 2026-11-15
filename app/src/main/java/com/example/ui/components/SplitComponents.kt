package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DriveFolderUpload
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.SplitNaming
import com.example.model.ChunkSizePreset
import com.example.model.TransferProgressState

@Composable
fun HeroBannerCard(
  chunkLimitBytes: Long,
  namingSample: String,
  compact: Boolean,
  modifier: Modifier = Modifier,
) {
  val height = if (compact) 112.dp else 164.dp
  Surface(
    modifier = modifier.fillMaxWidth().height(height),
    shape = MaterialTheme.shapes.large,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    tonalElevation = 2.dp,
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      Image(
        painter = painterResource(id = R.drawable.img_split_hero),
        contentDescription = "FAT32 binary data splitting illustration",
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
      )
      Box(
        modifier =
          Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
              colors =
                listOf(
                  MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                  MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                  MaterialTheme.colorScheme.background.copy(alpha = 0.35f),
                )
            )
          )
      )
      Column(
        modifier = Modifier.fillMaxSize().padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
            shape = MaterialTheme.shapes.extraSmall,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)),
          ) {
            Text(
              text = "FAT32 LIMIT ≤ 4,294,967,295 B",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
            shape = MaterialTheme.shapes.extraSmall,
          ) {
            Text(
              text = namingSample,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
        }

        Column {
          Text(
            text = if (compact) "PS3 & FAT32 Engine Ready" else "Split Oversized Files for FAT32",
            style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            color = Color(0xFFF0F4FA),
          )
          if (!compact) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text =
                "Slice ISOs, PKGs, and archives into ${SplitNaming.formatBytesHuman(chunkLimitBytes)} segments for USB OTG & multiMAN.",
              style = MaterialTheme.typography.bodyMedium,
              color = Color(0xFFB8C4D6),
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

@Composable
fun DestinationFolderBar(
  destinationName: String?,
  enabled: Boolean,
  onChooseDestination: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val hasDest = !destinationName.isNullOrBlank()
  Surface(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(min = 56.dp)
        .clip(MaterialTheme.shapes.medium)
        .clickable(enabled = enabled, onClick = onChooseDestination)
        .testTag("destination_folder_button"),
    shape = MaterialTheme.shapes.medium,
    color =
      if (hasDest) MaterialTheme.colorScheme.surfaceContainer
      else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.32f),
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (hasDest) MaterialTheme.colorScheme.outlineVariant
          else MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
      ),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier =
          Modifier
            .size(38.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
              if (hasDest) MaterialTheme.colorScheme.primaryContainer
              else MaterialTheme.colorScheme.secondaryContainer
            ),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = if (hasDest) Icons.Rounded.FolderOpen else Icons.Rounded.DriveFolderUpload,
          contentDescription = "Destination folder",
          tint =
            if (hasDest) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSecondaryContainer,
          modifier = Modifier.size(20.dp),
        )
      }
      Spacer(modifier = Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "OUTPUT DESTINATION (USB / FOLDER)",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = destinationName ?: "Tap to select target folder or FAT32 USB drive",
          style = MaterialTheme.typography.titleSmall,
          color =
            if (hasDest) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.secondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = if (hasDest) "CHANGE" else "SELECT",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
fun TransferProgressHud(
  state: TransferProgressState,
  onCancel: () -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  AnimatedVisibility(visible = state !is TransferProgressState.Idle, modifier = modifier) {
    when (state) {
      is TransferProgressState.Running -> {
        val animatedProgress by
          animateFloatAsState(
            targetValue = state.overallProgress,
            animationSpec = spring(),
            label = "transfer_progress",
          )
        Card(
          modifier = Modifier.fillMaxWidth().testTag("transfer_running_card"),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
          shape = MaterialTheme.shapes.medium,
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(
                modifier =
                  Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text =
                  if (state.isJoinMode) {
                    "MERGING ${state.fileIndex}/${state.totalFiles}"
                  } else {
                    "SPLITTING ${state.fileIndex}/${state.totalFiles}"
                  },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
              )
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Rounded.Speed,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                  modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = SplitNaming.formatSpeed(state.speedBytesPerSec),
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.secondary,
                )
              }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = state.currentFileName,
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = state.currentPartLabel,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
              progress = { animatedProgress },
              modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
              color = MaterialTheme.colorScheme.primary,
              trackColor = MaterialTheme.colorScheme.outlineVariant,
              strokeCap = StrokeCap.Round,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                text =
                  "${SplitNaming.formatBytesHuman(state.bytesProcessed)} / ${SplitNaming.formatBytesHuman(state.totalBytes)} • ${SplitNaming.formatDuration(state.elapsedMillis)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.heightIn(min = 48.dp).testTag("cancel_transfer_button"),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Cancel,
                  contentDescription = "Cancel transfer",
                  modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Cancel")
              }
            }
          }
        }
      }

      is TransferProgressState.Completed -> {
        Card(
          modifier = Modifier.fillMaxWidth().testTag("transfer_completed_card"),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)),
          shape = MaterialTheme.shapes.medium,
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = if (state.verifiedChecksums) Icons.Rounded.Verified else Icons.Rounded.CheckCircle,
              contentDescription = "Completed",
              tint = MaterialTheme.colorScheme.tertiary,
              modifier = Modifier.size(28.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text =
                  if (state.isJoinMode) "Merge Complete & Byte-Verified"
                  else "Split Complete & FAT32 Ready",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text =
                  "${state.filesProcessed} file(s) • ${state.partsCreated} output(s) • ${SplitNaming.formatBytesHuman(state.totalBytesProcessed)} in ${SplitNaming.formatDuration(state.elapsedMillis)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(
              onClick = onDismiss,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("dismiss_completed_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Dismiss status",
              )
            }
          }
        }
      }

      is TransferProgressState.Cancelled -> {
        Card(
          modifier = Modifier.fillMaxWidth().testTag("transfer_cancelled_card"),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
          shape = MaterialTheme.shapes.medium,
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Rounded.Cancel,
              contentDescription = "Cancelled",
              tint = MaterialTheme.colorScheme.secondary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              text = "Operation cancelled (${state.filesCompletedBeforeCancel} completed)",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.weight(1f),
            )
            IconButton(
              onClick = onDismiss,
              modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
              Icon(imageVector = Icons.Rounded.Close, contentDescription = "Dismiss")
            }
          }
        }
      }

      is TransferProgressState.Failed -> {
        Card(
          modifier = Modifier.fillMaxWidth().testTag("transfer_failed_card"),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
          shape = MaterialTheme.shapes.medium,
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Rounded.ErrorOutline,
              contentDescription = "Error",
              tint = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
              text = state.message,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.weight(1f),
            )
            IconButton(
              onClick = onDismiss,
              modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
              Icon(imageVector = Icons.Rounded.Close, contentDescription = "Dismiss error")
            }
          }
        }
      }

      TransferProgressState.Idle -> Unit
    }
  }
}

@Composable
fun CustomChunkSizeDialog(
  initialMib: Long,
  onDismiss: () -> Unit,
  onConfirm: (Long) -> Unit,
) {
  var textValue by remember(initialMib) { mutableStateOf(initialMib.toString()) }
  val parsed = textValue.toLongOrNull()
  val isValid = parsed != null && parsed in 1L..4095L
  val resultingBytes =
    if (isValid) {
      (parsed!! * 1024L * 1024L).coerceAtMost(ChunkSizePreset.FAT32_MAX_BYTES)
    } else {
      0L
    }

  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(imageVector = Icons.Rounded.Tune, contentDescription = null)
    },
    title = {
      Text("Custom Chunk Size (MiB)", style = MaterialTheme.typography.titleLarge)
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "Enter a chunk threshold from 1 to 4095 MiB. FAT32 enforces a strict ceiling of 4,294,967,295 bytes.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = textValue,
          onValueChange = { input -> textValue = input.filter { it.isDigit() }.take(4) },
          label = { Text("Size in MiB") },
          suffix = { Text("MiB") },
          singleLine = true,
          isError = !isValid,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          supportingText = {
            if (isValid) {
              Text(
                text = "Exact limit: ${SplitNaming.formatBytesExact(resultingBytes)}",
                style = MaterialTheme.typography.labelSmall,
              )
            } else {
              Text("Enter a value between 1 and 4095 MiB")
            }
          },
          modifier = Modifier.fillMaxWidth().testTag("custom_mib_input"),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          listOf(10L, 100L, 512L, 2048L).forEach { quick ->
            Surface(
              modifier =
                Modifier
                  .weight(1f)
                  .heightIn(min = 40.dp)
                  .clip(MaterialTheme.shapes.small)
                  .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                  .clickable { textValue = quick.toString() },
              color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
              Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                Text("${quick}M", style = MaterialTheme.typography.labelMedium)
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { parsed?.let(onConfirm) },
        enabled = isValid,
        modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_custom_mib_button"),
      ) {
        Text("Apply")
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text("Cancel")
      }
    },
  )
}
