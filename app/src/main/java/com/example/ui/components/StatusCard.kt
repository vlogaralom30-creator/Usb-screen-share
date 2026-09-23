package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceBorder
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.DarkTextMuted
import com.example.ui.theme.DarkTextPrimary
import com.example.ui.theme.DarkTextSecondary
import com.example.ui.theme.ElectricBlue
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.HostModeAccent
import com.example.ui.theme.ControllerModeAccent
import com.example.ui.theme.StatusConnectedGreen
import com.example.ui.theme.StatusErrorRed
import com.example.ui.theme.StatusPendingCyan
import com.example.ui.theme.StatusWarningAmber
import com.example.usb.model.UsbConnectionState
import com.example.usb.model.UsbDeviceInfo
import com.example.usb.model.UsbMessage
import com.example.usb.model.UsbRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun StatusCard(
    connectionState: UsbConnectionState,
    detectedDevices: List<UsbDeviceInfo>,
    activeRole: UsbRole,
    lastReceivedMessage: UsbMessage?,
    lastSentMessage: UsbMessage?,
    testLatencyMs: Long?,
    isTestInProgress: Boolean,
    isScanning: Boolean,
    onScanClick: () -> Unit,
    onRequestPermission: (UsbDeviceInfo) -> Unit,
    onConnectDevice: (UsbDeviceInfo) -> Unit,
    onTestConnection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("usb_status_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(DarkSurfaceBorder)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header Row: Status Indicator, Role Badge, and Scan Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (statusColor, statusText) = when (connectionState) {
                        is UsbConnectionState.Connected -> StatusConnectedGreen to "LINK ACTIVE"
                        is UsbConnectionState.Connecting -> StatusPendingCyan to "CONNECTING"
                        is UsbConnectionState.UsbDetected -> StatusPendingCyan to "USB DETECTED"
                        is UsbConnectionState.PermissionRequired -> StatusWarningAmber to "PERMISSION REQUIRED"
                        is UsbConnectionState.ConnectionError -> StatusErrorRed to "ERROR"
                        is UsbConnectionState.Disconnected -> DarkTextMuted to "DISCONNECTED"
                    }

                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        ),
                        color = statusColor
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Active Device Role Badge
                    val (roleText, roleBg, roleColor) = when (activeRole) {
                        UsbRole.HOST -> Triple("HOST (PHONE 1)", HostModeAccent.copy(alpha = 0.2f), HostModeAccent)
                        UsbRole.CONTROLLER -> Triple("CONTROLLER (PHONE 2)", ControllerModeAccent.copy(alpha = 0.2f), ControllerModeAccent)
                        UsbRole.NONE -> Triple("STANDBY", DarkSurfaceVariant, DarkTextMuted)
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(roleBg)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = roleText,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = roleColor
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = onScanClick,
                        modifier = Modifier.testTag("scan_usb_button")
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = ElectricCyan,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Scan USB Devices",
                                tint = ElectricCyan
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // State specific description
            when (connectionState) {
                is UsbConnectionState.Connected -> {
                    Text(
                        text = connectionState.modeName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkTextPrimary
                    )
                    Text(
                        text = "Peer: ${connectionState.peerInfo} • ${connectionState.speed}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = DarkTextSecondary
                    )
                }
                is UsbConnectionState.Connecting -> {
                    Text(
                        text = "Establishing Direct USB Connection…",
                        style = MaterialTheme.typography.titleMedium,
                        color = DarkTextPrimary
                    )
                    Text(
                        text = connectionState.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = DarkTextSecondary
                    )
                }
                is UsbConnectionState.UsbDetected -> {
                    Text(
                        text = connectionState.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        color = DarkTextPrimary
                    )
                    Text(
                        text = connectionState.details,
                        style = MaterialTheme.typography.bodySmall,
                        color = DarkTextSecondary
                    )
                }
                is UsbConnectionState.PermissionRequired -> {
                    Text(
                        text = "USB Permission Required",
                        style = MaterialTheme.typography.titleMedium,
                        color = StatusWarningAmber
                    )
                    Text(
                        text = "Tap 'Grant' or accept the system USB prompt for ${connectionState.deviceName}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = DarkTextSecondary
                    )
                }
                is UsbConnectionState.ConnectionError -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = StatusErrorRed,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "USB Communication Error [${connectionState.errorCode}]",
                                style = MaterialTheme.typography.titleSmall,
                                color = StatusErrorRed
                            )
                            Text(
                                text = connectionState.userMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = DarkTextSecondary
                            )
                        }
                    }
                }
                is UsbConnectionState.Disconnected -> {
                    Text(
                        text = "No Peer Device Connected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = DarkTextPrimary
                    )
                    Text(
                        text = connectionState.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = DarkTextSecondary
                    )
                }
            }

            // TEST USB CONNECTION BUTTON (Always visible & interactive)
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = onTestConnection,
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
                if (isTestInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color(0xFF021724),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TRANSMITTING USB PACKET…",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.NetworkPing,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "TEST USB CONNECTION",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            // Last Received Message Box
            AnimatedVisibility(visible = lastReceivedMessage != null || lastSentMessage != null) {
                Column(modifier = Modifier.padding(top = 14.dp)) {
                    if (lastReceivedMessage != null) {
                        Text(
                            text = "LAST RECEIVED MESSAGE (FROM PEER)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = StatusConnectedGreen
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkSurfaceVariant)
                                .border(1.dp, StatusConnectedGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "From: ${lastReceivedMessage.senderRole.name} (Msg #${lastReceivedMessage.sequenceNumber})",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = StatusConnectedGreen
                                    )
                                    val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                                        .format(Date(lastReceivedMessage.timestampMs))
                                    Text(
                                        text = timeStr,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = DarkTextMuted
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = lastReceivedMessage.text,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = DarkTextPrimary
                                )
                                if (testLatencyMs != null) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Trip latency: ${testLatencyMs}ms",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = ElectricCyan
                                    )
                                }
                            }
                        }
                    }

                    if (lastSentMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "LAST TRANSMITTED MESSAGE",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = DarkTextMuted
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(DarkSurfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Sent: \"${lastSentMessage.text}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = DarkTextSecondary
                            )
                        }
                    }
                }
            }

            // Attached devices sub-list
            AnimatedVisibility(visible = detectedDevices.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        text = "DISCOVERED USB HARDWARE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = DarkTextMuted
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    detectedDevices.forEach { device ->
                        DeviceItem(
                            device = device,
                            onRequestPermission = { onRequestPermission(device) },
                            onConnect = { onConnectDevice(device) }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceItem(
    device: UsbDeviceInfo,
    onRequestPermission: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DarkSurfaceVariant)
            .border(1.dp, DarkSurfaceBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Usb,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = device.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = DarkTextPrimary,
                        maxLines = 1
                    )
                    Text(
                        text = if (device.isAccessory) "Android USB Accessory" else "VID:${device.hexVendorId} PID:${device.hexProductId} • ${device.interfaceCount} ifaces",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = DarkTextSecondary
                    )
                }
            }

            if (!device.hasPermission) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StatusWarningAmber,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("grant_perm_${device.deviceId}")
                ) {
                    Text("Grant", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                OutlinedButton(
                    onClick = onConnect,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ElectricCyan),
                    modifier = Modifier.testTag("connect_dev_${device.deviceId}")
                ) {
                    Text("Connect", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
