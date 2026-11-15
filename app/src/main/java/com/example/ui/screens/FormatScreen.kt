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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.engine.SplitNaming
import com.example.format.FormatJobState
import com.example.format.PartitionScheme
import com.example.format.TargetFilesystem
import com.example.format.UsbDriveInfo
import com.example.ui.FormatUiState

@Composable
fun FormatScreen(
  uiState: FormatUiState,
  onRefreshDevices: () -> Unit,
  onSelectDrive: (Int) -> Unit,
  onRequestPermission: (Int) -> Unit,
  onProbeDrive: (Int) -> Unit,
  onSelectFilesystem: (TargetFilesystem) -> Unit,
  onVolumeLabelChange: (String) -> Unit,
  onSelectClusterSize: (Int) -> Unit,
  onRequestFormat: () -> Unit,
  onUpdateConfirmTypedText: (String) -> Unit,
  onConfirmFormat: () -> Unit,
  onDismissConfirmDialog: () -> Unit,
  onDismissJobState: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isBusy = uiState.isFormatting
  val selectedDrive = uiState.selectedDrive

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(modifier = Modifier.fillMaxSize().widthIn(max = 840.dp)) {
      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item(key = "format_hero") {
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          ) {
            Column(modifier = Modifier.padding(18.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Surface(
                  color = MaterialTheme.colorScheme.secondaryContainer,
                  shape = MaterialTheme.shapes.extraSmall,
                ) {
                  Text(
                    text = "USB OTG • NO ROOT REQUIRED",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                  )
                }
                OutlinedButton(
                  onClick = onRefreshDevices,
                  enabled = !isBusy,
                  modifier = Modifier.heightIn(min = 48.dp).testTag("refresh_usb_button"),
                ) {
                  Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh USB devices",
                    modifier = Modifier.size(16.dp),
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Scan USB")
                }
              }
              Spacer(modifier = Modifier.height(10.dp))
              Text(
                text = "MBR Drive Formatter (NTFS / FAT32)",
                style = MaterialTheme.typography.headlineSmall,
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text =
                  "Converts GPT or exFAT flash drives into PS3-compatible MBR + NTFS or MBR + FAT32 directly over USB Host.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        if (uiState.jobState !is FormatJobState.Idle) {
          item(key = "format_job_status") {
            FormatJobStatusCard(
              jobState = uiState.jobState,
              onDismiss = onDismissJobState,
            )
          }
        }

        item(key = "usb_header") {
          Text(
            text = "ATTACHED USB MASS STORAGE (${uiState.drives.size})",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }

        if (uiState.drives.isEmpty()) {
          item(key = "usb_empty") {
            Surface(
              modifier = Modifier.fillMaxWidth().testTag("usb_empty_state"),
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
                  imageVector = Icons.Rounded.Usb,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(38.dp),
                )
                Text("No USB OTG drive detected", style = MaterialTheme.typography.titleMedium)
                Text(
                  text =
                    "Connect a USB flash drive or external SSD via USB-C OTG adapter, then tap Scan USB. PS3 Assist writes raw MBR and NTFS/FAT32 sectors over Android USB Host without root.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        } else {
          items(items = uiState.drives, key = { it.deviceId }) { drive ->
            UsbDriveCard(
              drive = drive,
              selected = selectedDrive?.deviceId == drive.deviceId,
              enabled = !isBusy,
              onSelect = { onSelectDrive(drive.deviceId) },
              onGrantPermission = { onRequestPermission(drive.deviceId) },
              onProbe = { onProbeDrive(drive.deviceId) },
            )
          }
        }

        item(key = "fs_config") {
          Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Text(
                text = "TARGET PARTITION & FILESYSTEM",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

              Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TargetFilesystem.entries.forEach { fs ->
                  val selected = uiState.filesystem == fs
                  OutlinedCard(
                    onClick = { if (!isBusy) onSelectFilesystem(fs) },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth().testTag("fs_option_${fs.id}"),
                    shape = MaterialTheme.shapes.small,
                    colors =
                      CardDefaults.outlinedCardColors(
                        containerColor =
                          if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                          else MaterialTheme.colorScheme.surfaceContainerLow
                      ),
                    border =
                      BorderStroke(
                        width = if (selected) 1.5.dp else 1.dp,
                        color =
                          if (selected) MaterialTheme.colorScheme.primary
                          else MaterialTheme.colorScheme.outlineVariant,
                      ),
                  ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                      ) {
                        Text(fs.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Surface(
                          color =
                            if (fs == TargetFilesystem.MBR_NTFS) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer,
                          shape = MaterialTheme.shapes.extraSmall,
                        ) {
                          Text(
                            text = fs.badge,
                            style = MaterialTheme.typography.labelSmall,
                            color =
                              if (fs == TargetFilesystem.MBR_NTFS) MaterialTheme.colorScheme.onPrimaryContainer
                              else MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                          )
                        }
                      }
                      Text(
                        text = fs.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }
                }
              }

              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

              OutlinedTextField(
                value = uiState.volumeLabel,
                onValueChange = onVolumeLabelChange,
                enabled = !isBusy,
                singleLine = true,
                label = { Text("Volume Label") },
                supportingText = {
                  Text(
                    if (uiState.filesystem == TargetFilesystem.MBR_FAT32) {
                      "FAT32 stores up to 11 uppercase ASCII characters"
                    } else {
                      "NTFS supports up to 32 characters"
                    }
                  )
                },
                modifier = Modifier.fillMaxWidth().testTag("volume_label_input"),
              )

              Text(
                text = "ALLOCATION UNIT (CLUSTER SIZE)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                uiState.filesystem.clusterOptionsBytes.forEach { clusterBytes ->
                  FilterChip(
                    selected = uiState.clusterSizeBytes == clusterBytes,
                    onClick = { if (!isBusy) onSelectClusterSize(clusterBytes) },
                    enabled = !isBusy,
                    label = {
                      Text("${clusterBytes / 1024}K", style = MaterialTheme.typography.labelMedium)
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("cluster_${clusterBytes / 1024}k"),
                  )
                }
              }
            }
          }
        }

        item(key = "wipe_notice") {
          Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.55f)),
          ) {
            Row(
              modifier = Modifier.padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = "Warning",
                tint = MaterialTheme.colorScheme.error,
              )
              Text(
                text =
                  if (uiState.filesystem == TargetFilesystem.MBR_NTFS) {
                    "Formatting erases all data and writes MBR + NTFS. Note: Most Android phones mount NTFS read-only in the system Files app (for PS3/PC use); choose MBR + FAT32 if you need to copy split files directly from Android."
                  } else {
                    "Formatting writes a fresh MBR + FAT32 partition, wipes GPT headers, and erases all files. Replug the USB drive after formatting so Android mounts it read-write."
                  },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
      }

      // Docked Action Bar
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
          Button(
            onClick = onRequestFormat,
            enabled = !isBusy && selectedDrive != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("format_drive_button"),
            shape = MaterialTheme.shapes.medium,
            colors =
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
              ),
          ) {
            Icon(imageVector = Icons.Rounded.DeleteForever, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text =
                when {
                  isBusy -> "Formatting USB Drive..."
                  selectedDrive == null -> "Connect USB OTG Drive"
                  !selectedDrive.hasPermission -> "Grant USB Access to Continue"
                  !selectedDrive.probed -> "Inspect Drive Sectors"
                  else -> "Format to ${uiState.filesystem.title}"
                },
              style = MaterialTheme.typography.labelLarge,
            )
          }
        }
      }
    }

    if (uiState.showConfirmDialog && selectedDrive != null) {
      FormatTypedConfirmDialog(
        drive = selectedDrive,
        filesystem = uiState.filesystem,
        volumeLabel = uiState.volumeLabel,
        typedText = uiState.confirmTypedText,
        canExecute = uiState.isConfirmPhraseMatched,
        onTypedTextChange = onUpdateConfirmTypedText,
        onConfirm = onConfirmFormat,
        onDismiss = onDismissConfirmDialog,
      )
    }
  }
}

@Composable
private fun UsbDriveCard(
  drive: UsbDriveInfo,
  selected: Boolean,
  enabled: Boolean,
  onSelect: () -> Unit,
  onGrantPermission: () -> Unit,
  onProbe: () -> Unit,
) {
  OutlinedCard(
    onClick = onSelect,
    enabled = enabled,
    modifier = Modifier.fillMaxWidth().testTag("usb_drive_card_${drive.deviceId}"),
    shape = MaterialTheme.shapes.medium,
    colors =
      CardDefaults.outlinedCardColors(
        containerColor =
          if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
          else MaterialTheme.colorScheme.surfaceContainer
      ),
    border =
      BorderStroke(
        width = if (selected) 1.5.dp else 1.dp,
        color =
          if (selected) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.outlineVariant,
      ),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Rounded.Storage,
          contentDescription = null,
          tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = drive.displayTitle,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = "USB ID ${drive.vidPidHex} • ${drive.deviceName}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      if (drive.probed) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraSmall) {
            Text(
              text = SplitNaming.formatBytesHuman(drive.capacityBytes),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
          Surface(
            color =
              if (drive.detectedScheme == PartitionScheme.GPT) MaterialTheme.colorScheme.secondaryContainer
              else MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = MaterialTheme.shapes.extraSmall,
          ) {
            Text(
              text = "CURRENT: ${drive.detectedScheme.label}",
              style = MaterialTheme.typography.labelSmall,
              color =
                if (drive.detectedScheme == PartitionScheme.GPT) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
          }
          Text(
            text = "${drive.sectorSizeBytes}B sectors",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text =
              drive.probeError
                ?: if (drive.hasPermission) "Ready to probe SCSI geometry"
                else "USB Host permission required",
            style = MaterialTheme.typography.bodySmall,
            color =
              if (drive.probeError != null) MaterialTheme.colorScheme.error
              else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.weight(1f),
          )
          Spacer(modifier = Modifier.width(8.dp))
          OutlinedButton(
            onClick = if (drive.hasPermission) onProbe else onGrantPermission,
            enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag("usb_grant_probe_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.LockOpen,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (drive.hasPermission) "Inspect" else "Grant Access")
          }
        }
      }
    }
  }
}

@Composable
private fun FormatJobStatusCard(
  jobState: FormatJobState,
  onDismiss: () -> Unit,
) {
  when (jobState) {
    is FormatJobState.Probing -> {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
      ) {
        Row(
          modifier = Modifier.padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
          Text("Probing SCSI geometry for ${jobState.deviceTitle}...", style = MaterialTheme.typography.bodyMedium)
        }
      }
    }

    is FormatJobState.Formatting -> {
      Card(
        modifier = Modifier.fillMaxWidth().testTag("format_running_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        shape = MaterialTheme.shapes.medium,
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "FORMATTING TO ${jobState.filesystem.title}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(jobState.deviceTitle, style = MaterialTheme.typography.titleSmall)
          Text(
            text = jobState.stageLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          LinearProgressIndicator(
            progress = { jobState.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            strokeCap = StrokeCap.Round,
          )
        }
      }
    }

    is FormatJobState.Completed -> {
      Card(
        modifier = Modifier.fillMaxWidth().testTag("format_completed_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary),
        shape = MaterialTheme.shapes.medium,
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(14.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = "Format complete",
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(28.dp),
          )
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Formatted to ${jobState.filesystem.title}",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
            )
            Text(
              text =
                if (jobState.filesystem == TargetFilesystem.MBR_NTFS) {
                  "Label '${jobState.volumeLabel}' verified • ${jobState.clusterSizeBytes / 1024} KiB clusters (${SplitNaming.formatDuration(jobState.elapsedMillis)}). Unplug & replug drive. Note: Android's Files app often mounts NTFS read-only even when formatted cleanly; PS3 (IrisMAN/prepISO) & PCs have full access."
                } else {
                  "Label '${jobState.volumeLabel}' verified • ${jobState.clusterSizeBytes / 1024} KiB clusters (${SplitNaming.formatDuration(jobState.elapsedMillis)}). Unplug and replug USB drive so Android mounts it read-write."
                },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
            Icon(imageVector = Icons.Rounded.Close, contentDescription = "Dismiss")
          }
        }
      }
    }

    is FormatJobState.Failed -> {
      Card(
        modifier = Modifier.fillMaxWidth().testTag("format_failed_card"),
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
            contentDescription = "Format failed",
            tint = MaterialTheme.colorScheme.error,
          )
          Spacer(modifier = Modifier.width(12.dp))
          Text(
            text = jobState.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
          )
          IconButton(onClick = onDismiss, modifier = Modifier.minimumInteractiveComponentSize()) {
            Icon(imageVector = Icons.Rounded.Close, contentDescription = "Dismiss")
          }
        }
      }
    }

    FormatJobState.Idle -> Unit
  }
}

@Composable
private fun FormatTypedConfirmDialog(
  drive: UsbDriveInfo,
  filesystem: TargetFilesystem,
  volumeLabel: String,
  typedText: String,
  canExecute: Boolean,
  onTypedTextChange: (String) -> Unit,
  onConfirm: () -> Unit,
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Icon(
        imageVector = Icons.Rounded.DeleteForever,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
      )
    },
    title = {
      Text("Erase & Format Drive?", style = MaterialTheme.typography.titleLarge)
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
          color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
          shape = MaterialTheme.shapes.small,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
        ) {
          Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(drive.displayTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
              text =
                "Capacity: ${SplitNaming.formatBytesHuman(drive.capacityBytes)} • Target: ${filesystem.title} • Label: ${volumeLabel.ifBlank { "PS3USB" }}",
              style = MaterialTheme.typography.labelMedium,
            )
            Text(
              text = "ALL DATA ON THIS USB DRIVE WILL BE PERMANENTLY LOST.",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }

        Text(
          text = "Type ${FormatUiState.REQUIRED_CONFIRM_PHRASE} in uppercase below to authorize raw sector writes:",
          style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
          value = typedText,
          onValueChange = onTypedTextChange,
          singleLine = true,
          placeholder = { Text(FormatUiState.REQUIRED_CONFIRM_PHRASE) },
          isError = typedText.isNotEmpty() && !canExecute,
          modifier = Modifier.fillMaxWidth().testTag("confirm_format_text_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        enabled = canExecute,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
          ),
        modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_format_execute_button"),
      ) {
        Text("Erase & Format")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Cancel")
      }
    },
  )
}
