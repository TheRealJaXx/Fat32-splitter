package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.AppTab
import com.example.model.ThemeMode
import com.example.ui.MainViewModel
import com.example.ui.components.CustomChunkSizeDialog
import com.example.ui.screens.JoinScreen
import com.example.ui.screens.SpecsScreen
import com.example.ui.screens.SplitScreen
import com.example.ui.theme.Split666Theme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      Split666App()
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Split666App(viewModel: MainViewModel = viewModel()) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current
  val snackbarHostState = remember { SnackbarHostState() }

  val darkTheme =
    when (uiState.settings.themeMode) {
      ThemeMode.SYSTEM -> isSystemInDarkTheme()
      ThemeMode.DARK -> true
      ThemeMode.LIGHT -> false
    }

  LaunchedEffect(Unit) {
    viewModel.snackbarEvents.collect { msg ->
      snackbarHostState.showSnackbar(msg)
    }
  }

  // Back button from secondary tabs returns to primary Split tab
  BackHandler(enabled = uiState.activeTab != AppTab.SPLIT) {
    viewModel.selectTab(AppTab.SPLIT)
  }

  // SAF Launchers (zero storage permission required!)
  val splitFilesLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      viewModel.onSplitFilesPicked(uris)
    }

  val splitFolderLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      uri?.let(viewModel::onSplitFolderPicked)
    }

  val joinFilesLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      viewModel.onJoinFilesPicked(uris)
    }

  val joinFolderLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      uri?.let(viewModel::onJoinFolderPicked)
    }

  val outputFolderLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      uri?.let(viewModel::onOutputFolderPicked)
    }

  val notificationPermLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
      // Foreground service runs regardless, notification shown if granted
    }

  fun requestNotificationsIfNeeded() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED
      if (!granted) {
        notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }
  }

  Split666Theme(darkTheme = darkTheme) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
      val useRail = maxWidth >= 700.dp

      Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
          CenterAlignedTopAppBar(
            title = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                  text = stringResource(R.string.app_name),
                  style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                  color = MaterialTheme.colorScheme.primaryContainer,
                  shape = MaterialTheme.shapes.extraSmall,
                ) {
                  Text(
                    text = "FAT32",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }
            },
            colors =
              TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
              ),
          )
        },
        bottomBar = {
          if (!useRail) {
            NavigationBar(
              containerColor = MaterialTheme.colorScheme.surface,
              tonalElevation = 3.dp,
            ) {
              NavigationBarItem(
                selected = uiState.activeTab == AppTab.SPLIT,
                onClick = { viewModel.selectTab(AppTab.SPLIT) },
                icon = {
                  Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = "Split files")
                },
                label = { Text(stringResource(R.string.tab_split)) },
                modifier = Modifier.testTag("nav_tab_split"),
              )
              NavigationBarItem(
                selected = uiState.activeTab == AppTab.JOIN,
                onClick = { viewModel.selectTab(AppTab.JOIN) },
                icon = {
                  Icon(Icons.Rounded.CallMerge, contentDescription = "Merge split parts")
                },
                label = { Text(stringResource(R.string.tab_join)) },
                modifier = Modifier.testTag("nav_tab_join"),
              )
              NavigationBarItem(
                selected = uiState.activeTab == AppTab.SPECS,
                onClick = { viewModel.selectTab(AppTab.SPECS) },
                icon = {
                  Icon(Icons.Rounded.Tune, contentDescription = "Specs and calculator")
                },
                label = { Text(stringResource(R.string.tab_tools)) },
                modifier = Modifier.testTag("nav_tab_specs"),
              )
            }
          }
        },
      ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
          if (useRail) {
            NavigationRail(
              modifier = Modifier.fillMaxHeight(),
              containerColor = MaterialTheme.colorScheme.surface,
            ) {
              NavigationRailItem(
                selected = uiState.activeTab == AppTab.SPLIT,
                onClick = { viewModel.selectTab(AppTab.SPLIT) },
                icon = {
                  Icon(Icons.AutoMirrored.Rounded.CallSplit, contentDescription = "Split files")
                },
                label = { Text(stringResource(R.string.tab_split)) },
                modifier = Modifier.testTag("rail_tab_split"),
              )
              NavigationRailItem(
                selected = uiState.activeTab == AppTab.JOIN,
                onClick = { viewModel.selectTab(AppTab.JOIN) },
                icon = {
                  Icon(Icons.Rounded.CallMerge, contentDescription = "Merge split parts")
                },
                label = { Text(stringResource(R.string.tab_join)) },
                modifier = Modifier.testTag("rail_tab_join"),
              )
              NavigationRailItem(
                selected = uiState.activeTab == AppTab.SPECS,
                onClick = { viewModel.selectTab(AppTab.SPECS) },
                icon = {
                  Icon(Icons.Rounded.Tune, contentDescription = "Specs and calculator")
                },
                label = { Text(stringResource(R.string.tab_tools)) },
                modifier = Modifier.testTag("rail_tab_specs"),
              )
            }
          }

          Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            when (uiState.activeTab) {
              AppTab.SPLIT ->
                SplitScreen(
                  uiState = uiState,
                  onPickFiles = { splitFilesLauncher.launch(arrayOf("*/*")) },
                  onScanFolder = { splitFolderLauncher.launch(null) },
                  onPickOutputFolder = { outputFolderLauncher.launch(null) },
                  onSelectChunkPreset = viewModel::selectChunkPreset,
                  onSelectNamingFormat = viewModel::selectNamingFormat,
                  onSelectSmallFileAction = viewModel::selectSmallFileAction,
                  onToggleVerifyChecksum = viewModel::toggleVerifyChecksums,
                  onRemoveItem = viewModel::removeSplitItem,
                  onClearQueue = viewModel::clearSplitQueue,
                  onStartSplit = {
                    requestNotificationsIfNeeded()
                    viewModel.startSplitJob()
                  },
                  onCancelTransfer = viewModel::cancelTransfer,
                  onDismissProgress = viewModel::dismissProgressState,
                )

              AppTab.JOIN ->
                JoinScreen(
                  uiState = uiState,
                  onPickPartFiles = { joinFilesLauncher.launch(arrayOf("*/*")) },
                  onScanFolderForParts = { joinFolderLauncher.launch(null) },
                  onPickOutputFolder = { outputFolderLauncher.launch(null) },
                  onRemoveGroup = viewModel::removeJoinGroup,
                  onClearGroups = viewModel::clearJoinGroups,
                  onStartJoin = {
                    requestNotificationsIfNeeded()
                    viewModel.startJoinJob()
                  },
                  onCancelTransfer = viewModel::cancelTransfer,
                  onDismissProgress = viewModel::dismissProgressState,
                )

              AppTab.SPECS ->
                SpecsScreen(
                  uiState = uiState,
                  onToggleOverwrite = viewModel::toggleOverwriteExisting,
                  onToggleVerifyChecksum = viewModel::toggleVerifyChecksums,
                  onSelectThemeMode = viewModel::selectThemeMode,
                )
            }
          }
        }
      }

      if (uiState.showCustomSizeDialog) {
        CustomChunkSizeDialog(
          initialMib = uiState.settings.customSizeMib,
          onDismiss = viewModel::dismissCustomSizeDialog,
          onConfirm = viewModel::saveCustomSizeMib,
        )
      }
    }
  }
}
