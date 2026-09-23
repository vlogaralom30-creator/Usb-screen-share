package com.example.stream.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.example.usb.model.StreamConfig
import com.example.usb.model.UsbPacket
import com.example.usb.model.UsbProtocol
import com.example.usb.model.VideoFrameData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostics and live statistics for the hardware video encoder.
 */
data class EncoderStats(
    val currentFps: Int = 0,
    val totalFramesEncoded: Long = 0,
    val totalBytesEncoded: Long = 0,
    val currentBitrateBps: Long = 0,
    val isRunning: Boolean = false,
    val resolutionWidth: Int = 0,
    val resolutionHeight: Int = 0
)

/**
 * Hardware-accelerated H.264/AVC video encoder for low-latency screen capture.
 * Encodes directly from an input Surface (fed by VirtualDisplay) without intermediate Bitmaps.
 */
class HardwareVideoEncoder(
    private val width: Int,
    private val height: Int,
    private val targetFps: Int = 60,
    private val bitrateBps: Int = 5_000_000, // 5 Mbps
    private val onPacketReady: suspend (UsbPacket) -> Unit
) {
    companion object {
        private const val TAG = "HardwareVideoEncoder"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_USEC = 10_000L // 10ms poll
    }

    private var codec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    private val isEncoding = AtomicBoolean(false)
    private var encodeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val frameSeqCounter = AtomicLong(1)
    private val _stats = MutableStateFlow(
        EncoderStats(
            resolutionWidth = width,
            resolutionHeight = height
        )
    )
    val stats: StateFlow<EncoderStats> = _stats.asStateFlow()

    private var spsPpsData: ByteArray? = null

    /**
     * Initializes the MediaCodec hardware encoder and creates the input surface.
     */
    fun initialize(): Result<Surface> {
        try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe every 1 second
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)

                // Ultra-low latency optimization hints on supported devices
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LATENCY, 0)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime priority
            }

            val mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = mediaCodec.createInputSurface()

            mediaCodec.start()
            this.codec = mediaCodec
            this.inputSurface = surface
            _stats.update { it.copy(isRunning = true) }

            Log.i(TAG, "Hardware encoder initialized: ${width}x${height} @ ${targetFps}fps, ${bitrateBps / 1_000_000}Mbps")
            return Result.success(surface)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize hardware video encoder", e)
            return Result.failure(e)
        }
    }

    /**
     * Starts the asynchronous encoding drain loop in a background coroutine.
     */
    fun startEncoding() {
        val mediaCodec = codec ?: return
        if (!isEncoding.compareAndSet(false, true)) return

        encodeJob = scope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            var framesInLastSecond = 0
            var bytesInLastSecond = 0L
            var lastFpsCalcTime = System.currentTimeMillis()

            while (isActive && isEncoding.get()) {
                try {
                    val outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)

                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = mediaCodec.outputFormat
                        Log.i(TAG, "Encoder output format changed: $newFormat")
                        extractSpsPps(newFormat)
                    } else if (outputBufferIndex >= 0) {
                        val outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                            val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)

                            if (isConfig) {
                                spsPpsData = chunk
                                broadcastStreamConfig(chunk)
                            } else {
                                // If we have saved SPS/PPS and this is a keyframe, prepend SPS/PPS if not present
                                val finalPayload = if (isKeyFrame && spsPpsData != null && !containsSpsPps(chunk)) {
                                    spsPpsData!! + chunk
                                } else {
                                    chunk
                                }

                                val frameData = VideoFrameData(
                                    ptsUs = bufferInfo.presentationTimeUs,
                                    frameNumber = frameSeqCounter.getAndIncrement(),
                                    isKeyFrame = isKeyFrame,
                                    naluData = finalPayload
                                )

                                val packet = frameData.toPacket(sequenceId = frameData.frameNumber.toInt())
                                onPacketReady(packet)

                                framesInLastSecond++
                                bytesInLastSecond += finalPayload.size
                            }
                        }

                        mediaCodec.releaseOutputBuffer(outputBufferIndex, false)

                        // Update live stats every second
                        val now = System.currentTimeMillis()
                        if (now - lastFpsCalcTime >= 1000) {
                            val elapsedSec = (now - lastFpsCalcTime) / 1000.0
                            val calculatedFps = (framesInLastSecond / elapsedSec).toInt()
                            val calculatedBitrate = ((bytesInLastSecond * 8) / elapsedSec).toLong()

                            _stats.update {
                                it.copy(
                                    currentFps = calculatedFps,
                                    totalFramesEncoded = it.totalFramesEncoded + framesInLastSecond,
                                    totalBytesEncoded = it.totalBytesEncoded + bytesInLastSecond,
                                    currentBitrateBps = calculatedBitrate
                                )
                            }

                            framesInLastSecond = 0
                            bytesInLastSecond = 0L
                            lastFpsCalcTime = now
                        }
                    }
                } catch (e: Exception) {
                    if (isEncoding.get()) {
                        Log.e(TAG, "Error in video encoder drain loop", e)
                    }
                    break
                }
            }
        }
    }

    private suspend fun broadcastStreamConfig(spsPpsBytes: ByteArray) {
        val config = StreamConfig(
            width = width,
            height = height,
            fps = targetFps,
            bitrate = bitrateBps,
            spsPps = spsPpsBytes
        )
        val packet = UsbPacket(
            type = UsbProtocol.TYPE_STREAM_CONFIG,
            flags = UsbProtocol.FLAG_CONFIG,
            sequenceId = 0,
            payload = config.toByteArray()
        )
        onPacketReady(packet)
    }

    private suspend fun extractSpsPps(format: MediaFormat) {
        val csd0 = format.getByteBuffer("csd-0")
        val csd1 = format.getByteBuffer("csd-1")
        if (csd0 != null && csd1 != null) {
            val sps = ByteArray(csd0.remaining())
            csd0.get(sps)
            csd0.rewind()

            val pps = ByteArray(csd1.remaining())
            csd1.get(pps)
            csd1.rewind()

            val combined = sps + pps
            spsPpsData = combined
            broadcastStreamConfig(combined)
        }
    }

    private fun containsSpsPps(bytes: ByteArray): Boolean {
        // Checks if payload already begins with SPS NAL unit (NAL type 7)
        if (bytes.size < 5) return false
        for (i in 0 until minOf(bytes.size - 4, 32)) {
            if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte()) {
                val nalType = bytes[i + 4].toInt() and 0x1F
                if (nalType == 7) return true
            }
        }
        return false
    }

    /**
     * Requests the hardware encoder to output an immediate IDR keyframe (sync frame).
     */
    fun requestKeyFrame() {
        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec?.setParameters(params)
            Log.d(TAG, "Requested sync frame from encoder")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request sync frame: ${e.message}")
        }
    }

    /**
     * Stops the encoder and cleans up all MediaCodec and Surface resources.
     */
    fun stop() {
        isEncoding.set(false)
        encodeJob?.cancel()
        encodeJob = null

        try {
            codec?.signalEndOfInputStream()
        } catch (ignored: Exception) {}

        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaCodec", e)
        } finally {
            codec = null
        }

        try {
            inputSurface?.release()
        } catch (ignored: Exception) {}
        inputSurface = null

        _stats.update { it.copy(isRunning = false, currentFps = 0) }
        Log.i(TAG, "Hardware encoder stopped")
    }
}
