package com.example

import android.app.Activity
import android.content.Intent
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.permission.PermissionHelper
import com.example.ui.screens.ControllerSessionScreen
import com.example.ui.screens.HostSessionScreen
import com.example.ui.screens.MainScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AppScreen
import com.example.ui.viewmodel.UsbSessionViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: UsbSessionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleUsbIntent(intent)

        setContent {
            MyApplicationTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                var hasNotifPermission by remember {
                    mutableStateOf(PermissionHelper.hasNotificationPermission(this@MainActivity))
                }

                val notifPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { isGranted ->
                    hasNotifPermission = isGranted
                }

                // Official Android MediaProjection Consent Dialog Launcher
                val projectionManager = remember {
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                }

                val mediaProjectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                        val metrics = resources.displayMetrics
                        viewModel.startScreenSharing(result.resultCode, result.data!!, metrics)
                    }
                }

                // Handle system back navigation
                BackHandler(enabled = uiState.currentScreen != AppScreen.HOME) {
                    when (uiState.currentScreen) {
                        AppScreen.HOST_SESSION -> viewModel.stopSession()
                        AppScreen.CONTROLLER_SESSION -> viewModel.stopSession()
                        AppScreen.SETTINGS, AppScreen.DIAGNOSTICS -> viewModel.navigateTo(
                            if (uiState.isSessionActive) {
                                if (uiState.activeRole == com.example.usb.model.UsbRole.HOST) AppScreen.HOST_SESSION else AppScreen.CONTROLLER_SESSION
                            } else {
                                AppScreen.HOME
                            }
                        )
                        AppScreen.HOME -> Unit
                    }
                }

                when (uiState.currentScreen) {
                    AppScreen.HOME -> {
                        MainScreen(
                            uiState = uiState,
                            hasNotificationPermission = hasNotifPermission,
                            onRequestNotificationPermission = {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onSelectRole = { role -> viewModel.selectRole(role) },
                            onScanUsb = { viewModel.scanDevices() },
                            onRequestDevicePermission = { device -> viewModel.requestDevicePermission(device) },
                            onConnectDevice = { device -> viewModel.connectDevice(device) },
                            onTestConnection = { viewModel.testUsbConnection() },
                            onOpenSettings = { viewModel.navigateTo(AppScreen.SETTINGS) },
                            onDismissBanner = { viewModel.dismissBanner() }
                        )
                    }

                    AppScreen.HOST_SESSION -> {
                        HostSessionScreen(
                            uiState = uiState,
                            onStartScreenSharing = {
                                mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                            },
                            onStopScreenSharing = { viewModel.stopScreenSharing() },
                            onStopSession = { viewModel.stopSession() },
                            onSendTestPing = { viewModel.sendTestPing() },
                            onOpenSettings = { viewModel.navigateTo(AppScreen.SETTINGS) },
                            onToggleRemoteControl = { enabled -> viewModel.setRemoteControlEnabledOnHost(enabled) },
                            onRequestAccessibilityPermission = {
                                com.example.service.RemoteAccessibilityControlService.openAccessibilitySettings(this@MainActivity)
                            }
                        )
                    }

                    AppScreen.CONTROLLER_SESSION -> {
                        ControllerSessionScreen(
                            uiState = uiState,
                            onStopViewing = { viewModel.stopViewing() },
                            onSetSurface = { surface -> viewModel.setDecoderSurface(surface) },
                            onStopSession = { viewModel.stopSession() },
                            onSendTestPing = { viewModel.sendTestPing() },
                            onOpenSettings = { viewModel.navigateTo(AppScreen.SETTINGS) },
                            onToggleTouchControl = { active -> viewModel.setControllerTouchActive(active) },
                            onSendTouchEvent = { action, normX, normY, pointerId ->
                                viewModel.sendRemoteInput(action, normX, normY, pointerId)
                            },
                            onSendNavigation = { action -> viewModel.sendGlobalNavigation(action) },
                            onRequestAccessibilitySettings = {
                                com.example.service.RemoteAccessibilityControlService.openAccessibilitySettings(this@MainActivity)
                            }
                        )
                    }

                    AppScreen.SETTINGS, AppScreen.DIAGNOSTICS -> {
                        SettingsScreen(
                            settings = uiState.settings,
                            onUpdateSettings = { newSettings -> viewModel.updateSettings(newSettings) },
                            onBack = {
                                viewModel.navigateTo(
                                    if (uiState.isSessionActive) {
                                        if (uiState.activeRole == com.example.usb.model.UsbRole.HOST) {
                                            AppScreen.HOST_SESSION
                                        } else {
                                            AppScreen.CONTROLLER_SESSION
                                        }
                                    } else {
                                        AppScreen.HOME
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshAccessibilityStatus()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                viewModel.scanDevices()
            }
        }
    }
}

/**
 * Backward compatibility Greeting composable for tests and previews.
 */
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    MyApplicationTheme { Greeting("USB Screen Link") }
}
