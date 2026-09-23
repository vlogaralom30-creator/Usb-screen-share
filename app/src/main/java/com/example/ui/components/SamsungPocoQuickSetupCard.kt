package com.example.ui.components

import android.os.Build
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeveloperMode
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
import com.example.ui.theme.StatusConnectedGreen
import com.example.ui.theme.StatusWarningAmber
import com.example.usb.manager.UsbConnectionManager

@Composable
fun SamsungPocoQuickSetupCard(
    isConnected: Boolean,
    localIp: String?,
    onOpenTethering: () -> Unit,
    onInstantConnect: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSamsung = UsbConnectionManager.isSamsung()
    val isPoco = UsbConnectionManager.isXiaomiOrPoco()
    val brandName = Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("samsung_poco_quick_setup_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (isConnected) StatusConnectedGreen.copy(alpha = 0.6f) else ElectricCyan.copy(alpha = 0.5f)
            )
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Brand & High Speed Cable Link Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isConnected) StatusConnectedGreen.copy(alpha = 0.2f) else ElectricCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Bolt,
                            contentDescription = null,
                            tint = if (isConnected) StatusConnectedGreen else ElectricCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "EASY CABLE LINK SETUP",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            ),
                            color = ElectricCyan
                        )
                        Text(
                            text = "Samsung ⇄ Poco / Universal",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = DarkTextPrimary
                        )
                    }
                }

                // Brand Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkSurfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isSamsung) "Samsung One UI" else if (isPoco) "POCO / HyperOS" else brandName,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isSamsung) ElectricBlue else if (isPoco) StatusWarningAmber else DarkTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Step 1: Connect Cable
            StepRow(
                stepNum = "1",
                title = "Connect Type-C to Type-C Cable",
                desc = "Plug one end into Samsung and the other into Poco."
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Step 2: Turn on USB Tethering
            StepRow(
                stepNum = "2",
                title = "Turn ON USB Tethering (1-Tap)",
                desc = "Turns Type-C cable into a 0-lag 480Mbps hardware data highway."
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Step 3: Tap Connect
            StepRow(
                stepNum = "3",
                title = "Tap 'Instant Auto-Connect'",
                desc = "Both phones will automatically find each other and start mirroring."
            )

            if (isPoco) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(StatusWarningAmber.copy(alpha = 0.1f))
                        .padding(8.dp)
                ) {
                    Text(
                        text = "💡 Poco / Xiaomi Tip: If the cable is not recognized, go to Settings → Additional Settings → OTG and turn it ON.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusWarningAmber
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenTethering,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("open_tethering_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant)
                ) {
                    Icon(
                        imageVector = Icons.Default.WifiTethering,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "USB Tethering",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = DarkTextPrimary
                    )
                }

                Button(
                    onClick = onInstantConnect,
                    modifier = Modifier
                        .weight(1.3f)
                        .testTag("instant_auto_connect_button"),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isConnected) StatusConnectedGreen else ElectricCyan,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isConnected) "Linked (Active)" else "Auto-Connect Now",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }

            if (localIp != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "⚡ Local Link IP: $localIp:8889",
                    style = MaterialTheme.typography.labelSmall,
                    color = ElectricCyan
                )
            }
        }
    }
}

@Composable
private fun StepRow(stepNum: String, title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(ElectricCyan.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNum,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = ElectricCyan,
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = DarkTextPrimary
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.labelSmall,
                color = DarkTextSecondary
            )
        }
    }
}
