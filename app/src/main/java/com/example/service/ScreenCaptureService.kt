package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.stream.encoder.HardwareVideoEncoder
import com.example.usb.model.UsbPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground Service for Android MediaProjection screen capture.
 * Required on Android 10+ / 14+ for compliant, uninterrupted background screen recording.
 */
class ScreenCaptureService : Service() {

    companion object {
        private const val TAG = "ScreenCaptureService"
        const val NOTIFICATION_CHANNEL_ID = "usb_screen_link_capture"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.service.START_CAPTURE"
        const val ACTION_STOP = "com.example.service.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_DPI = "extra_dpi"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_FPS = "extra_fps"

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        var packetSender: (suspend (UsbPacket) -> Unit)? = null
    }

    private val binder = LocalBinder()
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: HardwareVideoEncoder? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
        fun getEncoder(): HardwareVideoEncoder? = encoder
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode != -1 && resultData != null) {
                    val width = intent.getIntExtra(EXTRA_WIDTH, 1080)
                    val height = intent.getIntExtra(EXTRA_HEIGHT, 2400)
                    val dpi = intent.getIntExtra(EXTRA_DPI, 400)
                    val bitrate = intent.getIntExtra(EXTRA_BITRATE, 5_000_000)
                    val fps = intent.getIntExtra(EXTRA_FPS, 60)

                    startForegroundWithNotification()
                    startCapture(resultCode, resultData, width, height, dpi, bitrate, fps)
                } else {
                    Log.e(TAG, "Cannot start capture: missing resultCode or resultData")
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                stopCapture()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("USB Screen Link — Sharing Active")
            .setContentText("Screen is streaming live to the Controller phone over USB wire")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent,
        width: Int,
        height: Int,
        dpi: Int,
        bitrate: Int,
        fps: Int
    ) {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            if (projection == null) {
                Log.e(TAG, "Cannot start capture: MediaProjection returned null")
                stopSelf()
                return
            }
            this.mediaProjection = projection

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection was stopped by system or user")
                    stopCapture()
                    stopSelf()
                }
            }, null)

            // Setup hardware video encoder
            val videoEncoder = HardwareVideoEncoder(
                width = width,
                height = height,
                targetFps = fps,
                bitrateBps = bitrate,
                onPacketReady = { packet ->
                    packetSender?.invoke(packet)
                }
            )

            val surfaceResult = videoEncoder.initialize()
            if (surfaceResult.isFailure) {
                Log.e(TAG, "Failed to initialize video encoder surface", surfaceResult.exceptionOrNull())
                stopSelf()
                return
            }

            val inputSurface = surfaceResult.getOrThrow()
            this.encoder = videoEncoder

            // Create VirtualDisplay bound to encoder input surface
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            this.virtualDisplay = projection.createVirtualDisplay(
                "UsbScreenLinkDisplay",
                width,
                height,
                dpi,
                flags,
                inputSurface,
                null,
                null
            )

            videoEncoder.startEncoding()
            _isServiceRunning.value = true
            Log.i(TAG, "Screen capture pipeline actively mirroring display (${width}x${height} @ ${fps}fps)")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting screen capture pipeline", e)
            stopCapture()
            stopSelf()
        }
    }

    fun stopCapture() {
        _isServiceRunning.value = false
        try {
            encoder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping encoder", e)
        } finally {
            encoder = null
        }

        try {
            virtualDisplay?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing virtual display", e)
        } finally {
            virtualDisplay = null
        }

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping media projection", e)
        } finally {
            mediaProjection = null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.i(TAG, "Screen capture pipeline cleanly stopped")
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Screen Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing notification while screen is being shared over USB"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
