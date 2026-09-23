package com.example.usb.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.os.Build
import android.util.Log
import com.example.usb.model.UsbPacket
import com.example.usb.model.UsbProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-speed direct Cable Network / USB Tethering Socket Transport.
 *
 * Provides instant, plug-and-play screen streaming and remote control
 * between Samsung, Poco, Xiaomi, and all Android devices over Type-C to Type-C
 * or USB Tethering without requiring root or custom kernel modules.
 */
class UsbSocketTransport private constructor(
    private val socket: Socket,
    override val modeName: String = "Direct USB Cable Link (High-Speed)"
) : UsbTransport {

    companion object {
        private const val TAG = "UsbSocketTransport"
        const val DEFAULT_STREAM_PORT = 8889
        const val DISCOVERY_BEACON_PORT = 8888
        private const val READ_BUFFER_SIZE = 64 * 1024
        private const val ACCUMULATION_CAPACITY = 1024 * 1024 // 1 MB buffer

        /**
         * Discovers all active local IPv4 addresses (e.g. rndis0, usb0, eth0, wlan0).
         */
        fun getAvailableNetworkIps(): List<Pair<String, String>> {
            val result = mutableListOf<Pair<String, String>>()
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
                val list = java.util.Collections.list(ifaces)
                for (iface in list) {
                    if (!iface.isUp || iface.isLoopback) continue
                    val addresses = java.util.Collections.list(iface.inetAddresses)
                    for (addr in addresses) {
                        if (!addr.isLoopbackAddress && addr.hostAddress?.contains(".") == true) {
                            val ip = addr.hostAddress ?: continue
                            result.add(Pair(iface.name, ip))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error enumerating network interfaces: ${e.message}")
            }
            return result
        }

        /**
         * Detects the best local IP candidate, prioritizing USB/RNDIS interfaces.
         */
        fun getBestLocalIp(): String? {
            val ips = getAvailableNetworkIps()
            // 1. Check USB/Tethering specific interfaces
            val usbIface = ips.firstOrNull { (name, _) ->
                name.startsWith("rndis") || name.startsWith("usb") || name.startsWith("eth")
            }
            if (usbIface != null) return usbIface.second

            // 2. Check hotspot or local LAN
            val fallback = ips.firstOrNull { (_, ip) ->
                ip.startsWith("192.168.42.") || ip.startsWith("192.168.43.") || ip.startsWith("192.168.")
            }
            return fallback?.second ?: ips.firstOrNull()?.second
        }

        /**
         * Creates a Host/Server transport that waits for incoming connection from Controller.
         */
        suspend fun createServerTransport(
            port: Int = DEFAULT_STREAM_PORT,
            onListening: (String) -> Unit = {}
        ): Result<UsbSocketTransport> = withContext(Dispatchers.IO) {
            var serverSocket: ServerSocket? = null
            var beaconJob: Job? = null
            val beaconScope = CoroutineScope(Dispatchers.IO)

            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }

                val localIp = getBestLocalIp() ?: "0.0.0.0"
                onListening(localIp)
                Log.i(TAG, "Server listening on $localIp:$port")

                // Start UDP discovery beacon so Controller can find Host instantly
                beaconJob = beaconScope.launch {
                    var ds: DatagramSocket? = null
                    try {
                        ds = DatagramSocket()
                        ds.broadcast = true
                        val beaconMsg = "USB_SCREEN_HOST:$port:${Build.MANUFACTURER} ${Build.MODEL}".toByteArray()
                        val broadcastAddr = InetAddress.getByName("255.255.255.255")
                        val dPacket = DatagramPacket(beaconMsg, beaconMsg.size, broadcastAddr, DISCOVERY_BEACON_PORT)

                        while (isActive) {
                            try {
                                ds.send(dPacket)
                            } catch (e: Exception) {
                                // Ignore intermittent broadcast issues
                            }
                            delay(1200)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "UDP Beacon broadcaster failed: ${e.message}")
                    } finally {
                        ds?.close()
                    }
                }

                // Accept client
                val clientSocket = serverSocket.accept().apply {
                    tcpNoDelay = true
                    receiveBufferSize = 512 * 1024
                    sendBufferSize = 512 * 1024
                    keepAlive = true
                }

                beaconJob.cancel()
                serverSocket.close()

                val transport = UsbSocketTransport(
                    socket = clientSocket,
                    modeName = "USB Cable Link (Host Server - ${clientSocket.inetAddress.hostAddress})"
                )
                Result.success(transport)
            } catch (e: Exception) {
                beaconJob?.cancel()
                try { serverSocket?.close() } catch (_: Exception) {}
                Log.e(TAG, "Failed to create server transport", e)
                Result.failure(e)
            }
        }

        /**
         * Creates a Controller/Client transport by auto-discovering Host or connecting to candidate IPs.
         */
        suspend fun createClientTransport(
            targetHost: String? = null,
            port: Int = DEFAULT_STREAM_PORT,
            timeoutMs: Int = 10000
        ): Result<UsbSocketTransport> = withContext(Dispatchers.IO) {
            val candidateIps = mutableSetOf<String>()

            if (!targetHost.isNullOrBlank()) {
                candidateIps.add(targetHost.trim())
            }

            // Add standard Android USB tethering gateway candidates
            candidateIps.add("192.168.42.129")
            candidateIps.add("192.168.42.1")
            candidateIps.add("192.168.43.1")
            candidateIps.add("192.168.44.1")
            candidateIps.add("192.168.49.1")
            candidateIps.add("192.168.1.1")
            candidateIps.add("127.0.0.1")

            // Try UDP discovery for 1.5 seconds if target not explicitly provided
            if (targetHost.isNullOrBlank()) {
                val discoveredIp = discoverHostViaUdp(1500)
                if (discoveredIp != null) {
                    candidateIps.add(discoveredIp)
                }
            }

            val startTime = System.currentTimeMillis()
            var lastException: Exception? = null

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                for (ip in candidateIps) {
                    try {
                        val socket = Socket()
                        socket.tcpNoDelay = true
                        socket.receiveBufferSize = 512 * 1024
                        socket.sendBufferSize = 512 * 1024
                        socket.keepAlive = true
                        socket.connect(InetSocketAddress(ip, port), 800)

                        Log.i(TAG, "Successfully connected to Host at $ip:$port")
                        val transport = UsbSocketTransport(
                            socket = socket,
                            modeName = "USB Cable Link (Controller Client -> $ip)"
                        )
                        return@withContext Result.success(transport)
                    } catch (e: Exception) {
                        lastException = e
                    }
                }
                delay(500)
            }

            Result.failure(lastException ?: IllegalStateException("Could not reach Host on USB cable link. Verify USB Tethering is enabled."))
        }

        private suspend fun discoverHostViaUdp(timeoutMs: Int): String? = withContext(Dispatchers.IO) {
            var ds: DatagramSocket? = null
            try {
                ds = DatagramSocket(DISCOVERY_BEACON_PORT).apply {
                    soTimeout = timeoutMs
                    reuseAddress = true
                }
                val buffer = ByteArray(512)
                val packet = DatagramPacket(buffer, buffer.size)
                ds.receive(packet)
                val text = String(packet.data, 0, packet.length)
                if (text.startsWith("USB_SCREEN_HOST:")) {
                    val senderIp = packet.address.hostAddress
                    Log.i(TAG, "Discovered Host beacon from $senderIp: $text")
                    return@withContext senderIp
                }
            } catch (e: Exception) {
                // Timeout or broadcast not available
            } finally {
                ds?.close()
            }
            return@withContext null
        }
    }

    override var onDisconnect: (() -> Unit)? = null

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var readJob: Job? = null

    private val _incomingPackets = MutableSharedFlow<UsbPacket>(extraBufferCapacity = 128)
    private val _stats = MutableStateFlow(TransportStats(isLinkActive = true))

    override val isConnected: Boolean
        get() = _stats.value.isLinkActive && socket.isConnected && !socket.isClosed

    override val stats: StateFlow<TransportStats> = _stats.asStateFlow()

    private val inputStream: InputStream = BufferedInputStream(socket.getInputStream(), READ_BUFFER_SIZE)
    private val outputStream: OutputStream = BufferedOutputStream(socket.getOutputStream(), READ_BUFFER_SIZE)

    init {
        startReading()
    }

    private fun startReading() {
        readJob = scope.launch {
            val rawBuffer = ByteArray(READ_BUFFER_SIZE)
            val accumulationBuffer = ByteBuffer.allocate(ACCUMULATION_CAPACITY).order(ByteOrder.BIG_ENDIAN)

            try {
                while (isActive && isConnected) {
                    val bytesRead = withContext(Dispatchers.IO) {
                        try {
                            inputStream.read(rawBuffer)
                        } catch (e: Exception) {
                            -1
                        }
                    }

                    if (bytesRead <= 0) {
                        Log.d(TAG, "Socket end of stream reached (read: $bytesRead)")
                        break
                    }

                    if (accumulationBuffer.remaining() < bytesRead) {
                        accumulationBuffer.compact()
                        if (accumulationBuffer.remaining() < bytesRead) {
                            Log.w(TAG, "Socket buffer overflow; clearing accumulation buffer")
                            accumulationBuffer.clear()
                        }
                    }

                    accumulationBuffer.put(rawBuffer, 0, bytesRead)
                    accumulationBuffer.flip()

                    while (accumulationBuffer.hasRemaining()) {
                        val packet = UsbPacket.fromByteBuffer(accumulationBuffer) ?: break
                        _stats.update {
                            it.copy(
                                packetsReceived = it.packetsReceived + 1,
                                bytesReceived = it.bytesReceived + packet.payload.size + UsbProtocol.HEADER_SIZE
                            )
                        }
                        _incomingPackets.emit(packet)
                    }
                    accumulationBuffer.compact()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket read exception: ${e.message}")
            } finally {
                close()
            }
        }
    }

    override suspend fun sendPacket(packet: UsbPacket): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConnected) {
            return@withContext Result.failure(IllegalStateException("Socket link is closed"))
        }

        try {
            val bytes = packet.toByteArray()
            outputStream.write(bytes)
            outputStream.flush()

            _stats.update {
                it.copy(
                    packetsSent = it.packetsSent + 1,
                    bytesSent = it.bytesSent + bytes.size
                )
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send packet over socket", e)
            close()
            Result.failure(e)
        }
    }

    override fun receivePackets(): Flow<UsbPacket> = _incomingPackets.asSharedFlow()

    override fun close() {
        if (_stats.value.isLinkActive) {
            _stats.update { it.copy(isLinkActive = false) }
            try { readJob?.cancel() } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
            try { outputStream.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            onDisconnect?.invoke()
            Log.i(TAG, "UsbSocketTransport closed")
        }
    }
}
