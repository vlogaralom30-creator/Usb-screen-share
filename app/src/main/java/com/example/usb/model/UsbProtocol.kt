package com.example.usb.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary protocol definitions for phone-to-phone USB Screen Link.
 * Designed for zero-overhead, sub-millisecond serialization over raw USB bulk endpoints.
 */
object UsbProtocol {
    // 4-byte Magic header: "USBS" (0x55, 0x53, 0x42, 0x53)
    val MAGIC_HEADER = byteArrayOf(0x55, 0x53, 0x42, 0x53)
    const val PROTOCOL_VERSION: Byte = 1

    // Packet Types
    const val TYPE_HANDSHAKE: Byte = 0x01
    const val TYPE_HANDSHAKE_ACK: Byte = 0x02
    const val TYPE_PING: Byte = 0x03
    const val TYPE_PONG: Byte = 0x04
    const val TYPE_TEST_MESSAGE: Byte = 0x05

    // Live Video Streaming Packets
    const val TYPE_FRAME_HEADER: Byte = 0x10
    const val TYPE_VIDEO_FRAME: Byte = 0x11
    const val TYPE_STREAM_CONFIG: Byte = 0x12
    const val TYPE_STREAM_START: Byte = 0x13
    const val TYPE_STREAM_STOP: Byte = 0x14

    // Remote Control & Input Injection Packets
    const val TYPE_INPUT_EVENT: Byte = 0x20
    const val TYPE_CONTROL_STATUS: Byte = 0x21
    const val TYPE_CONTROL_COMMAND: Byte = 0x22
    const val TYPE_CONFIG_SYNC: Byte = 0x30
    const val TYPE_DISCONNECT: Byte = 0x7F

    // Packet Header Size: 4 (Magic) + 1 (Version) + 1 (Type) + 1 (Flags) + 4 (SeqId) + 4 (PayloadLen) = 15 bytes
    const val HEADER_SIZE = 15

    // Flags
    const val FLAG_KEY_FRAME: Byte = 0x01
    const val FLAG_CONFIG: Byte = 0x02
    const val FLAG_COMPRESSED: Byte = 0x04

    // Input Actions
    const val INPUT_ACTION_DOWN = 0
    const val INPUT_ACTION_UP = 1
    const val INPUT_ACTION_MOVE = 2
    const val INPUT_ACTION_POINTER_DOWN = 5
    const val INPUT_ACTION_POINTER_UP = 6
    const val INPUT_ACTION_BACK = 10
    const val INPUT_ACTION_HOME = 11
    const val INPUT_ACTION_RECENTS = 12
}

/**
 * Standard packet wrapper for USB transmission.
 */
data class UsbPacket(
    val type: Byte,
    val flags: Byte = 0,
    val sequenceId: Int = 0,
    val payload: ByteArray = ByteArray(0)
) {
    val isKeyFrame: Boolean
        get() = (flags.toInt() and UsbProtocol.FLAG_KEY_FRAME.toInt()) != 0

    val isConfig: Boolean
        get() = (flags.toInt() and UsbProtocol.FLAG_CONFIG.toInt()) != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UsbPacket) return false
        return type == other.type &&
                flags == other.flags &&
                sequenceId == other.sequenceId &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.toInt()
        result = 31 * result + flags.toInt()
        result = 31 * result + sequenceId
        result = 31 * result + payload.contentHashCode()
        return result
    }

    /**
     * Serializes this packet into wire format:
     * [4 bytes Magic][1 byte Version][1 byte Type][1 byte Flags][4 bytes SeqId][4 bytes PayloadLen][Payload bytes]
     */
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(UsbProtocol.HEADER_SIZE + payload.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(UsbProtocol.MAGIC_HEADER)
        buffer.put(UsbProtocol.PROTOCOL_VERSION)
        buffer.put(type)
        buffer.put(flags)
        buffer.putInt(sequenceId)
        buffer.putInt(payload.size)
        if (payload.isNotEmpty()) {
            buffer.put(payload)
        }
        return buffer.array()
    }

    companion object {
        fun fromByteBuffer(buffer: ByteBuffer): UsbPacket? {
            if (buffer.remaining() < UsbProtocol.HEADER_SIZE) return null

            // Verify Magic
            val mark = buffer.position()
            val m0 = buffer.get()
            val m1 = buffer.get()
            val m2 = buffer.get()
            val m3 = buffer.get()
            if (m0 != UsbProtocol.MAGIC_HEADER[0] ||
                m1 != UsbProtocol.MAGIC_HEADER[1] ||
                m2 != UsbProtocol.MAGIC_HEADER[2] ||
                m3 != UsbProtocol.MAGIC_HEADER[3]
            ) {
                // Not a valid packet header, seek to next byte to resynchronize
                buffer.position(mark + 1)
                return null
            }

            val version = buffer.get()
            if (version != UsbProtocol.PROTOCOL_VERSION) {
                buffer.position(mark + 1)
                return null
            }

            val type = buffer.get()
            val flags = buffer.get()
            val seqId = buffer.int
            val payloadLen = buffer.int

            if (payloadLen < 0 || payloadLen > 5 * 1024 * 1024) { // 5 MB safety limit
                // Corrupted packet length, advance 1 byte from mark to search for next magic
                buffer.position(mark + 1)
                return null
            }

            if (buffer.remaining() < payloadLen) {
                // Payload not fully arrived yet, rewind to start of header
                buffer.position(mark)
                return null
            }

            val payload = ByteArray(payloadLen)
            if (payloadLen > 0) {
                buffer.get(payload)
            }

            return UsbPacket(
                type = type,
                flags = flags,
                sequenceId = seqId,
                payload = payload
            )
        }
    }
}

/**
 * Stream configuration payload (resolution, framerate, and H.264 SPS/PPS).
 */
data class StreamConfig(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val spsPps: ByteArray = ByteArray(0)
) {
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(16 + spsPps.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(width)
        buffer.putInt(height)
        buffer.putInt(fps)
        buffer.putInt(spsPps.size)
        if (spsPps.isNotEmpty()) {
            buffer.put(spsPps)
        }
        return buffer.array()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): StreamConfig? {
            if (bytes.size < 16) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val width = buffer.int
            val height = buffer.int
            val fps = buffer.int
            val spsPpsLen = buffer.int
            if (spsPpsLen < 0 || buffer.remaining() < spsPpsLen) return null
            val spsPps = ByteArray(spsPpsLen)
            if (spsPpsLen > 0) {
                buffer.get(spsPps)
            }
            return StreamConfig(width, height, fps, 0, spsPps)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StreamConfig) return false
        return width == other.width &&
                height == other.height &&
                fps == other.fps &&
                bitrate == other.bitrate &&
                spsPps.contentEquals(other.spsPps)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + fps
        result = 31 * result + bitrate
        result = 31 * result + spsPps.contentHashCode()
        return result
    }
}

/**
 * Decoded video frame packet structure.
 * Header inside payload: [8 bytes ptsUs][8 bytes frameNumber][N bytes H.264 NALU data]
 */
data class VideoFrameData(
    val ptsUs: Long,
    val frameNumber: Long,
    val isKeyFrame: Boolean,
    val naluData: ByteArray
) {
    fun toPacket(sequenceId: Int): UsbPacket {
        val buffer = ByteBuffer.allocate(16 + naluData.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(ptsUs)
        buffer.putLong(frameNumber)
        buffer.put(naluData)

        val flags = if (isKeyFrame) UsbProtocol.FLAG_KEY_FRAME else 0.toByte()
        return UsbPacket(
            type = UsbProtocol.TYPE_VIDEO_FRAME,
            flags = flags,
            sequenceId = sequenceId,
            payload = buffer.array()
        )
    }

    companion object {
        fun fromPacket(packet: UsbPacket): VideoFrameData? {
            if (packet.payload.size < 16) return null
            val buffer = ByteBuffer.wrap(packet.payload).order(ByteOrder.BIG_ENDIAN)
            val ptsUs = buffer.long
            val frameNumber = buffer.long
            val naluLen = buffer.remaining()
            val naluBytes = ByteArray(naluLen)
            buffer.get(naluBytes)

            return VideoFrameData(
                ptsUs = ptsUs,
                frameNumber = frameNumber,
                isKeyFrame = packet.isKeyFrame,
                naluData = naluBytes
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VideoFrameData) return false
        return ptsUs == other.ptsUs &&
                frameNumber == other.frameNumber &&
                isKeyFrame == other.isKeyFrame &&
                naluData.contentEquals(other.naluData)
    }

    override fun hashCode(): Int {
        var result = ptsUs.hashCode()
        result = 31 * result + frameNumber.hashCode()
        result = 31 * result + isKeyFrame.hashCode()
        result = 31 * result + naluData.contentHashCode()
        return result
    }
}

/**
 * Input event metadata for remote control over USB.
 */
data class RemoteInputEvent(
    val action: Int,
    val pointerId: Int,
    val normalizedX: Float, // 0.0f - 1.0f
    val normalizedY: Float, // 0.0f - 1.0f
    val pressure: Float = 1.0f,
    val timestampMs: Long = System.currentTimeMillis(),
    val sessionId: Int = 1
) {
    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(32)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(action)
        buffer.putInt(pointerId)
        buffer.putFloat(normalizedX)
        buffer.putFloat(normalizedY)
        buffer.putFloat(pressure)
        buffer.putLong(timestampMs)
        buffer.putInt(sessionId)
        return buffer.array()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): RemoteInputEvent? {
            if (bytes.size < 28) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val action = buffer.int
            val pointerId = buffer.int
            val normX = buffer.float
            val normY = buffer.float
            val pressure = buffer.float
            val timestamp = buffer.long
            val sessionId = if (buffer.remaining() >= 4) buffer.int else 1
            return RemoteInputEvent(
                action = action,
                pointerId = pointerId,
                normalizedX = normX,
                normalizedY = normY,
                pressure = pressure,
                timestampMs = timestamp,
                sessionId = sessionId
            )
        }
    }
}

/**
 * Wraps RemoteInputEvent into a UsbPacket for immediate transmission.
 */
fun RemoteInputEvent.toPacket(sequenceId: Int = 0): UsbPacket {
    return UsbPacket(
        type = UsbProtocol.TYPE_INPUT_EVENT,
        sequenceId = sequenceId,
        payload = this.toByteArray()
    )
}

/**
 * Control status payload exchanged between Host and Controller.
 */
data class ControlStatusPayload(
    val isPermissionGranted: Boolean,
    val isControlActive: Boolean
) {
    fun toByteArray(): ByteArray = byteArrayOf(
        if (isPermissionGranted) 1 else 0,
        if (isControlActive) 1 else 0
    )

    fun toPacket(sequenceId: Int = 0): UsbPacket {
        return UsbPacket(
            type = UsbProtocol.TYPE_CONTROL_STATUS,
            sequenceId = sequenceId,
            payload = this.toByteArray()
        )
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): ControlStatusPayload? {
            if (bytes.size < 2) return null
            return ControlStatusPayload(
                isPermissionGranted = bytes[0] == 1.toByte(),
                isControlActive = bytes[1] == 1.toByte()
            )
        }
    }
}

/**
 * Control command sent from Controller to Host.
 */
data class ControlCommandPayload(
    val command: Int // 1 = ENABLE, 2 = DISABLE
) {
    fun toByteArray(): ByteArray = byteArrayOf(command.toByte())

    fun toPacket(sequenceId: Int = 0): UsbPacket {
        return UsbPacket(
            type = UsbProtocol.TYPE_CONTROL_COMMAND,
            sequenceId = sequenceId,
            payload = this.toByteArray()
        )
    }

    companion object {
        const val CMD_ENABLE = 1
        const val CMD_DISABLE = 2

        fun fromByteArray(bytes: ByteArray): ControlCommandPayload? {
            if (bytes.isEmpty()) return null
            return ControlCommandPayload(bytes[0].toInt())
        }
    }
}

/**
 * Handshake payload metadata exchanged when phones connect.
 */
data class HandshakePayload(
    val role: UsbRole,
    val screenWidth: Int,
    val screenHeight: Int,
    val targetFps: Int,
    val deviceModel: String
) {
    fun toByteArray(): ByteArray {
        val modelBytes = deviceModel.toByteArray(Charsets.UTF_8)
        val buffer = ByteBuffer.allocate(17 + modelBytes.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put((if (role == UsbRole.HOST) 1 else 2).toByte())
        buffer.putInt(screenWidth)
        buffer.putInt(screenHeight)
        buffer.putInt(targetFps)
        buffer.putInt(modelBytes.size)
        buffer.put(modelBytes)
        return buffer.array()
    }

    companion object {
        fun fromByteArray(bytes: ByteArray): HandshakePayload? {
            if (bytes.size < 17) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val roleByte = buffer.get()
            val role = if (roleByte.toInt() == 1) UsbRole.HOST else UsbRole.CONTROLLER
            val width = buffer.int
            val height = buffer.int
            val fps = buffer.int
            val modelLen = buffer.int
            val model = if (modelLen > 0 && buffer.remaining() >= modelLen) {
                val strBytes = ByteArray(modelLen)
                buffer.get(strBytes)
                String(strBytes, Charsets.UTF_8)
            } else {
                "Unknown Android Device"
            }
            return HandshakePayload(role, width, height, fps, model)
        }
    }
}

/**
 * Serializes a UsbMessage for bidirectional test protocol.
 */
fun UsbMessage.toPacket(sequenceId: Int = 0): UsbPacket {
    val textBytes = text.toByteArray(Charsets.UTF_8)
    val buffer = ByteBuffer.allocate(1 + 8 + 8 + 4 + textBytes.size)
    buffer.order(ByteOrder.BIG_ENDIAN)
    buffer.put((if (senderRole == UsbRole.HOST) 1 else 2).toByte())
    buffer.putLong(timestampMs)
    buffer.putLong(sequenceNumber)
    buffer.putInt(textBytes.size)
    buffer.put(textBytes)

    return UsbPacket(
        type = UsbProtocol.TYPE_TEST_MESSAGE,
        sequenceId = sequenceId,
        payload = buffer.array()
    )
}

/**
 * Deserializes a UsbMessage from packet payload.
 */
fun parseUsbMessage(payload: ByteArray): UsbMessage? {
    if (payload.size < 21) return null // 1 + 8 + 8 + 4
    val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
    val roleByte = buffer.get()
    val role = if (roleByte.toInt() == 1) UsbRole.HOST else UsbRole.CONTROLLER
    val timestamp = buffer.long
    val seq = buffer.long
    val textLen = buffer.int

    if (textLen < 0 || buffer.remaining() < textLen) return null
    val textBytes = ByteArray(textLen)
    buffer.get(textBytes)
    val text = String(textBytes, Charsets.UTF_8)

    return UsbMessage(
        senderRole = role,
        text = text,
        timestampMs = timestamp,
        sequenceNumber = seq
    )
}
