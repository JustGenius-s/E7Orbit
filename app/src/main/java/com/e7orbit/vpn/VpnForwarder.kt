package com.e7orbit.vpn

import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * VPN tun 主循环:读 IP 包,分发到 TCP 连接级代理或 UDP 无状态转发。
 *
 * 游戏/系统所有流量经 0.0.0.0/0 路由进 tun,这里为每个 TCP 连接建立
 * [TcpRelay](伪连接 + 真实 socket 双通道),UDP 则建立 [UdpRelay]。
 */
class VpnForwarder(
    private val input: FileInputStream,
    private val output: FileOutputStream,
    private val protectTcp: (Socket) -> Unit,
    private val protectUdp: (DatagramSocket) -> Unit,
    private val onGearPayload: (
        connectionId: Long,
        serverPort: Int,
        direction: PayloadDirection,
        data: ByteArray,
    ) -> Unit,
    private val onLog: (String) -> Unit = {},
    private val onStats: ((packets: Long, bytes: Long) -> Unit)? = null,
) {
    private val writeLock = Any()
    private val tcpConnections = ConcurrentHashMap<ConnectionKey, TcpRelay>()
    private val udpRelays = ConcurrentHashMap<UdpKey, UdpRelay>()
    @Volatile
    private var running = true
    private var nextConnectionId = 1L
    private var packetsTotal = 0L
    private var bytesTotal = 0L

    fun run() {
        startReaper()
        val buffer = ByteArray(MAX_PACKET_SIZE)
        try {
            while (running) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                packetsTotal += 1
                bytesTotal += read
                if (packetsTotal % STATS_EVERY == 0L) {
                    onStats?.invoke(packetsTotal, bytesTotal)
                }
                dispatch(buffer.copyOf(read))
            }
        } finally {
            onStats?.invoke(packetsTotal, bytesTotal)
            closeAll()
        }
    }

    private fun dispatch(packet: ByteArray) {
        val ip = IpHeader.parse(packet) ?: return
        when (ip.protocol) {
            PROTO_TCP -> handleTcp(ip, packet)
            PROTO_UDP -> handleUdp(ip, packet)
        }
    }

    private fun handleTcp(ip: IpHeader, packet: ByteArray) {
        val segment = TcpSegment.parse(ip, packet) ?: return
        val key = ConnectionKey(
            srcIp = ip.source,
            srcPort = segment.sourcePort,
            dstIp = ip.destination,
            dstPort = segment.destinationPort,
        )
        val relay = tcpConnections[key]
        if (relay == null) {
            // 连接数上限保护:超过限制时丢弃新连接,让对端重试,防止 OOM
            if (tcpConnections.size >= MAX_TCP_CONNECTIONS) {
                onLog("tcp connection limit reached (${tcpConnections.size}), dropping new connection")
                return
            }
            val created = TcpRelay(
                connectionId = nextConnectionId++,
                gameIp = ip.source,
                gamePort = segment.sourcePort,
                serverIp = ip.destination,
                serverPort = segment.destinationPort,
                writeTun = ::writeTun,
                protect = protectTcp,
                onPayload = onGearPayload,
                onLog = onLog,
            )
            tcpConnections[key] = created
            created.onPacket(segment, packet)
            if (created.isClosed) {
                tcpConnections.remove(key)
            }
        } else {
            relay.onPacket(segment, packet)
            if (relay.isClosed) {
                tcpConnections.remove(key)
            }
        }
    }

    private fun handleUdp(ip: IpHeader, packet: ByteArray) {
        val udpOffset = ip.headerLength
        if (udpOffset + 8 > packet.size) return
        val srcPort = readShort(packet, udpOffset)
        val dstPort = readShort(packet, udpOffset + 2)
        val udpLength = readShort(packet, udpOffset + 4)
        val payloadLength = (udpLength - 8).coerceAtLeast(0)
        if (udpOffset + 8 + payloadLength > packet.size) return
        val payload = packet.copyOfRange(udpOffset + 8, udpOffset + 8 + payloadLength)

        val key = UdpKey(ip.source, srcPort, ip.destination, dstPort)
        val relay = udpRelays.getOrPut(key) {
            UdpRelay(
                gameIp = ip.source,
                gamePort = srcPort,
                serverIp = ip.destination,
                serverPort = dstPort,
                writeTun = ::writeTun,
                protect = protectUdp,
                onLog = onLog,
            ).also { it.start() }
        }
        relay.send(payload)
        onLog("udp fwd ${ipToString(ip.destination)}:$dstPort ${payload.size}B")
    }

    private fun writeTun(packet: ByteArray) {
        synchronized(writeLock) {
            output.write(packet)
            output.flush()
        }
    }

    private fun startReaper() {
        thread(name = "e7orbit-vpn-reaper", isDaemon = true) {
            while (running) {
                try {
                    Thread.sleep(REAP_INTERVAL_MS)
                    val now = System.currentTimeMillis()
                    synchronized(tcpConnections) {
                        val expiredTcp = tcpConnections.filterValues {
                            now - it.lastActivityMs() > TCP_IDLE_TIMEOUT_MS
                        }
                        expiredTcp.forEach { (key, relay) ->
                            relay.close()
                            tcpConnections.remove(key)
                        }
                    }
                    synchronized(udpRelays) {
                        val expiredUdp = udpRelays.filterValues {
                            now - it.lastActivityMs() > UDP_IDLE_TIMEOUT_MS
                        }
                        expiredUdp.forEach { (key, relay) ->
                            relay.close()
                            udpRelays.remove(key)
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun close() {
        running = false
        closeAll()
        runCatching { input.close() }
        runCatching { output.close() }
    }

    private fun closeAll() {
        synchronized(tcpConnections) {
            tcpConnections.values.forEach { it.close() }
            tcpConnections.clear()
        }
        synchronized(udpRelays) {
            udpRelays.values.forEach { it.close() }
            udpRelays.clear()
        }
    }

    private fun readShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private data class ConnectionKey(
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstIp: ByteArray,
        val dstPort: Int,
    ) {
        override fun equals(other: Any?): Boolean = other is ConnectionKey &&
            srcPort == other.srcPort &&
            dstPort == other.dstPort &&
            srcIp.contentEquals(other.srcIp) &&
            dstIp.contentEquals(other.dstIp)

        override fun hashCode(): Int =
            srcIp.contentHashCode() * 31 + srcPort * 31 + dstIp.contentHashCode() * 31 + dstPort
    }

    private data class UdpKey(
        val srcIp: ByteArray,
        val srcPort: Int,
        val dstIp: ByteArray,
        val dstPort: Int,
    ) {
        override fun equals(other: Any?): Boolean = other is UdpKey &&
            srcPort == other.srcPort &&
            dstPort == other.dstPort &&
            srcIp.contentEquals(other.srcIp) &&
            dstIp.contentEquals(other.dstIp)

        override fun hashCode(): Int =
            srcIp.contentHashCode() * 31 + srcPort * 31 + dstIp.contentHashCode() * 31 + dstPort
    }

    companion object {
        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17
        private const val MAX_PACKET_SIZE = 65_535
        private const val REAP_INTERVAL_MS = 30_000L
        private const val TCP_IDLE_TIMEOUT_MS = 5 * 60_000L
        private const val UDP_IDLE_TIMEOUT_MS = 2 * 60_000L
        private const val STATS_EVERY = 256L
        private const val MAX_TCP_CONNECTIONS = 400
    }
}

/**
 * UDP 无状态转发:tun 包 → protect DatagramSocket → 服务器;
 * 回包构造 UDP/IP 报文写回 tun。
 */
private class UdpRelay(
    private val gameIp: ByteArray,
    private val gamePort: Int,
    private val serverIp: ByteArray,
    private val serverPort: Int,
    private val writeTun: (ByteArray) -> Unit,
    private val protect: (DatagramSocket) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private val socket = DatagramSocket()
    private val serverAddress: InetAddress = InetAddress.getByAddress(serverIp)
    private var lastActivity = System.currentTimeMillis()

    fun start() {
        protect(socket)
        thread(name = "e7orbit-udp-$serverPort", isDaemon = true) {
            val buffer = ByteArray(MAX_UDP_SIZE)
            try {
                while (!socket.isClosed) {
                    val datagram = DatagramPacket(buffer, buffer.size)
                    socket.receive(datagram)
                    lastActivity = System.currentTimeMillis()
                    val payload = datagram.data.copyOf(datagram.length)
                    val packet = TcpPacketBuilder.buildUdp(
                        source = serverIp,
                        destination = gameIp,
                        sourcePort = serverPort,
                        destinationPort = gamePort,
                        payload = payload,
                    )
                    onLog("udp server->game $serverPort ${payload.size}B")
                    try {
                        writeTun(packet)
                    } catch (_: Throwable) {
                        break
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    fun send(payload: ByteArray) {
        try {
            socket.send(DatagramPacket(payload, payload.size, serverAddress, serverPort))
            lastActivity = System.currentTimeMillis()
        } catch (_: Throwable) {
        }
    }

    fun lastActivityMs(): Long = lastActivity

    fun close() {
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val MAX_UDP_SIZE = 65_535
    }
}
