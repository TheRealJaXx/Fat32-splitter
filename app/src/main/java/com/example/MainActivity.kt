package com.example

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Usb
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
import androidx.compose.runtime.DisposableEffect
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
import com.example.ui.FormatViewModel
import com.example.ui.MainViewModel
import com.example.ui.components.CustomChunkSizeDialog
import com.example.ui.screens.FormatScreen
import com.example.ui.screens.JoinScreen
import com.example.ui.screens.SpecsScreen
import com.example.ui.screens.SplitScreen
import com.example.ui.theme.Split666Theme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      Ps3AssistApp()
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Ps3AssistApp(
  viewModel: MainViewModel = viewModel(),
  formatViewModel: FormatViewModel = viewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val formatUiState by formatViewModel.uiState.collectAsStateWithLifecycle()
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

  LaunchedEffect(Unit) {
    formatViewModel.events.collect { msg ->
      snackbarHostState.showSnackbar(msg)
    }
  }

  // Register USB attach / detach / permission BroadcastReceiver cleanly
  DisposableEffect(context) {
    val receiver =
      object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
          when (intent?.action) {
            FormatViewModel.ACTION_USB_PERMISSION -> {
              val device =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                  @Suppress("DEPRECATION")
                  intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
              val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
              formatViewModel.onUsbPermissionResult(device, granted)
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
              formatViewModel.refreshDevices()
            }
          }
        }
      }

    val filter =
      IntentFilter().apply {
        addAction(FormatViewModel.ACTION_USB_PERMISSION)
        addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
      }
    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    onDispose {
      runCatching { context.unregisterReceiver(receiver) }
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
                    text = if (uiState.activeTab == AppTab.FORMAT) "MBR/NTFS" else "FAT32",
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
                  Icon(Icons.AutoMirrored.Rounded.CallMerge, contentDescription = "Merge split parts")
                },
                label = { Text(stringResource(R.string.tab_join)) },
                modifier = Modifier.testTag("nav_tab_join"),
              )
              NavigationBarItem(
                selected = uiState.activeTab == AppTab.FORMAT,
                onClick = { viewModel.selectTab(AppTab.FORMAT) },
                icon = {
                  Icon(Icons.Rounded.Usb, contentDescription = "Format USB drive")
                },
                label = { Text(stringResource(R.string.tab_format)) },
                modifier = Modifier.testTag("nav_tab_format"),
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
                  Icon(Icons.AutoMirrored.Rounded.CallMerge, contentDescription = "Merge split parts")
                },
                label = { Text(stringResource(R.string.tab_join)) },
                modifier = Modifier.testTag("rail_tab_join"),
              )
              NavigationRailItem(
                selected = uiState.activeTab == AppTab.FORMAT,
                onClick = { viewModel.selectTab(AppTab.FORMAT) },
                icon = {
                  Icon(Icons.Rounded.Usb, contentDescription = "Format USB drive")
                },
                label = { Text(stringResource(R.string.tab_format)) },
                modifier = Modifier.testTag("rail_tab_format"),
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

              AppTab.FORMAT ->
                FormatScreen(
                  uiState = formatUiState,
                  onRefreshDevices = formatViewModel::refreshDevices,
                  onSelectDrive = formatViewModel::selectDrive,
                  onRequestPermission = formatViewModel::requestUsbPermission,
                  onProbeDrive = formatViewModel::probeDrive,
                  onSelectFilesystem = formatViewModel::selectFilesystem,
                  onVolumeLabelChange = formatViewModel::updateVolumeLabel,
                  onSelectClusterSize = formatViewModel::selectClusterSize,
                  onRequestFormat = formatViewModel::openConfirmDialog,
                  onUpdateConfirmTypedText = formatViewModel::updateConfirmTypedText,
                  onConfirmFormat = {
                    requestNotificationsIfNeeded()
                    formatViewModel.executeConfirmedFormat()
                  },
                  onDismissConfirmDialog = formatViewModel::dismissConfirmDialog,
                  onDismissJobState = formatViewModel::dismissJobState,
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
