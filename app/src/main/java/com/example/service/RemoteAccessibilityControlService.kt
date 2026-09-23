package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import com.example.usb.model.RemoteInputEvent
import com.example.usb.model.UsbProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot

/**
 * Official Android Accessibility Service for Phone 1 (Host).
 *
 * Receives remote touch inputs transmitted from Phone 2 over the USB wire and dispatches
 * real gestures (taps, long presses, swipes/drags, and global navigation: back, home, recents)
 * on Phone 1 using the official Android SDK AccessibilityService.dispatchGesture() API.
 *
 * Strictly adheres to Android's security model:
 * - Requires explicit user permission in System Accessibility Settings.
 * - Does not use root, exploits, or hidden APIs.
 * - Can be paused or stopped immediately by the user on Phone 1 at any time.
 */
class RemoteAccessibilityControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "RemoteAccessibilityControlService connected and ready for input dispatch")
        instance = this
        _isServiceRunning.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — this service is dedicated to gesture dispatch
    }

    override fun onInterrupt() {
        Log.w(TAG, "RemoteAccessibilityControlService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "RemoteAccessibilityControlService unbound")
        instance = null
        _isServiceRunning.value = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isServiceRunning.value = false
    }

    companion object {
        private const val TAG = "RemoteAccessService"

        private var instance: RemoteAccessibilityControlService? = null
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning = _isServiceRunning.asStateFlow()

        // Tracking active stroke gesture on Host
        private var downPoint: PointF? = null
        private var lastMovePoint: PointF? = null
        private var downTimeMs: Long = 0L
        private val strokePoints = mutableListOf<PointF>()

        fun isRunning(): Boolean = instance != null

        /**
         * Checks whether this service is enabled in Android System Accessibility Settings.
         */
        fun isServiceEnabledInSettings(context: Context): Boolean {
            if (isRunning()) return true
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            val expectedCls = RemoteAccessibilityControlService::class.java.name
            return enabledServices.any {
                it.resolveInfo?.serviceInfo?.packageName == context.packageName &&
                        (it.resolveInfo?.serviceInfo?.name == expectedCls ||
                                it.resolveInfo?.serviceInfo?.name?.endsWith("RemoteAccessibilityControlService") == true)
            }
        }

        /**
         * Opens Android's system Accessibility Settings page so the user can grant permission.
         */
        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }

        /**
         * Dispatches input event received from Phone 2.
         * Maps normalized coordinates (0.0-1.0) to Phone 1's actual screen dimensions.
         */
        fun dispatchInputEvent(
            event: RemoteInputEvent,
            hostScreenWidth: Int,
            hostScreenHeight: Int
        ): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "Cannot dispatch input: RemoteAccessibilityControlService not connected/enabled")
                return false
            }

            // Handle system global navigation actions
            when (event.action) {
                UsbProtocol.INPUT_ACTION_BACK -> {
                    Log.d(TAG, "Executing GLOBAL_ACTION_BACK on Host")
                    return service.performGlobalAction(GLOBAL_ACTION_BACK)
                }
                UsbProtocol.INPUT_ACTION_HOME -> {
                    Log.d(TAG, "Executing GLOBAL_ACTION_HOME on Host")
                    return service.performGlobalAction(GLOBAL_ACTION_HOME)
                }
                UsbProtocol.INPUT_ACTION_RECENTS -> {
                    Log.d(TAG, "Executing GLOBAL_ACTION_RECENTS on Host")
                    return service.performGlobalAction(GLOBAL_ACTION_RECENTS)
                }
            }

            // Convert normalized coordinates to actual Host display pixels
            val hostX = (event.normalizedX * hostScreenWidth).coerceIn(0f, hostScreenWidth.toFloat())
            val hostY = (event.normalizedY * hostScreenHeight).coerceIn(0f, hostScreenHeight.toFloat())

            return when (event.action) {
                UsbProtocol.INPUT_ACTION_DOWN -> {
                    downPoint = PointF(hostX, hostY)
                    lastMovePoint = PointF(hostX, hostY)
                    downTimeMs = System.currentTimeMillis()
                    strokePoints.clear()
                    strokePoints.add(PointF(hostX, hostY))
                    true
                }

                UsbProtocol.INPUT_ACTION_MOVE -> {
                    val prev = lastMovePoint
                    if (prev != null) {
                        val dist = hypot(hostX - prev.x, hostY - prev.y)
                        // Sample movements >= 4px to avoid excessively complex paths in dispatchGesture
                        if (dist >= 4f) {
                            strokePoints.add(PointF(hostX, hostY))
                            lastMovePoint = PointF(hostX, hostY)
                        }
                    }
                    true
                }

                UsbProtocol.INPUT_ACTION_UP -> {
                    val start = downPoint ?: PointF(hostX, hostY)
                    val end = PointF(hostX, hostY)
                    val elapsedMs = System.currentTimeMillis() - downTimeMs

                    val totalDist = hypot(end.x - start.x, end.y - start.y)

                    val gesture = if (totalDist < 25f) {
                        if (elapsedMs >= 400L) {
                            // Long press: 600ms hold at target position
                            createClickGesture(start.x, start.y, durationMs = 600L)
                        } else {
                            // Standard Tap: 50ms click at target position
                            createClickGesture(start.x, start.y, durationMs = 50L)
                        }
                    } else {
                        // Swipe or Drag gesture along user-drawn path
                        val path = Path()
                        path.moveTo(start.x, start.y)
                        if (strokePoints.size > 2) {
                            for (i in 1 until strokePoints.size) {
                                val pt = strokePoints[i]
                                path.lineTo(pt.x, pt.y)
                            }
                            path.lineTo(end.x, end.y)
                        } else {
                            path.lineTo(end.x, end.y)
                        }
                        val gestureDuration = elapsedMs.coerceIn(120L, 800L)
                        val stroke = GestureDescription.StrokeDescription(path, 0, gestureDuration)
                        GestureDescription.Builder().addStroke(stroke).build()
                    }

                    // Reset tracking state
                    downPoint = null
                    lastMovePoint = null
                    strokePoints.clear()

                    try {
                        service.dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                Log.v(TAG, "Gesture completed successfully on Host")
                            }

                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                Log.w(TAG, "Gesture cancelled by Android OS")
                            }
                        }, null)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in dispatchGesture", e)
                        false
                    }
                }

                else -> false
            }
        }

        private fun createClickGesture(x: Float, y: Float, durationMs: Long): GestureDescription {
            val path = Path()
            path.moveTo(x, y)
            path.lineTo(x, y)
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            return GestureDescription.Builder().addStroke(stroke).build()
        }
    }
}
