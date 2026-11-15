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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.engine.SplitNaming
import com.example.model.ThemeMode
import com.example.ui.MainUiState

@Composable
fun SpecsScreen(
  uiState: MainUiState,
  onToggleOverwrite: (Boolean) -> Unit,
  onToggleVerifyChecksum: (Boolean) -> Unit,
  onSelectThemeMode: (ThemeMode) -> Unit,
  modifier: Modifier = Modifier,
) {
  val settings = uiState.settings
  var calcGibInput by remember { mutableStateOf("8.5") }
  val parsedGib = calcGibInput.toDoubleOrNull()?.coerceIn(0.01, 200.0) ?: 0.0
  val simBytes = (parsedGib * 1024.0 * 1024.0 * 1024.0).toLong()
  val partSizes =
    remember(simBytes, settings.effectiveChunkSizeBytes) {
      SplitNaming.calculatePartSizes(simBytes, settings.effectiveChunkSizeBytes)
    }

  Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
      modifier = Modifier.fillMaxSize().widthIn(max = 840.dp),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item(key = "calculator") {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.medium,
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Calculate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
              Text("FAT32 Chunk Calculator", style = MaterialTheme.typography.titleMedium)
            }

            Text(
              text =
                "Preview exact part names and byte sizes using your active chunk threshold (${SplitNaming.formatBytesHuman(settings.effectiveChunkSizeBytes)}).",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
              value = calcGibInput,
              onValueChange = { raw ->
                calcGibInput = raw.filter { it.isDigit() || it == '.' }.take(6)
              },
              label = { Text("Simulated ISO / File Size (GiB)") },
              suffix = { Text("GiB") },
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              modifier = Modifier.fillMaxWidth().testTag("calculator_gib_input"),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              listOf("4.7" to "DVD5", "8.5" to "DVD9", "22.5" to "BD25", "45.0" to "BD50").forEach { (gib, label) ->
                FilterChip(
                  selected = calcGibInput == gib,
                  onClick = { calcGibInput = gib },
                  label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                  modifier = Modifier.heightIn(min = 48.dp),
                )
              }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
              text = "PREVIEW (${partSizes.size} OUTPUT PART${if (partSizes.size == 1) "" else "S"})",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )

            partSizes.take(6).forEachIndexed { idx, bytes ->
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = SplitNaming.partFileName("backup.iso", idx, settings.namingFormat),
                  style = MaterialTheme.typography.labelMedium,
                )
                Text(
                  text = "${SplitNaming.formatBytesHuman(bytes)} (${SplitNaming.formatBytesExact(bytes)})",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            if (partSizes.size > 6) {
              Text(
                text = "... and ${partSizes.size - 6} more part(s)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      item(key = "preferences") {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.medium,
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Transfer & Appearance", style = MaterialTheme.typography.titleMedium)

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("Overwrite existing outputs", style = MaterialTheme.typography.titleSmall)
                Text(
                  "Replace matching .666xx or merged files in destination",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.overwriteExisting,
                onCheckedChange = onToggleOverwrite,
                modifier = Modifier.testTag("overwrite_switch"),
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text("CRC32 integrity check", style = MaterialTheme.typography.titleSmall)
                Text(
                  "Compute stream checksum and verify exact byte counts",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
              Switch(
                checked = settings.verifyChecksums,
                onCheckedChange = onToggleVerifyChecksum,
              )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
              text = "THEME APPEARANCE",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              ThemeMode.entries.forEach { mode ->
                FilterChip(
                  selected = settings.themeMode == mode,
                  onClick = { onSelectThemeMode(mode) },
                  label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) },
                  modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("theme_${mode.name.lowercase()}"),
                )
              }
            }
          }
        }
      }

      item(key = "fat32_guide") {
        Surface(
          modifier = Modifier.fillMaxWidth(),
          shape = MaterialTheme.shapes.medium,
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
          Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
              )
              Text("PS3 & FAT32 Reference", style = MaterialTheme.typography.titleMedium)
            }
            GuideBullet(
              title = "Why 4,294,967,295 bytes?",
              body =
                "FAT32 stores file sizes as an unsigned 32-bit integer, making 4 GiB minus 1 byte the absolute ceiling. 4095 MiB leaves a 1 MiB margin.",
            )
            GuideBullet(
              title = "How .66600 naming works",
              body =
                "Backup managers such as multiMAN and irisMAN automatically detect .66600, .66601, ... sequences inside PS3_GAME or PS3ISO folders and reassemble them when copying to internal storage.",
            )
            GuideBullet(
              title = "USB OTG on Android",
              body =
                "Plug in a FAT32-formatted USB drive via USB-C OTG, tap 'Choose Destination', and grant folder access directly through Android's Storage Access Framework.",
            )
          }
        }
      }
    }
  }
}

@Composable
private fun GuideBullet(title: String, body: String) {
  Column(modifier = Modifier.padding(top = 4.dp)) {
    Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(modifier = Modifier.height(2.dp))
    Text(text = body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  }
}
