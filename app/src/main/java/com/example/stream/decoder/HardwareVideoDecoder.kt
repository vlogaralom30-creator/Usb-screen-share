package com.example.stream.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import com.example.usb.model.StreamConfig
import com.example.usb.model.VideoFrameData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostics and live statistics for the hardware video decoder on Phone 2.
 */
data class DecoderStats(
    val currentFps: Int = 0,
    val receivedFrames: Long = 0,
    val decodedFrames: Long = 0,
    val droppedFrames: Long = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val isDecoding: Boolean = false,
    val latencyMs: Long = 0
)

/**
 * Hardware-accelerated H.264 video decoder.
 * Feeds received NALUs directly to MediaCodec and renders onto an Android Surface with zero memory copies.
 */
class HardwareVideoDecoder(
    private var surface: Surface? = null
) {
    companion object {
        private const val TAG = "HardwareVideoDecoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val DEQUEUE_TIMEOUT_US = 5_000L // 5ms non-blocking
    }

    private var decoder: MediaCodec? = null
    private val isConfigured = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)

    private val _stats = MutableStateFlow(DecoderStats())
    val stats: StateFlow<DecoderStats> = _stats.asStateFlow()

    private var currentConfig: StreamConfig? = null
    private var lastFpsCalculationTime = System.currentTimeMillis()
    private var framesThisSecond = 0
    private val totalReceivedFrames = AtomicLong(0)
    private val totalDroppedFrames = AtomicLong(0)

    /**
     * Sets or updates the target display Surface from SurfaceView.
     */
    fun setSurface(newSurface: Surface?) {
        this.surface = newSurface
        if (newSurface != null && currentConfig != null && !isConfigured.get()) {
            configureDecoder(currentConfig!!)
        }
    }

    /**
     * Configures or reinitializes the hardware decoder with stream parameters.
     */
    @Synchronized
    fun configureDecoder(config: StreamConfig) {
        val targetSurface = surface
        if (targetSurface == null || !targetSurface.isValid) {
            Log.w(TAG, "Cannot configure decoder: display Surface is not ready")
            currentConfig = config
            return
        }

        try {
            stop()

            val format = MediaFormat.createVideoFormat(MIME_TYPE, config.width, config.height).apply {
                if (config.spsPps.isNotEmpty()) {
                    splitAndAssignCsd(this, config.spsPps)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }

            val mediaCodec = MediaCodec.createDecoderByType(MIME_TYPE)
            mediaCodec.configure(format, targetSurface, null, 0)
            mediaCodec.start()

            this.decoder = mediaCodec
            this.currentConfig = config
            this.isConfigured.set(true)
            this.isRunning.set(true)

            _stats.update {
                it.copy(
                    videoWidth = config.width,
                    videoHeight = config.height,
                    isDecoding = true
                )
            }

            Log.i(TAG, "Hardware decoder configured and running for ${config.width}x${config.height}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure hardware decoder", e)
            isConfigured.set(false)
        }
    }

    /**
     * Splits SPS/PPS bytes if combined and assigns them to csd-0 and csd-1.
     */
    private fun splitAndAssignCsd(format: MediaFormat, spsPps: ByteArray) {
        // Look for 0x00, 0x00, 0x00, 0x01 start codes
        val naluIndices = mutableListOf<Int>()
        for (i in 0 until spsPps.size - 4) {
            if (spsPps[i] == 0.toByte() && spsPps[i + 1] == 0.toByte() && spsPps[i + 2] == 0.toByte() && spsPps[i + 3] == 1.toByte()) {
                naluIndices.add(i)
            }
        }

        if (naluIndices.size >= 2) {
            val spsBytes = spsPps.copyOfRange(naluIndices[0], naluIndices[1])
            val ppsBytes = spsPps.copyOfRange(naluIndices[1], spsPps.size)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(spsBytes))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsBytes))
        } else {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(spsPps))
        }
    }

    /**
     * Ingests an incoming H.264 video frame packet and renders it to the hardware Surface.
     */
    fun ingestFrame(frameData: VideoFrameData) {
        totalReceivedFrames.incrementAndGet()

        // If not configured yet, attempt auto-configuration from keyframe if it contains SPS/PPS
        if (!isConfigured.get() || decoder == null) {
            if (frameData.isKeyFrame) {
                // Auto-configure with default 720p or 1080p profile
                val fallbackConfig = StreamConfig(
                    width = 1080,
                    height = 2400,
                    fps = 60,
                    bitrate = 5_000_000,
                    spsPps = frameData.naluData
                )
                configureDecoder(fallbackConfig)
            } else {
                totalDroppedFrames.incrementAndGet()
                return
            }
        }

        val mediaCodec = decoder ?: return

        try {
            val inputIndex = mediaCodec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputIndex >= 0) {
                val inputBuffer = mediaCodec.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(frameData.naluData)

                    val flags = if (frameData.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    mediaCodec.queueInputBuffer(
                        inputIndex,
                        0,
                        frameData.naluData.size,
                        frameData.ptsUs,
                        flags
                    )
                }
            } else {
                // Bounded queue: Drop stale non-keyframe to prevent buffer bloat and maintain live latency
                if (!frameData.isKeyFrame) {
                    totalDroppedFrames.incrementAndGet()
                }
            }

            // Immediately drain all ready decoded frames to the Surface
            drainOutputBuffers(mediaCodec)

        } catch (e: Exception) {
            Log.e(TAG, "Error ingesting frame into decoder", e)
        }
    }

    private fun drainOutputBuffers(mediaCodec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val newFormat = mediaCodec.outputFormat
                val w = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                val h = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                _stats.update { it.copy(videoWidth = w, videoHeight = h) }
                Log.i(TAG, "Decoder output format changed to ${w}x${h}")
            } else if (outputIndex >= 0) {
                // Render directly onto Surface with hardware acceleration
                mediaCodec.releaseOutputBuffer(outputIndex, true)

                framesThisSecond++

                val now = System.currentTimeMillis()
                if (now - lastFpsCalculationTime >= 1000) {
                    val elapsedSec = (now - lastFpsCalculationTime) / 1000.0
                    val calculatedFps = (framesThisSecond / elapsedSec).toInt()

                    _stats.update {
                        it.copy(
                            currentFps = calculatedFps,
                            receivedFrames = totalReceivedFrames.get(),
                            decodedFrames = it.decodedFrames + framesThisSecond,
                            droppedFrames = totalDroppedFrames.get()
                        )
                    }

                    framesThisSecond = 0
                    lastFpsCalculationTime = now
                }
            } else {
                break
            }
        }
    }

    /**
     * Stops the decoder and frees all hardware codecs.
     */
    @Synchronized
    fun stop() {
        isRunning.set(false)
        isConfigured.set(false)

        try {
            decoder?.stop()
            decoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping decoder", e)
        } finally {
            decoder = null
        }

        _stats.update { it.copy(isDecoding = false, currentFps = 0) }
        Log.i(TAG, "Hardware decoder stopped")
    }
}
