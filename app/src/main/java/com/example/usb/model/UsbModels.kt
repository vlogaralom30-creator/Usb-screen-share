package com.example.usb.model

/**
 * Operating mode of the app on the current device.
 */
enum class UsbRole {
    NONE,
    HOST,       // Phone 1: Encodes and streams screen to Controller, receives control events
    CONTROLLER  // Phone 2: Displays remote screen feed, captures touches and sends to Host
}

/**
 * Connection states for reactive UI feedback and lifecycle management,
 * matching the 6 required stages:
 * - Disconnected
 * - USB detected
 * - Permission required
 * - Connecting
 * - Connected
 * - Connection error
 */
sealed interface UsbConnectionState {
    data class Disconnected(
        val reason: String = "No peer USB device connected"
    ) : UsbConnectionState

    data class UsbDetected(
        val deviceName: String,
        val isAccessory: Boolean = false,
        val details: String = "USB peer hardware attached"
    ) : UsbConnectionState

    data class PermissionRequired(
        val deviceName: String,
        val isAccessory: Boolean = false
    ) : UsbConnectionState

    data class Connecting(
        val role: UsbRole,
        val detail: String = "Configuring USB endpoints…"
    ) : UsbConnectionState

    data class Connected(
        val role: UsbRole,
        val modeName: String, // e.g. "USB Host Mode (Bulk Endpoints)" or "USB Accessory Mode (AOA)"
        val peerInfo: String,
        val speed: String = "High-Speed (480 Mbps)"
    ) : UsbConnectionState

    data class ConnectionError(
        val errorCode: String,
        val userMessage: String,
        val isRecoverable: Boolean = true
    ) : UsbConnectionState
}

/**
 * High-level message object for the bidirectional USB test protocol and status display.
 */
data class UsbMessage(
    val senderRole: UsbRole,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val sequenceNumber: Long = 0
)

/**
 * Diagnostic info for discovered USB devices.
 */
data class UsbDeviceInfo(
    val deviceName: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val interfaceCount: Int,
    val manufacturerName: String?,
    val productName: String?,
    val hasPermission: Boolean,
    val isAccessory: Boolean = false
) {
    val displayName: String
        get() = when {
            !productName.isNullOrBlank() -> productName
            !manufacturerName.isNullOrBlank() -> "$manufacturerName (VID:${vendorId.toString(16)})"
            isAccessory -> "Android USB Accessory"
            else -> "USB Device [${vendorId.toString(16).padStart(4, '0')}:${productId.toString(16).padStart(4, '0')}]"
        }

    val hexVendorId: String
        get() = "0x" + vendorId.toString(16).uppercase().padStart(4, '0')

    val hexProductId: String
        get() = "0x" + productId.toString(16).uppercase().padStart(4, '0')
}
