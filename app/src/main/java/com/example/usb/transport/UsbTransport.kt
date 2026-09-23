package com.example.usb.transport

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.usb.model.UsbPacket
import com.example.usb.model.UsbProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Transport statistics for live latency and throughput telemetry.
 */
data class TransportStats(
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val latencyMs: Long = 0,
    val isLinkActive: Boolean = false
)

/**
 * General interface for phone-to-phone bidirectional USB data transport.
 */
interface UsbTransport {
    val isConnected: Boolean
    val modeName: String
    val stats: StateFlow<TransportStats>
    var onDisconnect: (() -> Unit)?

    suspend fun sendPacket(packet: UsbPacket): Result<Unit>
    fun receivePackets(): Flow<UsbPacket>
    fun close()
}

/**
 * High-performance USB Bulk Endpoints implementation (for the phone operating as USB Host).
 * Performs direct non-blocking USB bulk transfers using Android UsbDeviceConnection.
 */
class UsbBulkTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val endpointIn: UsbEndpoint?,
    private val endpointOut: UsbEndpoint?
) : UsbTransport {

    companion object {
        private const val TAG = "UsbBulkTransport"
        private const val READ_BUFFER_SIZE = 64 * 1024 // 64 KB read buffer
        private const val TIMEOUT_MS = 2000
    }

    override val modeName: String = "USB Host Mode (Bulk Endpoints)"
    override var onDisconnect: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var readJob: Job? = null

    private val _incomingPackets = MutableSharedFlow<UsbPacket>(extraBufferCapacity = 64)
    private val _stats = MutableStateFlow(TransportStats(isLinkActive = true))

    override val isConnected: Boolean
        get() = _stats.value.isLinkActive

    override val stats: StateFlow<TransportStats> = _stats.asStateFlow()

    init {
        startReading()
    }

    private fun startReading() {
        if (endpointIn == null) {
            Log.w(TAG, "No BULK IN endpoint available; receive stream disabled")
            return
        }

        readJob = scope.launch {
            val rawBuffer = ByteArray(READ_BUFFER_SIZE)
            val accumulationBuffer = ByteBuffer.allocate(512 * 1024).order(ByteOrder.BIG_ENDIAN)

            try {
                while (isActive && isConnected) {
                    val bytesRead = connection.bulkTransfer(endpointIn, rawBuffer, rawBuffer.size, TIMEOUT_MS)
                    if (bytesRead > 0) {
                        _stats.update {
                            it.copy(bytesReceived = it.bytesReceived + bytesRead)
                        }

                        // Compact or reset accumulation buffer if needed
                        if (accumulationBuffer.remaining() < bytesRead) {
                            accumulationBuffer.compact()
                        }
                        accumulationBuffer.put(rawBuffer, 0, bytesRead)
                        accumulationBuffer.flip()

                        // Parse available packets
                        while (true) {
                            val packet = UsbPacket.fromByteBuffer(accumulationBuffer) ?: break
                            _stats.update {
                                it.copy(packetsReceived = it.packetsReceived + 1)
                            }
                            _incomingPackets.emit(packet)
                        }

                        // Compact remaining unparsed bytes to beginning
                        accumulationBuffer.compact()
                    } else if (bytesRead < 0 && bytesRead != -2) {
                        // -1 typically indicates endpoint stall or physical disconnect
                        Log.w(TAG, "USB bulk transfer read returned code: $bytesRead; checking link")
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Error reading from USB bulk endpoint", e)
                }
            } finally {
                _stats.update { it.copy(isLinkActive = false) }
                onDisconnect?.invoke()
            }
        }
    }

    override suspend fun sendPacket(packet: UsbPacket): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConnected || endpointOut == null) {
            return@withContext Result.failure(IllegalStateException("USB bulk transport not connected or no OUT endpoint"))
        }

        try {
            val bytes = packet.toByteArray()
            var offset = 0
            val chunkSize = 16384 // 16 KB chunk for USB bulk stability

            while (offset < bytes.size) {
                val len = minOf(chunkSize, bytes.size - offset)
                val written = connection.bulkTransfer(endpointOut, bytes, offset, len, TIMEOUT_MS)
                if (written < 0) {
                    return@withContext Result.failure(IllegalStateException("Bulk transfer write error: $written"))
                }
                offset += written
            }

            _stats.update {
                it.copy(
                    packetsSent = it.packetsSent + 1,
                    bytesSent = it.bytesSent + bytes.size
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send packet type ${packet.type}", e)
            Result.failure(e)
        }
    }

    override fun receivePackets(): Flow<UsbPacket> = _incomingPackets.asSharedFlow()

    override fun close() {
        _stats.update { it.copy(isLinkActive = false) }
        readJob?.cancel()
        try {
            connection.releaseInterface(usbInterface)
            connection.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing USB resources", e)
        }
    }
}

/**
 * Android Open Accessory (AOA 2.0) USB transport implementation (for the phone operating as USB Accessory).
 * Reads and writes packets using ParcelFileDescriptor streams.
 */
class UsbAccessoryTransport(
    private val pfd: ParcelFileDescriptor
) : UsbTransport {

    companion object {
        private const val TAG = "UsbAccessoryTransport"
        private const val READ_BUFFER_SIZE = 64 * 1024
    }

    override val modeName: String = "USB Accessory Mode (AOA 2.0)"
    override var onDisconnect: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var readJob: Job? = null

    private val inStream = FileInputStream(pfd.fileDescriptor)
    private val outStream = FileOutputStream(pfd.fileDescriptor)

    private val _incomingPackets = MutableSharedFlow<UsbPacket>(extraBufferCapacity = 64)
    private val _stats = MutableStateFlow(TransportStats(isLinkActive = true))

    override val isConnected: Boolean
        get() = _stats.value.isLinkActive

    override val stats: StateFlow<TransportStats> = _stats.asStateFlow()

    init {
        startReading()
    }

    private fun startReading() {
        readJob = scope.launch {
            val rawBuffer = ByteArray(READ_BUFFER_SIZE)
            val accumulationBuffer = ByteBuffer.allocate(512 * 1024).order(ByteOrder.BIG_ENDIAN)

            try {
                while (isActive && isConnected) {
                    val bytesRead = inStream.read(rawBuffer)
                    if (bytesRead > 0) {
                        _stats.update {
                            it.copy(bytesReceived = it.bytesReceived + bytesRead)
                        }

                        if (accumulationBuffer.remaining() < bytesRead) {
                            accumulationBuffer.compact()
                        }
                        accumulationBuffer.put(rawBuffer, 0, bytesRead)
                        accumulationBuffer.flip()

                        while (true) {
                            val packet = UsbPacket.fromByteBuffer(accumulationBuffer) ?: break
                            _stats.update {
                                it.copy(packetsReceived = it.packetsReceived + 1)
                            }
                            _incomingPackets.emit(packet)
                        }

                        accumulationBuffer.compact()
                    } else if (bytesRead < 0) {
                        // EOF reached
                        Log.i(TAG, "USB Accessory stream reached EOF")
                        break
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Error reading from USB accessory stream", e)
                }
            } finally {
                _stats.update { it.copy(isLinkActive = false) }
                onDisconnect?.invoke()
            }
        }
    }

    override suspend fun sendPacket(packet: UsbPacket): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConnected) {
            return@withContext Result.failure(IllegalStateException("USB accessory transport not connected"))
        }

        try {
            val bytes = packet.toByteArray()
            outStream.write(bytes)
            outStream.flush()

            _stats.update {
                it.copy(
                    packetsSent = it.packetsSent + 1,
                    bytesSent = it.bytesSent + bytes.size
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write packet to USB accessory stream", e)
            Result.failure(e)
        }
    }

    override fun receivePackets(): Flow<UsbPacket> = _incomingPackets.asSharedFlow()

    override fun close() {
        _stats.update { it.copy(isLinkActive = false) }
        readJob?.cancel()
        try {
            inStream.close()
        } catch (ignored: Exception) {}
        try {
            outStream.close()
        } catch (ignored: Exception) {}
        try {
            pfd.close()
        } catch (ignored: Exception) {}
    }
}
