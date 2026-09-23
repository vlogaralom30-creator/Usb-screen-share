package com.example.ui.screens

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.ControllerModeAccent
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.DarkTextMuted
import com.example.ui.theme.DarkTextPrimary
import com.example.ui.theme.DarkTextSecondary
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.StatusConnectedGreen
import com.example.ui.theme.StatusErrorRed
import com.example.ui.theme.StatusWarningAmber
import com.example.ui.viewmodel.UsbUiState
import com.example.usb.model.UsbConnectionState
import com.example.usb.model.UsbProtocol
import com.example.usb.model.ViewportBounds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerSessionScreen(
    uiState: UsbUiState,
    onStopViewing: () -> Unit,
    onSetSurface: (Surface?) -> Unit,
    onStopSession: () -> Unit,
    onSendTestPing: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleTouchControl: (Boolean) -> Unit = {},
    onSendTouchEvent: (action: Int, normX: Float, normY: Float, pointerId: Int) -> Unit = { _, _, _, _ -> },
    onSendNavigation: (action: Int) -> Unit = {},
    onRequestAccessibilitySettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val isConnected = uiState.connectionState is UsbConnectionState.Connected
    val isStreaming = uiState.isStreamingActive || uiState.decoderStats.isDecoding

    // Compute aspect ratio from actual incoming video stream or default to 9:18 portrait
    val videoAspect = if (uiState.decoderStats.videoWidth > 0 && uiState.decoderStats.videoHeight > 0) {
        uiState.decoderStats.videoWidth.toFloat() / uiState.decoderStats.videoHeight.toFloat()
    } else {
        9f / 18f
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DarkBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isStreaming) StatusConnectedGreen else ControllerModeAccent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isStreaming) "PHONE 1 SCREEN (LIVE)" else "CONTROLLER MODE",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = DarkTextPrimary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onStopSession) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = DarkTextPrimary
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = DarkTextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Live Stream Viewport
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("controller_viewport_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(
                        if (isStreaming) StatusConnectedGreen else DarkSurfaceBorder
                    )
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Video Display Box with correct Aspect Ratio
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(videoAspect)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black)
                            .pointerInput(uiState.isControllerTouchActive, uiState.decoderStats.videoWidth, uiState.decoderStats.videoHeight) {
                                if (!uiState.isControllerTouchActive) return@pointerInput
                                val streamW = if (uiState.decoderStats.videoWidth > 0) uiState.decoderStats.videoWidth.toFloat() else 1080f
                                val streamH = if (uiState.decoderStats.videoHeight > 0) uiState.decoderStats.videoHeight.toFloat() else 1920f
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        val change = event.changes.firstOrNull() ?: continue
                                        val mapper = ViewportBounds(
                                            viewportWidth = size.width.toFloat(),
                                            viewportHeight = size.height.toFloat(),
                                            streamWidth = streamW,
                                            streamHeight = streamH
                                        )
                                        val (normX, normY) = mapper.toNormalized(change.position.x, change.position.y)
                                        val action = when {
                                            change.changedToDown() -> UsbProtocol.INPUT_ACTION_DOWN
                                            change.changedToUp() -> UsbProtocol.INPUT_ACTION_UP
                                            change.positionChanged() -> UsbProtocol.INPUT_ACTION_MOVE
                                            else -> null
                                        }
                                        if (action != null) {
                                            onSendTouchEvent(action, normX, normY, change.id.value.toInt())
                                            change.consume()
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Real hardware SurfaceView for direct zero-copy GPU decoding
                        AndroidView(
                            factory = { ctx ->
                                SurfaceView(ctx).apply {
                                    holder.addCallback(object : SurfaceHolder.Callback {
                                        override fun surfaceCreated(holder: SurfaceHolder) {
                                            onSetSurface(holder.surface)
                                        }

                                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                            onSetSurface(holder.surface)
                                        }

                                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                                            onSetSurface(null)
                                        }
                                    })
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Standby Overlay if stream has not started yet
                        if (!isStreaming) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xE60A0E14))
                                    .padding(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Tv,
                                    contentDescription = null,
                                    tint = ControllerModeAccent,
                                    modifier = Modifier.size(52.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Awaiting Live Screen Stream",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = DarkTextPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Connect USB cable to Phone 1 and tap \"START SCREEN SHARING\" on Phone 1.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DarkTextSecondary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }

                        // Touch Control Active Badge Overlay
                        if (uiState.isControllerTouchActive && isStreaming) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 10.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(Color(0xCC0A0E14))
                                    .border(1.dp, ElectricCyan.copy(alpha = 0.8f), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(ElectricCyan)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "TOUCH CONTROL ACTIVE",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = ElectricCyan
                                    )
                                }
                            }
                        }
                    }

                    // Navigation bar for Phone 1 system commands
                    if (uiState.isControllerTouchActive) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { onSendNavigation(UsbProtocol.INPUT_ACTION_BACK) },
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
                                modifier = Modifier.testTag("nav_back_button")
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(16.dp), tint = ElectricCyan)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("BACK", style = MaterialTheme.typography.labelMedium, color = DarkTextPrimary)
                            }

                            OutlinedButton(
                                onClick = { onSendNavigation(UsbProtocol.INPUT_ACTION_HOME) },
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
                                modifier = Modifier.testTag("nav_home_button")
                            ) {
                                Icon(Icons.Default.Home, contentDescription = "Home", modifier = Modifier.size(16.dp), tint = ElectricCyan)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("HOME", style = MaterialTheme.typography.labelMedium, color = DarkTextPrimary)
                            }

                            OutlinedButton(
                                onClick = { onSendNavigation(UsbProtocol.INPUT_ACTION_RECENTS) },
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, DarkSurfaceBorder),
                                modifier = Modifier.testTag("nav_recents_button")
                            ) {
                                Icon(Icons.Default.Apps, contentDescription = "Recents", modifier = Modifier.size(16.dp), tint = ElectricCyan)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("RECENTS", style = MaterialTheme.typography.labelMedium, color = DarkTextPrimary)
                            }
                        }
                    }
                }
            }

            // Dedicated REMOTE CONTROL Card (Requirement 5)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("controller_remote_control_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.isControllerTouchActive) DarkSurfaceVariant else DarkSurface
                ),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(
                        if (uiState.isControllerTouchActive) ElectricCyan else DarkSurfaceBorder
                    )
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.TouchApp,
                                contentDescription = null,
                                tint = if (uiState.isControllerTouchActive) ElectricCyan else ControllerModeAccent,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "REMOTE CONTROL",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp),
                                color = DarkTextPrimary
                            )
                        }

                        // Active Indicator
                        if (uiState.isControllerTouchActive) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(ElectricCyan.copy(alpha = 0.15f))
                                    .border(1.dp, ElectricCyan, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "ACTIVE",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = ElectricCyan
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Three Required Status Indicators:
                    // 1. USB: Connected / Disconnected
                    // 2. Screen: Streaming / Standby
                    // 3. Control: Available / Requires Permission / Active
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DarkBackground.copy(alpha = 0.6f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // USB Status
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "USB:", style = MaterialTheme.typography.bodyMedium, color = DarkTextSecondary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isConnected) StatusConnectedGreen else StatusErrorRed)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isConnected) "Connected" else "Disconnected",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isConnected) StatusConnectedGreen else StatusErrorRed
                                )
                            }
                        }

                        // Screen Status
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Screen:", style = MaterialTheme.typography.bodyMedium, color = DarkTextSecondary)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (isStreaming) StatusConnectedGreen else StatusWarningAmber)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isStreaming) "Streaming" else "Standby",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isStreaming) StatusConnectedGreen else StatusWarningAmber
                                )
                            }
                        }

                        // Control Status
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "Control:", style = MaterialTheme.typography.bodyMedium, color = DarkTextSecondary)
                            val controlStatusLabel = when {
                                uiState.isControllerTouchActive -> "Active"
                                uiState.isPeerControlPermissionGranted -> "Available"
                                else -> "Requires Permission"
                            }
                            val controlColor = when {
                                uiState.isControllerTouchActive -> ElectricCyan
                                uiState.isPeerControlPermissionGranted -> StatusConnectedGreen
                                else -> StatusWarningAmber
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(controlColor)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = controlStatusLabel,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = controlColor
                                )
                            }
                        }
                    }

                    // Permission warning banner if permission is missing on Phone 1
                    if (!uiState.isPeerControlPermissionGranted) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(StatusWarningAmber.copy(alpha = 0.12f))
                                .border(1.dp, StatusWarningAmber.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                Text(
                                    text = "Accessibility Permission Needed on Phone 1",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = StatusWarningAmber
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Phone 1 requires its Accessibility Service to be enabled in Android Settings before it can perform touch gestures from Phone 2.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DarkTextSecondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = onRequestAccessibilitySettings,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Open Accessibility Settings", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Buttons: Enable Control / Disable Control / Disconnect
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (!uiState.isControllerTouchActive) {
                            Button(
                                onClick = { onToggleTouchControl(true) },
                                enabled = isConnected,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("enable_control_button"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ElectricCyan,
                                    contentColor = Color.Black
                                )
                            ) {
                                Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Enable Control", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                            }
                        } else {
                            Button(
                                onClick = { onToggleTouchControl(false) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("disable_control_button"),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = StatusWarningAmber,
                                    contentColor = Color.Black
                                )
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Disable Control", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                            }
                        }

                        OutlinedButton(
                            onClick = onStopSession,
                            modifier = Modifier
                                .weight(0.7f)
                                .height(48.dp)
                                .testTag("controller_disconnect_button"),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StatusErrorRed.copy(alpha = 0.7f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusErrorRed)
                        ) {
                            Text("Disconnect", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }

            // Stream Diagnostics Bar (FPS, Dropped Frames, Resolution)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isStreaming) StatusConnectedGreen else StatusWarningAmber)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isStreaming) "HARDWARE DECODER ACTIVE" else "DECODER STANDBY",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (isStreaming) StatusConnectedGreen else DarkTextMuted
                            )
                        }

                        if (uiState.decoderStats.videoWidth > 0) {
                            Text(
                                text = "${uiState.decoderStats.videoWidth}x${uiState.decoderStats.videoHeight}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = ElectricCyan
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem(label = "FPS", value = "${uiState.decoderStats.currentFps}")
                        StatItem(label = "RESOLUTION", value = if (uiState.decoderStats.videoWidth > 0) "${uiState.decoderStats.videoWidth}x${uiState.decoderStats.videoHeight}" else "Standby")
                        StatItem(label = "DROPPED", value = "${uiState.decoderStats.droppedFrames}")
                        StatItem(label = "LATENCY", value = if (uiState.testLatencyMs != null) "${uiState.testLatencyMs}ms" else "<16ms")
                        StatItem(label = "USB", value = if (isConnected) "Connected" else "Waiting")
                    }
                }
            }

            // Stop Viewing Button (Required by Step 3)
            Button(
                onClick = onStopViewing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("stop_viewing_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreaming) StatusErrorRed else DarkSurfaceVariant,
                    contentColor = if (isStreaming) Color.White else DarkTextPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.VisibilityOff,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "STOP VIEWING",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            // Physical USB Link Telemetry
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "PHYSICAL USB LINK",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = DarkTextMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Usb,
                            contentDescription = null,
                            tint = if (isConnected) StatusConnectedGreen else StatusWarningAmber,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isConnected) "USB Link Active (${uiState.connectionState.let { (it as? UsbConnectionState.Connected)?.modeName ?: "Connected" }})" else "Waiting for USB cable connection",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = DarkTextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem(label = "PACKETS RECV", value = "${uiState.transportStats.packetsReceived}")
                        StatItem(label = "BYTES RECV", value = formatBytes(uiState.transportStats.bytesReceived))
                        StatItem(label = "PACKETS SENT", value = "${uiState.transportStats.packetsSent}")
                    }
                }
            }

            // Bidirectional Test Channel Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                border = CardDefaults.outlinedCardBorder().copy(
                    brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "BIDIRECTIONAL USB TEST CHANNEL",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = DarkTextMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    if (uiState.lastReceivedMessage != null) {
                        Text(
                            text = "RECEIVED FROM HOST (PHONE 1)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = StatusConnectedGreen
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkSurfaceVariant)
                                .border(1.dp, StatusConnectedGreen.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                Text(
                                    text = uiState.lastReceivedMessage.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = DarkTextPrimary
                                )
                                if (uiState.testLatencyMs != null) {
                                    Text(
                                        text = "Trip Latency: ${uiState.testLatencyMs}ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = ElectricCyan
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Button(
                        onClick = onSendTestPing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("test_usb_connection_button"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF021724)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.NetworkPing,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "TEST USB CONNECTION",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Stop Session Button
            Button(
                onClick = onStopSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("controller_stop_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = DarkSurfaceVariant,
                    contentColor = DarkTextPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = null,
                    tint = DarkTextMuted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Stop Controller Session")
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
