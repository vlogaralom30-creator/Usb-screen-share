package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.DisplayMetrics
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.model.SettingsModel
import com.example.service.RemoteAccessibilityControlService
import com.example.service.ScreenCaptureService
import com.example.stream.decoder.DecoderStats
import com.example.stream.decoder.HardwareVideoDecoder
import com.example.stream.encoder.EncoderStats
import com.example.usb.manager.UsbConnectionManager
import com.example.usb.model.ControlCommandPayload
import com.example.usb.model.ControlStatusPayload
import com.example.usb.model.RemoteInputEvent
import com.example.usb.model.StreamConfig
import com.example.usb.model.UsbConnectionState
import com.example.usb.model.UsbDeviceInfo
import com.example.usb.model.UsbMessage
import com.example.usb.model.UsbPacket
import com.example.usb.model.UsbProtocol
import com.example.usb.model.UsbRole
import com.example.usb.model.VideoFrameData
import com.example.usb.model.parseUsbMessage
import com.example.usb.model.toPacket
import com.example.usb.transport.TransportStats
import com.example.usb.transport.UsbTransport
import kotlin.math.hypot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

enum class AppScreen {
    HOME,
    HOST_SESSION,
    CONTROLLER_SESSION,
    SETTINGS,
    DIAGNOSTICS
}

data class UsbUiState(
    val currentScreen: AppScreen = AppScreen.HOME,
    val activeRole: UsbRole = UsbRole.NONE,
    val isSessionActive: Boolean = false,
    val connectionState: UsbConnectionState = UsbConnectionState.Disconnected(),
    val detectedDevices: List<UsbDeviceInfo> = emptyList(),
    val transportStats: TransportStats = TransportStats(),
    val settings: SettingsModel = SettingsModel(),
    val statusBanner: String? = null,
    val isScanning: Boolean = false,
    val lastReceivedMessage: UsbMessage? = null,
    val lastSentMessage: UsbMessage? = null,
    val testMessageHistory: List<UsbMessage> = emptyList(),
    val testLatencyMs: Long? = null,
    val isTestInProgress: Boolean = false,

    // Step 3 Live Video Streaming State
    val isScreenSharing: Boolean = false,
    val isStreamingActive: Boolean = false,
    val encoderStats: EncoderStats = EncoderStats(),
    val decoderStats: DecoderStats = DecoderStats(),

    // Step 4 Remote Control State
    val isAccessibilityServiceActive: Boolean = false,
    val isRemoteControlEnabledOnHost: Boolean = true,
    val isControllerTouchActive: Boolean = false,
    val isPeerControlPermissionGranted: Boolean = false,
    val isPeerControlActive: Boolean = false,
    val remoteControlStatusText: String = "Standby",
    val lastDispatchedAction: String? = null
)

class UsbSessionViewModel(application: Application) : AndroidViewModel(application) {

    val usbManager = UsbConnectionManager(application, viewModelScope)
    val videoDecoder = HardwareVideoDecoder()

    private val _uiState = MutableStateFlow(UsbUiState())
    val uiState: StateFlow<UsbUiState> = _uiState.asStateFlow()

    private var packetReceiverJob: Job? = null
    private val messageSeqCounter = AtomicLong(1)
    private var lastPingSentTime: Long = 0

    init {
        usbManager.register()

        // Sync connection state
        viewModelScope.launch {
            usbManager.connectionState.collect { connState ->
                _uiState.update { it.copy(connectionState = connState) }
                if (connState is UsbConnectionState.Connected) {
                    val transport = usbManager.currentTransport
                    if (transport != null) {
                        attachTransport(transport)
                    }
                } else if (connState is UsbConnectionState.Disconnected) {
                    // Auto-stop sharing if wire unplugged
                    if (_uiState.value.isScreenSharing) {
                        stopScreenSharing()
                    }
                    videoDecoder.stop()
                    _uiState.update {
                        it.copy(
                            isStreamingActive = false,
                            isControllerTouchActive = false,
                            isPeerControlActive = false
                        )
                    }
                }
            }
        }

        // Sync detected devices
        viewModelScope.launch {
            usbManager.detectedDevices.collect { devices ->
                _uiState.update { it.copy(detectedDevices = devices) }
            }
        }

        // Sync ScreenCaptureService running state
        viewModelScope.launch {
            ScreenCaptureService.isServiceRunning.collect { running ->
                _uiState.update { it.copy(isScreenSharing = running) }
            }
        }

        // Sync RemoteAccessibilityControlService running state (Host Phone 1)
        viewModelScope.launch {
            RemoteAccessibilityControlService.isServiceRunning.collect { running ->
                _uiState.update { it.copy(isAccessibilityServiceActive = running) }
                broadcastControlStatus()
            }
        }

        // Sync decoder stats
        viewModelScope.launch {
            videoDecoder.stats.collect { decStats ->
                _uiState.update { it.copy(decoderStats = decStats) }
            }
        }

        // Periodically refresh transport stats
        viewModelScope.launch {
            while (true) {
                val stats = usbManager.currentTransport?.stats?.value ?: TransportStats()
                _uiState.update { it.copy(transportStats = stats) }
                delay(1000)
            }
        }
    }

    private fun attachTransport(transport: UsbTransport) {
        packetReceiverJob?.cancel()
        packetReceiverJob = viewModelScope.launch {
            transport.receivePackets().collect { packet ->
                handleIncomingPacket(packet, transport)
            }
        }
    }

    private suspend fun handleIncomingPacket(packet: UsbPacket, transport: UsbTransport) {
        when (packet.type) {
            UsbProtocol.TYPE_STREAM_START -> {
                _uiState.update {
                    it.copy(
                        isStreamingActive = true,
                        statusBanner = "Live screen stream incoming from Phone 1"
                    )
                }
            }

            UsbProtocol.TYPE_STREAM_CONFIG -> {
                val config = StreamConfig.fromByteArray(packet.payload)
                if (config != null) {
                    videoDecoder.configureDecoder(config)
                    _uiState.update {
                        it.copy(
                            isStreamingActive = true,
                            statusBanner = "Stream configured: ${config.width}x${config.height} @ ${config.fps}fps"
                        )
                    }
                }
            }

            UsbProtocol.TYPE_VIDEO_FRAME -> {
                val frame = VideoFrameData.fromPacket(packet)
                if (frame != null) {
                    videoDecoder.ingestFrame(frame)
                    if (!_uiState.value.isStreamingActive) {
                        _uiState.update { it.copy(isStreamingActive = true) }
                    }
                }
            }

            UsbProtocol.TYPE_STREAM_STOP -> {
                videoDecoder.stop()
                _uiState.update {
                    it.copy(
                        isStreamingActive = false,
                        statusBanner = "Screen stream stopped by Phone 1"
                    )
                }
            }

            UsbProtocol.TYPE_TEST_MESSAGE -> {
                val msg = parseUsbMessage(packet.payload)
                if (msg != null) {
                    val latency = System.currentTimeMillis() - msg.timestampMs
                    _uiState.update {
                        it.copy(
                            lastReceivedMessage = msg,
                            testMessageHistory = (listOf(msg) + it.testMessageHistory).take(10),
                            testLatencyMs = if (latency >= 0) latency else null,
                            statusBanner = "Received: \"${msg.text}\" from ${msg.senderRole}"
                        )
                    }

                    // Acknowledge receipt back over USB
                    val ack = UsbMessage(
                        senderRole = if (_uiState.value.activeRole != UsbRole.NONE) _uiState.value.activeRole else UsbRole.CONTROLLER,
                        text = "ACK for Msg #${msg.sequenceNumber}",
                        sequenceNumber = messageSeqCounter.getAndIncrement()
                    )
                    transport.sendPacket(ack.toPacket())
                }
            }

            UsbProtocol.TYPE_PING -> {
                val pongPacket = UsbPacket(
                    type = UsbProtocol.TYPE_PONG,
                    sequenceId = packet.sequenceId,
                    payload = "PONG".toByteArray()
                )
                transport.sendPacket(pongPacket)
            }

            UsbProtocol.TYPE_PONG -> {
                val rtt = System.currentTimeMillis() - lastPingSentTime
                _uiState.update {
                    it.copy(
                        testLatencyMs = rtt,
                        statusBanner = "USB Ping round-trip: ${rtt}ms",
                        isTestInProgress = false
                    )
                }
            }

            UsbProtocol.TYPE_INPUT_EVENT -> {
                val event = RemoteInputEvent.fromByteArray(packet.payload)
                if (event != null && _uiState.value.isRemoteControlEnabledOnHost) {
                    val metrics = getApplication<Application>().resources.displayMetrics
                    val dispatched = RemoteAccessibilityControlService.dispatchInputEvent(
                        event = event,
                        hostScreenWidth = metrics.widthPixels,
                        hostScreenHeight = metrics.heightPixels
                    )
                    _uiState.update {
                        val actStr = when (event.action) {
                            UsbProtocol.INPUT_ACTION_DOWN -> "DOWN"
                            UsbProtocol.INPUT_ACTION_MOVE -> "MOVE"
                            UsbProtocol.INPUT_ACTION_UP -> "UP"
                            UsbProtocol.INPUT_ACTION_BACK -> "BACK"
                            UsbProtocol.INPUT_ACTION_HOME -> "HOME"
                            UsbProtocol.INPUT_ACTION_RECENTS -> "RECENTS"
                            else -> "ACT_${event.action}"
                        }
                        it.copy(
                            lastDispatchedAction = "$actStr (${String.format("%.2f", event.normalizedX)}, ${String.format("%.2f", event.normalizedY)}) [ok=$dispatched]"
                        )
                    }
                }
            }

            UsbProtocol.TYPE_CONTROL_STATUS -> {
                val status = ControlStatusPayload.fromByteArray(packet.payload)
                if (status != null) {
                    _uiState.update {
                        it.copy(
                            isPeerControlPermissionGranted = status.isPermissionGranted,
                            isPeerControlActive = status.isControlActive,
                            remoteControlStatusText = if (status.isControlActive) "Active" else if (status.isPermissionGranted) "Available" else "Requires Permission"
                        )
                    }
                }
            }

            UsbProtocol.TYPE_CONTROL_COMMAND -> {
                val cmd = ControlCommandPayload.fromByteArray(packet.payload)
                if (cmd != null) {
                    if (cmd.command == ControlCommandPayload.CMD_ENABLE) {
                        setRemoteControlEnabledOnHost(true)
                    } else if (cmd.command == ControlCommandPayload.CMD_DISABLE) {
                        setRemoteControlEnabledOnHost(false)
                    }
                }
            }

            UsbProtocol.TYPE_DISCONNECT -> {
                disconnect()
            }
        }
    }

    /**
     * Starts MediaProjection screen capture foreground service and initiates live streaming to Phone 2.
     */
    fun startScreenSharing(resultCode: Int, resultData: Intent, metrics: DisplayMetrics) {
        val app = getApplication<Application>()
        val transport = usbManager.currentTransport

        if (transport == null || !transport.isConnected) {
            _uiState.update {
                it.copy(statusBanner = "Cannot start screen sharing: USB cable is not connected")
            }
            return
        }

        // Configure pipeline output packet sender
        ScreenCaptureService.packetSender = { packet ->
            usbManager.currentTransport?.sendPacket(packet)
        }

        // Resolution and bitrate from settings
        val settings = _uiState.value.settings
        val targetWidth = when (settings.resolution) {
            com.example.model.ResolutionPreset.HD_720P -> 720
            com.example.model.ResolutionPreset.FHD_1080P -> 1080
            com.example.model.ResolutionPreset.NATIVE -> metrics.widthPixels.let { if (it % 2 != 0) it - 1 else it }
        }
        val targetHeight = when (settings.resolution) {
            com.example.model.ResolutionPreset.NATIVE -> metrics.heightPixels.let { if (it % 2 != 0) it - 1 else it }
            else -> (targetWidth * (metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat())).toInt().let {
                if (it % 2 != 0) it + 1 else it
            }
        }

        val serviceIntent = Intent(app, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, resultData)
            putExtra(ScreenCaptureService.EXTRA_WIDTH, targetWidth)
            putExtra(ScreenCaptureService.EXTRA_HEIGHT, targetHeight)
            putExtra(ScreenCaptureService.EXTRA_DPI, metrics.densityDpi)
            putExtra(ScreenCaptureService.EXTRA_BITRATE, settings.bitrateMbps * 1_000_000)
            putExtra(ScreenCaptureService.EXTRA_FPS, settings.targetFps)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(serviceIntent)
            } else {
                app.startService(serviceIntent)
            }

            // Notify Controller that streaming is starting
            viewModelScope.launch {
                transport.sendPacket(
                    UsbPacket(
                        type = UsbProtocol.TYPE_STREAM_START,
                        sequenceId = 0
                    )
                )
            }

            _uiState.update {
                it.copy(
                    isScreenSharing = true,
                    statusBanner = "Live screen sharing active (${targetWidth}x${targetHeight} @ ${settings.targetFps}fps)"
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(statusBanner = "Failed to start capture service: ${e.message}")
            }
        }
    }

    /**
     * Cleanly stops screen capture and halts MediaProjection.
     */
    fun stopScreenSharing() {
        val app = getApplication<Application>()
        val serviceIntent = Intent(app, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        app.startService(serviceIntent)

        viewModelScope.launch {
            usbManager.currentTransport?.sendPacket(
                UsbPacket(
                    type = UsbProtocol.TYPE_STREAM_STOP,
                    sequenceId = 0
                )
            )
        }

        _uiState.update {
            it.copy(
                isScreenSharing = false,
                statusBanner = "Screen sharing stopped"
            )
        }
    }

    /**
     * Attaches target Surface from SurfaceView to the hardware decoder.
     */
    fun setDecoderSurface(surface: Surface?) {
        videoDecoder.setSurface(surface)
    }

    /**
     * Controller action to stop viewing stream.
     */
    fun stopViewing() {
        videoDecoder.stop()
        _uiState.update {
            it.copy(
                isStreamingActive = false,
                currentScreen = AppScreen.HOME,
                statusBanner = "Stopped viewing stream"
            )
        }
    }

    fun selectRole(role: UsbRole) {
        _uiState.update {
            it.copy(
                activeRole = role,
                currentScreen = when (role) {
                    UsbRole.HOST -> AppScreen.HOST_SESSION
                    UsbRole.CONTROLLER -> AppScreen.CONTROLLER_SESSION
                    UsbRole.NONE -> AppScreen.HOME
                },
                isSessionActive = role != UsbRole.NONE
            )
        }

        val currentDevice = _uiState.value.detectedDevices.firstOrNull()
        if (currentDevice != null && currentDevice.hasPermission && role != UsbRole.NONE) {
            connectDevice(currentDevice)
        }
    }

    fun navigateTo(screen: AppScreen) {
        _uiState.update { it.copy(currentScreen = screen) }
    }

    fun scanDevices() {
        _uiState.update { it.copy(isScanning = true) }
        usbManager.refreshDevices()
        viewModelScope.launch {
            delay(500)
            _uiState.update { it.copy(isScanning = false) }
        }
    }

    fun requestDevicePermission(device: UsbDeviceInfo) {
        usbManager.requestDevicePermission(device)
    }

    fun connectDevice(device: UsbDeviceInfo) {
        viewModelScope.launch {
            val role = if (_uiState.value.activeRole != UsbRole.NONE) _uiState.value.activeRole else UsbRole.HOST
            val result = usbManager.connectDevice(device, role)
            if (result.isFailure) {
                _uiState.update {
                    it.copy(statusBanner = "Connect error: ${result.exceptionOrNull()?.message}")
                }
            } else {
                _uiState.update {
                    it.copy(statusBanner = "Linked with ${device.displayName} over USB")
                }
            }
        }
    }

    /**
     * Executes the bidirectional USB test protocol.
     */
    fun testUsbConnection(customText: String? = null) {
        viewModelScope.launch {
            val transport = usbManager.currentTransport
            if (transport == null || !transport.isConnected) {
                val device = _uiState.value.detectedDevices.firstOrNull()
                if (device != null && device.hasPermission) {
                    connectDevice(device)
                    delay(300)
                } else if (device != null && !device.hasPermission) {
                    requestDevicePermission(device)
                    _uiState.update { it.copy(statusBanner = "Please accept Android USB permission dialog first") }
                    return@launch
                } else {
                    _uiState.update { it.copy(statusBanner = "No USB peer connected. Connect phone via USB cable first.") }
                    return@launch
                }
            }

            val currentTransport = usbManager.currentTransport
            if (currentTransport == null || !currentTransport.isConnected) {
                _uiState.update { it.copy(statusBanner = "USB wire not connected or endpoints not claimed.") }
                return@launch
            }

            _uiState.update { it.copy(isTestInProgress = true) }
            lastPingSentTime = System.currentTimeMillis()

            val role = if (_uiState.value.activeRole != UsbRole.NONE) _uiState.value.activeRole else UsbRole.HOST
            val seq = messageSeqCounter.getAndIncrement()
            val text = customText ?: "Test Packet #$seq from ${role.name} (${android.os.Build.MODEL})"

            val msg = UsbMessage(
                senderRole = role,
                text = text,
                timestampMs = System.currentTimeMillis(),
                sequenceNumber = seq
            )

            val packet = msg.toPacket(sequenceId = seq.toInt())
            val res = currentTransport.sendPacket(packet)

            if (res.isSuccess) {
                _uiState.update {
                    it.copy(
                        lastSentMessage = msg,
                        statusBanner = "Sent: \"$text\" via physical USB",
                        isTestInProgress = false
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        statusBanner = "USB Send Failed: ${res.exceptionOrNull()?.message}",
                        isTestInProgress = false
                    )
                }
            }
        }
    }

    fun sendTestPing() {
        testUsbConnection()
    }

    private var lastMoveSentTimeMs: Long = 0L
    private var lastMoveSentX: Float = -1f
    private var lastMoveSentY: Float = -1f

    /**
     * Sends a touch event (DOWN, MOVE, UP) from Phone 2 over USB to Phone 1.
     * Throttles MOVE events to ~60Hz while preserving all DOWN and UP events.
     */
    fun sendRemoteInput(
        action: Int,
        normalizedX: Float,
        normalizedY: Float,
        pointerId: Int = 0
    ) {
        if (!_uiState.value.isControllerTouchActive) return
        val transport = usbManager.currentTransport ?: return
        if (!transport.isConnected) return

        // Throttle high-frequency MOVE events to ~60Hz and filter sub-pixel jitter
        if (action == UsbProtocol.INPUT_ACTION_MOVE) {
            val now = System.currentTimeMillis()
            val dx = normalizedX - lastMoveSentX
            val dy = normalizedY - lastMoveSentY
            val dist = kotlin.math.hypot(dx, dy)
            if (now - lastMoveSentTimeMs < 16L && dist < 0.003f) {
                return // Drop redundant/excessive move event to protect USB bandwidth
            }
            lastMoveSentTimeMs = now
            lastMoveSentX = normalizedX
            lastMoveSentY = normalizedY
        } else if (action == UsbProtocol.INPUT_ACTION_DOWN) {
            lastMoveSentTimeMs = System.currentTimeMillis()
            lastMoveSentX = normalizedX
            lastMoveSentY = normalizedY
        }

        viewModelScope.launch {
            val event = RemoteInputEvent(
                action = action,
                pointerId = pointerId,
                normalizedX = normalizedX,
                normalizedY = normalizedY,
                pressure = 1.0f,
                timestampMs = System.currentTimeMillis(),
                sessionId = 1
            )
            val packet = event.toPacket(messageSeqCounter.getAndIncrement().toInt())
            transport.sendPacket(packet)
        }
    }

    /**
     * Sends system navigation command (Back, Home, Recents) to Phone 1 via USB.
     */
    fun sendGlobalNavigation(action: Int) {
        val transport = usbManager.currentTransport ?: return
        if (!transport.isConnected) return

        viewModelScope.launch {
            val event = RemoteInputEvent(
                action = action,
                pointerId = 0,
                normalizedX = 0.5f,
                normalizedY = 0.5f,
                timestampMs = System.currentTimeMillis()
            )
            val packet = event.toPacket(messageSeqCounter.getAndIncrement().toInt())
            transport.sendPacket(packet)
            _uiState.update {
                val actionName = when (action) {
                    UsbProtocol.INPUT_ACTION_BACK -> "BACK"
                    UsbProtocol.INPUT_ACTION_HOME -> "HOME"
                    UsbProtocol.INPUT_ACTION_RECENTS -> "RECENTS"
                    else -> "NAV"
                }
                it.copy(statusBanner = "Dispatched $actionName to Phone 1")
            }
        }
    }

    /**
     * Enables or disables remote control on Phone 1 (Host).
     * Immediate safety switch for the user on Phone 1.
     */
    fun setRemoteControlEnabledOnHost(enabled: Boolean) {
        _uiState.update {
            it.copy(
                isRemoteControlEnabledOnHost = enabled,
                statusBanner = if (enabled) "Remote control enabled on Host" else "Remote control paused by Host"
            )
        }
        broadcastControlStatus()
    }

    /**
     * Enables or disables touch sending mode on Phone 2 (Controller).
     */
    fun setControllerTouchActive(active: Boolean) {
        _uiState.update {
            it.copy(
                isControllerTouchActive = active,
                statusBanner = if (active) "Remote touch input ENABLED" else "Remote touch input DISABLED"
            )
        }
        viewModelScope.launch {
            val cmd = if (active) ControlCommandPayload.CMD_ENABLE else ControlCommandPayload.CMD_DISABLE
            usbManager.currentTransport?.sendPacket(
                ControlCommandPayload(cmd).toPacket(messageSeqCounter.getAndIncrement().toInt())
            )
        }
    }

    /**
     * Broadcasts current control capability and permission status over USB.
     */
    fun broadcastControlStatus() {
        viewModelScope.launch {
            val transport = usbManager.currentTransport ?: return@launch
            if (!transport.isConnected) return@launch
            val app = getApplication<Application>()
            val hasPerm = RemoteAccessibilityControlService.isRunning() ||
                    RemoteAccessibilityControlService.isServiceEnabledInSettings(app)
            val isActive = hasPerm && _uiState.value.isRemoteControlEnabledOnHost
            val payload = ControlStatusPayload(
                isPermissionGranted = hasPerm,
                isControlActive = isActive
            )
            transport.sendPacket(payload.toPacket(messageSeqCounter.getAndIncrement().toInt()))
        }
    }

    /**
     * Re-checks accessibility permission state (called when user returns from settings).
     */
    fun refreshAccessibilityStatus() {
        val app = getApplication<Application>()
        val hasPerm = RemoteAccessibilityControlService.isRunning() ||
                RemoteAccessibilityControlService.isServiceEnabledInSettings(app)
        _uiState.update { it.copy(isAccessibilityServiceActive = hasPerm) }
        broadcastControlStatus()
    }

    fun disconnect() {
        packetReceiverJob?.cancel()
        packetReceiverJob = null
        if (_uiState.value.isScreenSharing) {
            stopScreenSharing()
        }
        videoDecoder.stop()
        usbManager.disconnect()
        _uiState.update {
            it.copy(
                statusBanner = "USB disconnected",
                transportStats = TransportStats(),
                isTestInProgress = false,
                isStreamingActive = false,
                isScreenSharing = false,
                isControllerTouchActive = false,
                isPeerControlActive = false
            )
        }
    }

    fun stopSession() {
        disconnect()
        _uiState.update {
            it.copy(
                activeRole = UsbRole.NONE,
                isSessionActive = false,
                currentScreen = AppScreen.HOME,
                statusBanner = "Session ended",
                lastReceivedMessage = null,
                lastSentMessage = null
            )
        }
    }

    fun updateSettings(newSettings: SettingsModel) {
        _uiState.update {
            it.copy(
                settings = newSettings,
                statusBanner = "Settings updated"
            )
        }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(statusBanner = null) }
    }

    override fun onCleared() {
        super.onCleared()
        packetReceiverJob?.cancel()
        videoDecoder.stop()
        if (_uiState.value.isScreenSharing) {
            stopScreenSharing()
        }
        usbManager.unregister()
    }
}
