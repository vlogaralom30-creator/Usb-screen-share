package com.example.model

enum class ResolutionPreset(val title: String, val width: Int, val height: Int) {
    HD_720P("720p (1280 × 720) - Low Latency", 1280, 720),
    FHD_1080P("1080p (1920 × 1080) - Balanced", 1920, 1080),
    NATIVE("Native Resolution - Sharpest", 0, 0)
}

enum class TransportProtocol(val title: String, val description: String) {
    USB_BULK_ENDPOINTS("USB Host Direct (Bulk)", "Direct bulk endpoint transfer via USB OTG cable"),
    AOA_ACCESSORY("Android Open Accessory (AOA 2.0)", "Allows controller phone to act as USB accessory without root"),
    USB_TETHER_TCP("Direct TCP over USB", "Local socket connection over USB Tethering (RNDIS/NCM)")
}

enum class InputMethod(val title: String, val description: String) {
    ACCESSIBILITY_SERVICE("Accessibility Service (Rootless)", "Standard Android Accessibility API for gesture dispatch"),
    ADB_PORT_FORWARD("ADB / Shizuku Injection", "Kernel-level input tap/swipe via local ADB server")
}

data class SettingsModel(
    val resolution: ResolutionPreset = ResolutionPreset.HD_720P,
    val targetFps: Int = 60,
    val bitrateMbps: Int = 8,
    val transportProtocol: TransportProtocol = TransportProtocol.USB_BULK_ENDPOINTS,
    val inputMethod: InputMethod = InputMethod.ACCESSIBILITY_SERVICE,
    val lowLatencyMode: Boolean = true,
    val keepScreenOn: Boolean = true
)
