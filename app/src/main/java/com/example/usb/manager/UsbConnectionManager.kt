package com.example.usb.manager

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.usb.model.UsbConnectionState
import com.example.usb.model.UsbDeviceInfo
import com.example.usb.model.UsbRole
import com.example.usb.transport.UsbAccessoryTransport
import com.example.usb.transport.UsbBulkTransport
import com.example.usb.transport.UsbSocketTransport
import com.example.usb.transport.UsbTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Manages physical USB connection lifecycle, detection, permissions,
 * and data transport creation for both USB Host and USB Accessory modes.
 */
class UsbConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) {
    companion object {
        private const val TAG = "UsbConnectionManager"
        const val ACTION_USB_PERMISSION = "com.example.usb.USB_PERMISSION"

        // Google Android Accessory Protocol (AOA) Constants
        private const val USB_ACCESSORY_VENDOR_ID = 0x18D1
        private const val USB_ACCESSORY_PRODUCT_ID = 0x2D00
        private const val USB_ACCESSORY_ADB_PRODUCT_ID = 0x2D01

        private const val AOA_GET_PROTOCOL = 51
        private const val AOA_SEND_STRING = 52
        private const val AOA_START = 53

        fun isSamsung(): Boolean = Build.MANUFACTURER.contains("samsung", ignoreCase = true)
        fun isXiaomiOrPoco(): Boolean = Build.MANUFACTURER.contains("xiaomi", ignoreCase = true) ||
                Build.MANUFACTURER.contains("poco", ignoreCase = true) ||
                Build.MANUFACTURER.contains("redmi", ignoreCase = true)

        fun openTetheringSettings(ctx: Context) {
            try {
                val intent = Intent("android.settings.TETHER_SETTINGS").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                ctx.startActivity(intent)
            } catch (e: Exception) {
                try {
                    val fallback = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(fallback)
                } catch (_: Exception) {}
            }
        }

        fun openDeveloperSettings(ctx: Context) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                ctx.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _connectionState = MutableStateFlow<UsbConnectionState>(
        UsbConnectionState.Disconnected("No peer USB device connected. Connect phones via USB-C to USB-C cable.")
    )
    val connectionState: StateFlow<UsbConnectionState> = _connectionState.asStateFlow()

    private val _detectedDevices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val detectedDevices: StateFlow<List<UsbDeviceInfo>> = _detectedDevices.asStateFlow()

    private var activeTransport: UsbTransport? = null
    val currentTransport: UsbTransport?
        get() = activeTransport

    val hasUsbHostFeature: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        val accessory: UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
                        }

                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) {
                            val name = device?.productName ?: accessory?.description ?: "USB Device"
                            Log.d(TAG, "USB Permission granted for: $name")
                            refreshDevices()
                            _connectionState.update {
                                UsbConnectionState.UsbDetected(
                                    deviceName = name,
                                    isAccessory = accessory != null,
                                    details = "Permission granted. Ready to establish USB link."
                                )
                            }
                        } else {
                            Log.w(TAG, "USB Permission denied")
                            _connectionState.update {
                                UsbConnectionState.ConnectionError(
                                    errorCode = "PERM_DENIED",
                                    userMessage = "USB Permission was denied. Please grant permission in the Android dialog to connect.",
                                    isRecoverable = true
                                )
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.d(TAG, "USB device attached")
                    refreshDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.d(TAG, "USB device detached")
                    disconnect()
                    refreshDevices()
                }
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    Log.d(TAG, "USB accessory attached")
                    refreshDevices()
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    Log.d(TAG, "USB accessory detached")
                    disconnect()
                    refreshDevices()
                }
            }
        }
    }

    private var isReceiverRegistered = false

    fun register() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
            isReceiverRegistered = true
            refreshDevices()
        }
    }

    fun unregister() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver", e)
            }
            isReceiverRegistered = false
        }
        disconnect()
    }

    fun refreshDevices() {
        try {
            val deviceList = mutableListOf<UsbDeviceInfo>()

            // 1. Check USB Devices (Host Mode)
            val usbDevices = usbManager.deviceList
            for (dev in usbDevices.values) {
                deviceList.add(createDeviceInfo(dev))
            }

            // 2. Check USB Accessories (Accessory Mode)
            val accessories = usbManager.accessoryList
            if (accessories != null) {
                for (acc in accessories) {
                    deviceList.add(createAccessoryInfo(acc))
                }
            }

            _detectedDevices.value = deviceList

            if (deviceList.isEmpty()) {
                if (_connectionState.value !is UsbConnectionState.Connected) {
                    _connectionState.value = UsbConnectionState.Disconnected(
                        "No peer USB device detected. Connect phones via USB-C to USB-C cable or OTG adapter."
                    )
                }
            } else {
                val current = _connectionState.value
                if (current is UsbConnectionState.Disconnected) {
                    val first = deviceList.first()
                    _connectionState.value = if (!first.hasPermission) {
                        UsbConnectionState.PermissionRequired(
                            deviceName = first.displayName,
                            isAccessory = first.isAccessory
                        )
                    } else {
                        UsbConnectionState.UsbDetected(
                            deviceName = first.displayName,
                            isAccessory = first.isAccessory,
                            details = "Hardware ready. Tap 'TEST USB CONNECTION' or 'Connect' to link."
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh USB devices", e)
        }
    }

    fun requestDevicePermission(deviceInfo: UsbDeviceInfo) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            },
            flags
        )

        if (deviceInfo.isAccessory) {
            val accessory = usbManager.accessoryList?.find { it.hashCode() == deviceInfo.deviceId }
            if (accessory == null) {
                _connectionState.value = UsbConnectionState.ConnectionError(
                    "ACC_NOT_FOUND",
                    "USB Accessory is no longer attached",
                    false
                )
                return
            }
            _connectionState.value = UsbConnectionState.PermissionRequired(deviceInfo.displayName, true)
            usbManager.requestPermission(accessory, permissionIntent)
        } else {
            val device = usbManager.deviceList.values.find { it.deviceId == deviceInfo.deviceId }
            if (device == null) {
                _connectionState.value = UsbConnectionState.ConnectionError(
                    "DEV_NOT_FOUND",
                    "Device is no longer attached",
                    false
                )
                return
            }
            _connectionState.value = UsbConnectionState.PermissionRequired(deviceInfo.displayName, false)
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    /**
     * Connects to a detected USB hardware endpoint (Device or Accessory).
     */
    suspend fun connectDevice(deviceInfo: UsbDeviceInfo, role: UsbRole): Result<UsbTransport> = withContext(Dispatchers.IO) {
        if (deviceInfo.isAccessory) {
            return@withContext connectAccessory(deviceInfo, role)
        }

        val device = usbManager.deviceList.values.find { it.deviceId == deviceInfo.deviceId }
            ?: return@withContext Result.failure(IllegalStateException("Device ${deviceInfo.displayName} not found on USB bus"))

        if (!usbManager.hasPermission(device)) {
            withContext(Dispatchers.Main) {
                requestDevicePermission(deviceInfo)
            }
            return@withContext Result.failure(SecurityException("USB permission required for ${deviceInfo.displayName}"))
        }

        _connectionState.value = UsbConnectionState.Connecting(role, "Opening USB Device Connection…")

        try {
            val connection = usbManager.openDevice(device)
                ?: return@withContext Result.failure(IllegalStateException("Failed to open UsbDeviceConnection. Check cable connection."))

            // If the connected device is a standard Android phone not yet in accessory mode,
            // check if we can switch it into AOA accessory mode.
            if (device.vendorId != USB_ACCESSORY_VENDOR_ID && isAndroidDeviceCandidate(device)) {
                val aoaAttempt = tryInitiateAoaMode(connection)
                if (aoaAttempt) {
                    connection.close()
                    _connectionState.value = UsbConnectionState.Connecting(role, "Switching peer phone to USB Accessory mode…")
                    return@withContext Result.failure(IllegalStateException("Peer phone switched to Accessory mode; re-enumerating USB link…"))
                }
            }

            // Find bulk transfer interface
            var selectedInterface: UsbInterface? = null
            var endpointIn: UsbEndpoint? = null
            var endpointOut: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var foundIn: UsbEndpoint? = null
                var foundOut: UsbEndpoint? = null

                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN && foundIn == null) {
                            foundIn = ep
                        } else if (ep.direction == UsbConstants.USB_DIR_OUT && foundOut == null) {
                            foundOut = ep
                        }
                    }
                }

                if (foundIn != null || foundOut != null) {
                    selectedInterface = iface
                    endpointIn = foundIn
                    endpointOut = foundOut
                    break
                }
            }

            // Fallback to interface 0 endpoints if bulk not tagged
            if (selectedInterface == null && device.interfaceCount > 0) {
                selectedInterface = device.getInterface(0)
                for (j in 0 until selectedInterface.endpointCount) {
                    val ep = selectedInterface.getEndpoint(j)
                    if (ep.direction == UsbConstants.USB_DIR_IN && endpointIn == null) endpointIn = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT && endpointOut == null) endpointOut = ep
                }
            }

            if (selectedInterface == null) {
                connection.close()
                return@withContext Result.failure(IllegalStateException("No compatible USB interface found on device"))
            }

            if (!connection.claimInterface(selectedInterface, true)) {
                connection.close()
                return@withContext Result.failure(IllegalStateException("Could not claim USB interface #${selectedInterface.id}"))
            }

            val transport = UsbBulkTransport(
                connection = connection,
                usbInterface = selectedInterface,
                endpointIn = endpointIn,
                endpointOut = endpointOut
            )
            transport.onDisconnect = {
                scope.launch {
                    disconnect()
                    refreshDevices()
                }
            }

            activeTransport?.close()
            activeTransport = transport

            val epDetails = "IN: ${endpointIn?.address ?: "none"}, OUT: ${endpointOut?.address ?: "none"}"
            _connectionState.value = UsbConnectionState.Connected(
                role = role,
                modeName = "USB Host Mode (Bulk Endpoints)",
                peerInfo = "${deviceInfo.displayName} ($epDetails)",
                speed = "High-Speed (480 Mbps)"
            )

            Result.success(transport)
        } catch (e: Exception) {
            Log.e(TAG, "Connection failure", e)
            _connectionState.value = UsbConnectionState.ConnectionError(
                errorCode = "CONN_FAILED",
                userMessage = "Could not establish USB connection: ${e.localizedMessage ?: "Unknown hardware error"}"
            )
            Result.failure(e)
        }
    }

    private fun connectAccessory(deviceInfo: UsbDeviceInfo, role: UsbRole): Result<UsbTransport> {
        val accessory = usbManager.accessoryList?.find { it.hashCode() == deviceInfo.deviceId }
            ?: return Result.failure(IllegalStateException("USB Accessory not found"))

        if (!usbManager.hasPermission(accessory)) {
            requestDevicePermission(deviceInfo)
            return Result.failure(SecurityException("USB permission required for accessory"))
        }

        _connectionState.value = UsbConnectionState.Connecting(role, "Opening USB Accessory stream…")

        try {
            val pfd: ParcelFileDescriptor = usbManager.openAccessory(accessory)
                ?: return Result.failure(IllegalStateException("Failed to open USB Accessory ParcelFileDescriptor"))

            val transport = UsbAccessoryTransport(pfd)
            transport.onDisconnect = {
                scope.launch {
                    disconnect()
                    refreshDevices()
                }
            }
            activeTransport?.close()
            activeTransport = transport

            _connectionState.value = UsbConnectionState.Connected(
                role = role,
                modeName = "USB Accessory Mode (AOA 2.0)",
                peerInfo = accessory.description ?: "Android USB Accessory",
                speed = "High-Speed (480 Mbps)"
            )

            return Result.success(transport)
        } catch (e: Exception) {
            Log.e(TAG, "Accessory connection failure", e)
            _connectionState.value = UsbConnectionState.ConnectionError(
                errorCode = "ACC_CONN_FAILED",
                userMessage = "Failed to connect USB accessory: ${e.localizedMessage}"
            )
            return Result.failure(e)
        }
    }

    /**
     * Checks if the device could be an Android phone to attempt AOA handoff.
     */
    private fun isAndroidDeviceCandidate(device: UsbDevice): Boolean {
        // Standard Android VID candidates or USB device class 0 (per-interface)
        return device.deviceClass == 0 || device.deviceClass == UsbConstants.USB_CLASS_PER_INTERFACE
    }

    /**
     * Attempts standard Android Open Accessory (AOA) initiation control transfers.
     */
    private fun tryInitiateAoaMode(connection: UsbDeviceConnection): Boolean {
        try {
            val protocolBuffer = ByteArray(2)
            val len = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR,
                AOA_GET_PROTOCOL,
                0,
                0,
                protocolBuffer,
                protocolBuffer.size,
                1000
            )

            if (len < 2) return false
            val protocolVersion = (protocolBuffer[1].toInt() shl 8) or (protocolBuffer[0].toInt() and 0xFF)
            if (protocolVersion < 1) return false

            Log.d(TAG, "Peer device supports AOA protocol v$protocolVersion! Sending accessory identification...")

            fun sendAoaString(index: Int, str: String) {
                val bytes = str.toByteArray(Charsets.UTF_8)
                connection.controlTransfer(
                    UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
                    AOA_SEND_STRING,
                    0,
                    index,
                    bytes,
                    bytes.size,
                    1000
                )
            }

            sendAoaString(0, "UsbScreenLink")             // Manufacturer
            sendAoaString(1, "ScreenLinkHost")            // Model
            sendAoaString(2, "USB Screen Link Data Bus")  // Description
            sendAoaString(3, "1.0")                       // Version
            sendAoaString(4, "https://github.com")        // URI
            sendAoaString(5, "USBSCREENLINK0001")         // Serial

            // Trigger START ACCESSORY
            connection.controlTransfer(
                UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
                AOA_START,
                0,
                0,
                null,
                0,
                1000
            )
            Log.d(TAG, "Sent AOA_START command to peer device")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "AOA negotiation skipped/failed: ${e.message}")
            return false
        }
    }

    /**
     * Connects via high-speed Direct Cable Network / USB Tethering Socket.
     * Host creates server, Controller auto-discovers and connects.
     */
    suspend fun connectViaSocket(role: UsbRole, targetHost: String? = null): Result<UsbTransport> = withContext(Dispatchers.IO) {
        _connectionState.value = UsbConnectionState.Connecting(
            role,
            if (role == UsbRole.HOST) "Starting USB Screen Host at ${UsbSocketTransport.getBestLocalIp() ?: "0.0.0.0"}:8889…" else "Connecting to Host over USB cable link…"
        )

        val result: Result<UsbTransport> = if (role == UsbRole.HOST) {
            UsbSocketTransport.createServerTransport(
                onListening = { ip: String ->
                    _connectionState.value = UsbConnectionState.Connecting(role, "Host ready at $ip:8889. Waiting for Controller…")
                }
            ).map { it as UsbTransport }
        } else {
            UsbSocketTransport.createClientTransport(targetHost = targetHost).map { it as UsbTransport }
        }

        if (result.isSuccess) {
            val transport = result.getOrThrow()
            transport.onDisconnect = {
                scope.launch {
                    disconnect()
                    refreshDevices()
                }
            }
            activeTransport?.close()
            activeTransport = transport

            _connectionState.value = UsbConnectionState.Connected(
                role = role,
                modeName = transport.modeName,
                peerInfo = if (role == UsbRole.HOST) "Connected Controller" else "Connected Host (${targetHost ?: "Auto-Discovered"})",
                speed = "High-Speed Hardware Link (Ultra-Low Latency)"
            )
        } else {
            val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Could not establish USB cable link"
            _connectionState.value = UsbConnectionState.ConnectionError(
                errorCode = "SOCKET_TIMEOUT",
                userMessage = "$errorMsg. Tip: Please enable USB Tethering or verify cable connection.",
                isRecoverable = true
            )
        }

        result
    }

    fun disconnect() {
        activeTransport?.close()
        activeTransport = null
        _connectionState.value = UsbConnectionState.Disconnected("Disconnected from peer USB device")
    }

    private fun createDeviceInfo(device: UsbDevice): UsbDeviceInfo {
        return UsbDeviceInfo(
            deviceName = device.deviceName,
            deviceId = device.deviceId,
            vendorId = device.vendorId,
            productId = device.productId,
            deviceClass = device.deviceClass,
            interfaceCount = device.interfaceCount,
            manufacturerName = device.manufacturerName,
            productName = device.productName,
            hasPermission = usbManager.hasPermission(device),
            isAccessory = false
        )
    }

    private fun createAccessoryInfo(accessory: UsbAccessory): UsbDeviceInfo {
        return UsbDeviceInfo(
            deviceName = accessory.description ?: "Android Accessory",
            deviceId = accessory.hashCode(),
            vendorId = USB_ACCESSORY_VENDOR_ID,
            productId = USB_ACCESSORY_PRODUCT_ID,
            deviceClass = 0,
            interfaceCount = 1,
            manufacturerName = accessory.manufacturer,
            productName = accessory.model,
            hasPermission = usbManager.hasPermission(accessory),
            isAccessory = true
        )
    }
}
