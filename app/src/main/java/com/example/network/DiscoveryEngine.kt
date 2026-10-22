package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.model.PeerDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

class DiscoveryEngine(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _discoveredDevices = MutableStateFlow<List<PeerDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<PeerDevice>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isBroadcasting = MutableStateFlow(false)
    val isBroadcasting: StateFlow<Boolean> = _isBroadcasting.asStateFlow()

    private var broadcastJob: Job? = null
    private var udpListenJob: Job? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var udpSocket: DatagramSocket? = null

    companion object {
        const val SERVICE_TYPE = "_infinitespeed._tcp."
        const val UDP_DISCOVERY_PORT = 8988
        const val DEFAULT_HTTP_PORT = 8990
    }

    /**
     * Start advertising this device as a receiver (both NSD and UDP beacon)
     */
    fun startBroadcasting(deviceName: String, deviceId: String, port: Int = DEFAULT_HTTP_PORT) {
        if (_isBroadcasting.value) return
        _isBroadcasting.value = true

        // 1. Android NSD Registration
        try {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "InfiniteSpeed-$deviceName"
                serviceType = SERVICE_TYPE
                setPort(port)
            }

            nsdRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
        } catch (_: Exception) {}

        // 2. UDP Beacon Broadcast
        broadcastJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                val beaconMessage = "INFINITE_SPEED_BEACON:$deviceName:$port:$deviceId"
                val sendData = beaconMessage.toByteArray()

                while (isActive) {
                    val broadcastIp = NetworkUtils.getBroadcastAddress()
                    try {
                        val packet = DatagramPacket(
                            sendData,
                            sendData.size,
                            InetAddress.getByName(broadcastIp),
                            UDP_DISCOVERY_PORT
                        )
                        socket.send(packet)
                    } catch (_: Exception) {}
                    delay(1500)
                }
            } catch (_: Exception) {
            } finally {
                socket?.close()
            }
        }
    }

    fun stopBroadcasting() {
        _isBroadcasting.value = false
        broadcastJob?.cancel()
        broadcastJob = null

        try {
            nsdRegistrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        nsdRegistrationListener = null
    }

    /**
     * Start scanning for nearby receiver devices
     */
    fun startDiscovery() {
        if (_isScanning.value) return
        _isScanning.value = true
        _discoveredDevices.value = emptyList()

        // 1. Listen for UDP beacons
        udpListenJob = scope.launch(Dispatchers.IO) {
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                }
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive) {
                    try {
                        udpSocket?.receive(packet)
                        val message = String(packet.data, 0, packet.length).trim()
                        val senderIp = packet.address.hostAddress ?: continue

                        // Ignore our own broadcast
                        if (senderIp == NetworkUtils.getLocalIpAddress()) continue

                        if (message.startsWith("INFINITE_SPEED_BEACON:")) {
                            val parts = message.split(":")
                            if (parts.size >= 4) {
                                val name = parts[1]
                                val port = parts[2].toIntOrNull() ?: DEFAULT_HTTP_PORT
                                val id = parts[3]

                                addOrUpdateDevice(
                                    PeerDevice(
                                        id = id,
                                        name = name,
                                        ipAddress = senderIp,
                                        port = port,
                                        connectionType = "Wi-Fi Direct / LAN",
                                        lastSeen = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            } finally {
                udpSocket?.close()
                udpSocket = null
            }
        }

        // 2. NSD Discovery
        try {
            nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.contains("infinitespeed")) {
                        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                                val host = resolvedInfo.host?.hostAddress ?: return
                                if (host == NetworkUtils.getLocalIpAddress()) return
                                val port = resolvedInfo.port
                                val name = resolvedInfo.serviceName.removePrefix("InfiniteSpeed-")
                                addOrUpdateDevice(
                                    PeerDevice(
                                        id = "nsd_$host",
                                        name = name,
                                        ipAddress = host,
                                        port = port,
                                        connectionType = "NSD Local",
                                        lastSeen = System.currentTimeMillis()
                                    )
                                )
                            }
                        })
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            }

            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, nsdDiscoveryListener)
        } catch (_: Exception) {}
    }

    fun stopDiscovery() {
        _isScanning.value = false
        udpListenJob?.cancel()
        udpListenJob = null
        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null

        try {
            nsdDiscoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        nsdDiscoveryListener = null
    }

    private fun addOrUpdateDevice(peer: PeerDevice) {
        val current = _discoveredDevices.value.toMutableList()
        val index = current.indexOfFirst { it.ipAddress == peer.ipAddress }
        if (index >= 0) {
            current[index] = peer
        } else {
            current.add(peer)
        }
        _discoveredDevices.value = current
    }

    fun addManualDevice(ip: String, port: Int = DEFAULT_HTTP_PORT, name: String = "Manual Peer") {
        addOrUpdateDevice(
            PeerDevice(
                id = "manual_$ip",
                name = name,
                ipAddress = ip,
                port = port,
                connectionType = "Direct IP",
                lastSeen = System.currentTimeMillis()
            )
        )
    }
}
