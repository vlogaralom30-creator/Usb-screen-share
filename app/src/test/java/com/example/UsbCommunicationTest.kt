package com.example

import com.example.usb.model.HandshakePayload
import com.example.usb.model.StreamConfig
import com.example.usb.model.UsbConnectionState
import com.example.usb.model.UsbMessage
import com.example.usb.model.UsbPacket
import com.example.usb.model.UsbProtocol
import com.example.usb.model.UsbRole
import com.example.usb.model.VideoFrameData
import com.example.usb.model.parseUsbMessage
import com.example.usb.model.toPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class UsbCommunicationTest {

    @Test
    fun `test packet binary serialization and deserialization`() {
        val payload = "Hello Phone 2 from Phone 1 over physical USB wire".toByteArray(Charsets.UTF_8)
        val originalPacket = UsbPacket(
            type = UsbProtocol.TYPE_TEST_MESSAGE,
            flags = UsbProtocol.FLAG_KEY_FRAME,
            sequenceId = 42,
            payload = payload
        )

        val wireBytes = originalPacket.toByteArray()

        // Validate Magic Header "USBS"
        assertEquals(0x55.toByte(), wireBytes[0])
        assertEquals(0x53.toByte(), wireBytes[1])
        assertEquals(0x42.toByte(), wireBytes[2])
        assertEquals(0x53.toByte(), wireBytes[3])

        // Parse back
        val buffer = ByteBuffer.wrap(wireBytes)
        val parsedPacket = UsbPacket.fromByteBuffer(buffer)

        assertNotNull(parsedPacket)
        assertEquals(originalPacket.type, parsedPacket!!.type)
        assertEquals(originalPacket.flags, parsedPacket.flags)
        assertEquals(originalPacket.sequenceId, parsedPacket.sequenceId)
        assertArrayEquals(originalPacket.payload, parsedPacket.payload)
    }

    @Test
    fun `test bidirectional UsbMessage serialization and parsing`() {
        val originalMsg = UsbMessage(
            senderRole = UsbRole.HOST,
            text = "Direct USB Link active: Packet #100",
            timestampMs = 1700000000000L,
            sequenceNumber = 100
        )

        val packet = originalMsg.toPacket(sequenceId = 100)
        assertEquals(UsbProtocol.TYPE_TEST_MESSAGE, packet.type)
        assertEquals(100, packet.sequenceId)

        val parsedMsg = parseUsbMessage(packet.payload)
        assertNotNull(parsedMsg)
        assertEquals(UsbRole.HOST, parsedMsg!!.senderRole)
        assertEquals("Direct USB Link active: Packet #100", parsedMsg.text)
        assertEquals(1700000000000L, parsedMsg.timestampMs)
        assertEquals(100L, parsedMsg.sequenceNumber)
    }

    @Test
    fun `test controller to host test message parsing`() {
        val controllerMsg = UsbMessage(
            senderRole = UsbRole.CONTROLLER,
            text = "Controller Touch Input Ack",
            timestampMs = 1700000005000L,
            sequenceNumber = 205
        )

        val packet = controllerMsg.toPacket(sequenceId = 205)
        val parsed = parseUsbMessage(packet.payload)

        assertNotNull(parsed)
        assertEquals(UsbRole.CONTROLLER, parsed!!.senderRole)
        assertEquals("Controller Touch Input Ack", parsed.text)
        assertEquals(205L, parsed.sequenceNumber)
    }

    @Test
    fun `test handshake payload encoding and decoding`() {
        val handshake = HandshakePayload(
            role = UsbRole.HOST,
            screenWidth = 1080,
            screenHeight = 2400,
            targetFps = 60,
            deviceModel = "Pixel 8 Pro"
        )

        val bytes = handshake.toByteArray()
        val parsed = HandshakePayload.fromByteArray(bytes)

        assertNotNull(parsed)
        assertEquals(UsbRole.HOST, parsed!!.role)
        assertEquals(1080, parsed.screenWidth)
        assertEquals(2400, parsed.screenHeight)
        assertEquals(60, parsed.targetFps)
        assertEquals("Pixel 8 Pro", parsed.deviceModel)
    }

    @Test
    fun `test stream config serialization and deserialization`() {
        val spsPpsFake = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f, 0x00, 0x00, 0x00, 0x01, 0x68.toByte())
        val config = StreamConfig(
            width = 1080,
            height = 2400,
            fps = 60,
            bitrate = 6_000_000,
            spsPps = spsPpsFake
        )

        val serialized = config.toByteArray()
        val parsed = StreamConfig.fromByteArray(serialized)

        assertNotNull(parsed)
        assertEquals(1080, parsed!!.width)
        assertEquals(2400, parsed.height)
        assertEquals(60, parsed.fps)
        assertArrayEquals(spsPpsFake, parsed.spsPps)
    }

    @Test
    fun `test video frame data packet wrapping and extraction`() {
        val fakeNalu = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x11, 0x22, 0x33, 0x44)
        val frameData = VideoFrameData(
            ptsUs = 123456789L,
            frameNumber = 555L,
            isKeyFrame = true,
            naluData = fakeNalu
        )

        val packet = frameData.toPacket(sequenceId = 555)
        assertEquals(UsbProtocol.TYPE_VIDEO_FRAME, packet.type)
        assertTrue(packet.isKeyFrame)

        val parsedFrame = VideoFrameData.fromPacket(packet)
        assertNotNull(parsedFrame)
        assertEquals(123456789L, parsedFrame!!.ptsUs)
        assertEquals(555L, parsedFrame.frameNumber)
        assertTrue(parsedFrame.isKeyFrame)
        assertArrayEquals(fakeNalu, parsedFrame.naluData)
    }

    @Test
    fun `test non-keyframe video frame flags`() {
        val fakeNalu = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x41, 0x55)
        val frameData = VideoFrameData(
            ptsUs = 987654321L,
            frameNumber = 556L,
            isKeyFrame = false,
            naluData = fakeNalu
        )

        val packet = frameData.toPacket(sequenceId = 556)
        assertFalse(packet.isKeyFrame)

        val parsedFrame = VideoFrameData.fromPacket(packet)
        assertNotNull(parsedFrame)
        assertFalse(parsedFrame!!.isKeyFrame)
    }

    @Test
    fun `test resynchronization on noise bytes before valid packet`() {
        val validPacket = UsbPacket(
            type = UsbProtocol.TYPE_PING,
            sequenceId = 1,
            payload = "PING".toByteArray()
        )
        val validBytes = validPacket.toByteArray()

        // Prepend 5 bytes of random garbage noise
        val noiseBytes = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x90.toByte())
        val streamBytes = noiseBytes + validBytes

        val buffer = ByteBuffer.wrap(streamBytes)

        // While seeking, invalid magic bytes are skipped 1 by 1
        var recoveredPacket: UsbPacket? = null
        while (buffer.hasRemaining()) {
            val pkt = UsbPacket.fromByteBuffer(buffer)
            if (pkt != null) {
                recoveredPacket = pkt
                break
            }
        }

        assertNotNull("Should recover valid packet despite leading noise", recoveredPacket)
        assertEquals(UsbProtocol.TYPE_PING, recoveredPacket!!.type)
        assertEquals(1, recoveredPacket.sequenceId)
    }

    @Test
    fun `test all required connection states exist and represent lifecycle`() {
        val disconnected: UsbConnectionState = UsbConnectionState.Disconnected("Cable unplugged")
        val detected: UsbConnectionState = UsbConnectionState.UsbDetected("Pixel 7", false)
        val permRequired: UsbConnectionState = UsbConnectionState.PermissionRequired("Pixel 7", false)
        val connecting: UsbConnectionState = UsbConnectionState.Connecting(UsbRole.HOST, "Claiming endpoints")
        val connected: UsbConnectionState = UsbConnectionState.Connected(
            role = UsbRole.HOST,
            modeName = "USB Host Mode (Bulk Endpoints)",
            peerInfo = "Pixel 7 (IN: 130, OUT: 1)"
        )
        val error: UsbConnectionState = UsbConnectionState.ConnectionError("ERR_IO", "USB endpoint stall", true)

        assertTrue(disconnected is UsbConnectionState.Disconnected)
        assertTrue(detected is UsbConnectionState.UsbDetected)
        assertTrue(permRequired is UsbConnectionState.PermissionRequired)
        assertTrue(connecting is UsbConnectionState.Connecting)
        assertTrue(connected is UsbConnectionState.Connected)
        assertTrue(error is UsbConnectionState.ConnectionError)
    }

    @Test
    fun `test RemoteInputEvent serialization and deserialization`() {
        val originalEvent = com.example.usb.model.RemoteInputEvent(
            action = UsbProtocol.INPUT_ACTION_MOVE,
            pointerId = 2,
            normalizedX = 0.354f,
            normalizedY = 0.789f,
            pressure = 0.95f,
            timestampMs = 1711200000000L,
            sessionId = 42
        )

        val packet = originalEvent.toPacket(sequenceId = 88)
        assertEquals(UsbProtocol.TYPE_INPUT_EVENT, packet.type)
        assertEquals(88, packet.sequenceId)

        val parsed = com.example.usb.model.RemoteInputEvent.fromByteArray(packet.payload)
        assertNotNull(parsed)
        assertEquals(UsbProtocol.INPUT_ACTION_MOVE, parsed!!.action)
        assertEquals(2, parsed.pointerId)
        assertEquals(0.354f, parsed.normalizedX, 0.0001f)
        assertEquals(0.789f, parsed.normalizedY, 0.0001f)
        assertEquals(0.95f, parsed.pressure, 0.0001f)
        assertEquals(1711200000000L, parsed.timestampMs)
        assertEquals(42, parsed.sessionId)
    }

    @Test
    fun `test CoordinateMapper aspect ratio mapping and bounds clamping`() {
        // Stream: 1080x1920 (aspect 9:16 = 0.5625)
        // Viewport: 1080x2400 (aspect 0.45) -> Letterbox on top and bottom
        val bounds = com.example.usb.model.ViewportBounds(
            viewportWidth = 1080f,
            viewportHeight = 2400f,
            streamWidth = 1080f,
            streamHeight = 1920f
        )

        // Center of viewport should map to exactly (0.5, 0.5)
        val centerCoords = bounds.toNormalized(540f, 1200f)
        assertEquals(0.5f, centerCoords.first, 0.001f)
        assertEquals(0.5f, centerCoords.second, 0.001f)

        // Touches outside the video stream area must be clamped to [0.0, 1.0]
        val outCoords = bounds.toNormalized(-50f, 3000f)
        assertEquals(0.0f, outCoords.first, 0.001f)
        assertEquals(1.0f, outCoords.second, 0.001f)

        // Denormalize on Host side (Host: 1080 x 2400)
        val hostCoords = com.example.usb.model.CoordinateMapper.toHostPixel(
            normX = 0.5f,
            normY = 0.25f,
            hostWidth = 1080,
            hostHeight = 2400
        )
        assertEquals(540f, hostCoords.first, 0.01f)
        assertEquals(600f, hostCoords.second, 0.01f)
    }

    @Test
    fun `test ControlStatusPayload serialization and deserialization`() {
        val payload = com.example.usb.model.ControlStatusPayload(
            isPermissionGranted = true,
            isControlActive = true
        )
        val packet = payload.toPacket(10)
        assertEquals(UsbProtocol.TYPE_CONTROL_STATUS, packet.type)

        val parsed = com.example.usb.model.ControlStatusPayload.fromByteArray(packet.payload)
        assertNotNull(parsed)
        assertTrue(parsed!!.isPermissionGranted)
        assertTrue(parsed.isControlActive)
    }

    @Test
    fun `test ControlCommandPayload serialization and deserialization`() {
        val enableCmd = com.example.usb.model.ControlCommandPayload(
            command = com.example.usb.model.ControlCommandPayload.CMD_ENABLE
        )
        val packet = enableCmd.toPacket(11)
        assertEquals(UsbProtocol.TYPE_CONTROL_COMMAND, packet.type)

        val parsed = com.example.usb.model.ControlCommandPayload.fromByteArray(packet.payload)
        assertNotNull(parsed)
        assertEquals(com.example.usb.model.ControlCommandPayload.CMD_ENABLE, parsed!!.command)
    }
}
