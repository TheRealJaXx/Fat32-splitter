package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.ChunkSizePreset
import com.example.model.SmallFileAction
import com.example.model.SplitNamingFormat
import com.example.model.ThemeMode
import com.example.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "split666_settings")

class SettingsRepository(private val context: Context) {

  private object Keys {
    val CHUNK_PRESET = stringPreferencesKey("chunk_preset")
    val CUSTOM_MIB = longPreferencesKey("custom_mib")
    val NAMING_FORMAT = stringPreferencesKey("naming_format")
    val SMALL_FILE_ACTION = stringPreferencesKey("small_file_action")
    val VERIFY_CHECKSUMS = booleanPreferencesKey("verify_checksums")
    val OVERWRITE_EXISTING = booleanPreferencesKey("overwrite_existing")
    val OUTPUT_TREE_URI = stringPreferencesKey("output_tree_uri")
    val OUTPUT_TREE_NAME = stringPreferencesKey("output_tree_name")
    val THEME_MODE = stringPreferencesKey("theme_mode")
  }

  val settingsFlow: Flow<UserSettings> =
    context.settingsDataStore.data.map { prefs ->
      UserSettings(
        chunkPreset = ChunkSizePreset.fromId(prefs[Keys.CHUNK_PRESET]),
        customSizeMib = (prefs[Keys.CUSTOM_MIB] ?: 100L).coerceIn(1L, 4095L),
        namingFormat = SplitNamingFormat.fromId(prefs[Keys.NAMING_FORMAT]),
        smallFileAction = SmallFileAction.fromId(prefs[Keys.SMALL_FILE_ACTION]),
        verifyChecksums = prefs[Keys.VERIFY_CHECKSUMS] ?: true,
        overwriteExisting = prefs[Keys.OVERWRITE_EXISTING] ?: true,
        outputTreeUri = prefs[Keys.OUTPUT_TREE_URI],
        outputTreeDisplayName = prefs[Keys.OUTPUT_TREE_NAME],
        themeMode =
          runCatching { ThemeMode.valueOf(prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name) }
            .getOrDefault(ThemeMode.DARK),
      )
    }

  suspend fun updateChunkPreset(preset: ChunkSizePreset) {
    context.settingsDataStore.edit { it[Keys.CHUNK_PRESET] = preset.id }
  }

  suspend fun updateCustomMib(mib: Long) {
    context.settingsDataStore.edit {
      it[Keys.CUSTOM_MIB] = mib.coerceIn(1L, 4095L)
      it[Keys.CHUNK_PRESET] = ChunkSizePreset.CUSTOM.id
    }
  }

  suspend fun updateNamingFormat(format: SplitNamingFormat) {
    context.settingsDataStore.edit { it[Keys.NAMING_FORMAT] = format.id }
  }

  suspend fun updateSmallFileAction(action: SmallFileAction) {
    context.settingsDataStore.edit { it[Keys.SMALL_FILE_ACTION] = action.id }
  }

  suspend fun updateVerifyChecksums(enabled: Boolean) {
    context.settingsDataStore.edit { it[Keys.VERIFY_CHECKSUMS] = enabled }
  }

  suspend fun updateOverwriteExisting(enabled: Boolean) {
    context.settingsDataStore.edit { it[Keys.OVERWRITE_EXISTING] = enabled }
  }

  suspend fun updateOutputDestination(uriString: String?, displayName: String?) {
    context.settingsDataStore.edit { prefs ->
      if (uriString == null) {
        prefs.remove(Keys.OUTPUT_TREE_URI)
        prefs.remove(Keys.OUTPUT_TREE_NAME)
      } else {
        prefs[Keys.OUTPUT_TREE_URI] = uriString
        prefs[Keys.OUTPUT_TREE_NAME] = displayName ?: "Selected Folder"
      }
    }
  }

  suspend fun updateThemeMode(mode: ThemeMode) {
    context.settingsDataStore.edit { it[Keys.THEME_MODE] = mode.name }
  }
}
