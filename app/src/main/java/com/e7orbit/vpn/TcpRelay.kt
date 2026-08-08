package com.e7orbit.vpn

import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * 单条 TCP 连接的双向透明代理。
 *
 * 原理:游戏发往服务器的流量被路由进 tun,这里伪装服务器与游戏完成握手
 * (伪连接,seq 由本类维护),同时用 protect() 的真实 socket 连接真正的
 * 服务器(真实连接,seq 由系统内核维护)。转发时只搬运载荷,seq/ack 在
 * tun 侧按伪连接重映射。
 *
 * 方向约定:
 *  - game → server:从 tun 读到的游戏载荷,去重后写入真实 socket;
 *  - server → game:从真实 socket 读到的数据,构造 TCP 段写回 tun。
 */
class TcpRelay(
    val connectionId: Long,
    val gameIp: ByteArray,
    val gamePort: Int,
    val serverIp: ByteArray,
    val serverPort: Int,
    private val writeTun: (ByteArray) -> Unit,
    private val protect: (Socket) -> Unit,
    private val onPayload: (Long, Int, PayloadDirection, ByteArray) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private val lock = Any()
    private val socket = Socket()
    private var socketConnected = false
    private var handshakeStarted = false
    private var gameIsn = 0L
    private val tunIsn = Random.nextLong() and 0xFFFFFFFFL
    private var receivedFromGame = 0L
    private var sentToGame = 0L
    private var gameAckedOurSyn = false
    private var gameSentFin = false
    private var serverSentFin = false
    @Volatile
    private var closed = false
    @Volatile
    private var lastActivity = System.currentTimeMillis()
    private var segmentLogCount = 0L

    private val pendingToServer = ArrayDeque<ByteArray>()
    private var pendingBytes = 0L

    val isClosed: Boolean
        get() = closed

    fun lastActivityMs(): Long = lastActivity

    /** 处理从 tun 读到的游戏 → 服务器的 TCP 段。 */
    fun onPacket(segment: TcpSegment, packet: ByteArray) {
        synchronized(lock) {
            if (closed) return
            lastActivity = System.currentTimeMillis()

            when {
                (segment.flags and TcpFlags.SYN) != 0 -> {
                    // 幂等处理:首次 SYN 启动真实连接,SYN 重传时重新回 SYN-ACK。
                    if (!handshakeStarted) {
                        handshakeStarted = true
                        gameIsn = segment.seq
                        onLog("syn game=$gamePort -> server=$serverPort@${ipToString(serverIp)}")
                        connectToServer()
                    }
                    sendTun(SYN_ACK_FLAGS, tunIsn, gameIsn + 1)
                    onLog("synack sent (game $gamePort <- server $serverPort)")
                }

                (segment.flags and TcpFlags.RST) != 0 -> {
                    onLog("rst from game (${ipToString(gameIp)}:$gamePort -> $serverPort)")
                    closed = true
                    closeQuietly(socket)
                }

                (segment.flags and TcpFlags.FIN) != 0 -> {
                    onLog("fin from game")
                    gameSentFin = true
                    handleGamePayload(segment, packet)
                    if (serverSentFin) {
                        closed = true
                        closeQuietly(socket)
                    }
                }

                (segment.flags and TcpFlags.ACK) != 0 &&
                    segment.ack == ((tunIsn + 1) and 0xFFFFFFFFL) -> {
                    if (!gameAckedOurSyn) {
                        gameAckedOurSyn = true
                        onLog("game acked our syn (ack=${segment.ack})")
                    }
                    handleGamePayload(segment, packet)
                }

                else -> {
                    if (!handshakeStarted) {
                        // 中间流截获(无 SYN 的既有连接):发 RST 强制对端重连,
                        // 重连的连接带 SYN 才能被完整代理。
                        onLog("mid-stream packet without SYN, sending RST (game $gamePort -> $serverPort@${ipToString(serverIp)})")
                        sendTun(
                            TcpFlags.RST,
                            (segment.seq + segment.payloadLength) and 0xFFFFFFFFL,
                            0L,
                        )
                        closed = true
                        closeQuietly(socket)
                    } else {
                        handleGamePayload(segment, packet)
                    }
                }
            }
        }
    }

    private fun handleGamePayload(segment: TcpSegment, packet: ByteArray) {
        if (segment.payloadLength <= 0) return
        val expected = (gameIsn + 1 + receivedFromGame) and 0xFFFFFFFFL
        if (segment.seq != expected) {
            // 重传/乱序:回 ACK 告知已收到水位,抑制游戏继续重传。
            onLog("payload seq mismatch: got=${segment.seq} expected=$expected (ack)")
            sendTun(ACK_FLAGS, (tunIsn + 1 + sentToGame) and 0xFFFFFFFFL, expected)
            return
        }

        val payload = packet.copyOfRange(
            segment.payloadOffset,
            segment.payloadOffset + segment.payloadLength,
        )
        receivedFromGame += segment.payloadLength

        onPayload(connectionId, serverPort, PayloadDirection.GAME_TO_SERVER, payload)
        if (segmentLogCount++ % LOG_EVERY == 0L) {
            onLog("fwd game->server $serverPort ${payload.size}B (total=$receivedFromGame) ${payload.previewAscii()}")
        }

        if (socketConnected) {
            try {
                socket.getOutputStream().write(payload)
                socket.getOutputStream().flush()
            } catch (error: Throwable) {
                onLog("write to server failed: ${error.message}")
                closed = true
                closeQuietly(socket)
            }
        } else {
            // 真实连接未就绪:缓冲有限量,防止连接长期失败时无限堆积导致 OOM
            if (pendingBytes + segment.payloadLength > PENDING_LIMIT) {
                onLog("pending buffer overflow, dropping connection")
                closed = true
                closeQuietly(socket)
            } else {
                pendingToServer.addLast(payload)
                pendingBytes += segment.payloadLength
            }
        }
        // 关键:回 ACK,告知游戏数据已接收(否则游戏一直等 ACK 并重传)
        sendTun(ACK_FLAGS, (tunIsn + 1 + sentToGame) and 0xFFFFFFFFL, (gameIsn + 1 + receivedFromGame) and 0xFFFFFFFFL)
    }

    private fun connectToServer() {
        thread(name = "e7orbit-tcp-$serverPort", isDaemon = true) {
            try {
                protect(socket)
                val address = InetAddress.getByAddress(serverIp)
                socket.connect(InetSocketAddress(address, serverPort), CONNECT_TIMEOUT_MS)
                onLog("real connected to $serverPort (local=${socket.localAddress.hostAddress}:${socket.localPort})")

                synchronized(lock) {
                    socketConnected = true
                    if (closed) {
                        false
                    } else {
                        val output = socket.getOutputStream()
                        while (pendingToServer.isNotEmpty()) {
                            output.write(pendingToServer.removeFirst())
                        }
                        pendingBytes = 0
                        output.flush()
                        startServerReader(socket.getInputStream())
                        true
                    }
                }
            } catch (error: Throwable) {
                onLog("real connect failed: ${error.message}")
                synchronized(lock) {
                    closed = true
                    pendingToServer.clear()
                    pendingBytes = 0
                    closeQuietly(socket)
                }
            }
        }
    }

    private fun startServerReader(input: InputStream) {
        thread(name = "e7orbit-tcp-read-$serverPort", isDaemon = true) {
            val buffer = ByteArray(READ_BUFFER_SIZE)
            try {
                while (!closed) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    val data = buffer.copyOf(read)
                    // 服务器 → 游戏方向:按 tun MTU 分片,避免写入超过 MTU 的包。
                    var offset = 0
                    while (offset < data.size) {
                        val chunkSize = minOf(MAX_TUN_PAYLOAD, data.size - offset)
                        val chunk = data.copyOfRange(offset, offset + chunkSize)
                        synchronized(lock) {
                            if (closed) return@synchronized
                            lastActivity = System.currentTimeMillis()
                            if (gameAckedOurSyn && !gameSentFin) {
                                val seq = (tunIsn + 1 + sentToGame) and 0xFFFFFFFFL
                                val ack = (gameIsn + 1 + receivedFromGame) and 0xFFFFFFFFL
                                sentToGame += chunkSize
                                onPayload(
                                    connectionId,
                                    serverPort,
                                    PayloadDirection.SERVER_TO_GAME,
                                    chunk,
                                )
                                if (segmentLogCount % LOG_EVERY == 0L) {
                                    onLog("fwd server->game $serverPort ${chunk.size}B (total=$sentToGame)")
                                }
                                sendTun(flags = ACK_PSH_FLAGS, seq = seq, ack = ack, payload = chunk)
                            }
                        }
                        offset += chunkSize
                    }
                }
                // EOF:对端关闭
                synchronized(lock) {
                    serverSentFin = true
                    if (!closed && !gameSentFin) {
                        sendTun(FIN_ACK_FLAGS, (tunIsn + 1 + sentToGame) and 0xFFFFFFFFL, (gameIsn + 1 + receivedFromGame) and 0xFFFFFFFFL)
                    }
                    closed = true
                    closeQuietly(socket)
                }
            } catch (error: Throwable) {
                synchronized(lock) {
                    if (!closed) {
                        closed = true
                        closeQuietly(socket)
                    }
                }
            }
        }
    }

    /** 写回 tun;payload 非空时也记录。 */
    private fun sendTun(flags: Int, seq: Long, ack: Long, payload: ByteArray = EMPTY) {
        val packet = TcpPacketBuilder.build(
            source = serverIp,
            destination = gameIp,
            sourcePort = serverPort,
            destinationPort = gamePort,
            seq = seq,
            ack = ack,
            flags = flags,
            window = TUN_WINDOW,
            payload = payload,
        )
        try {
            writeTun(packet)
        } catch (error: Throwable) {
            closed = true
        }
    }

    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            closeQuietly(socket)
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_BUFFER_SIZE = 16 * 1024
        private const val TUN_WINDOW = 65_535
        private const val MAX_TUN_PAYLOAD = 1400
        private const val PENDING_LIMIT = 256L * 1024L
        private const val LOG_EVERY = 64L
        private val EMPTY = ByteArray(0)

        private const val SYN_ACK_FLAGS = TcpFlags.SYN or TcpFlags.ACK
        private const val ACK_PSH_FLAGS = TcpFlags.ACK or TcpFlags.PSH
        private const val FIN_ACK_FLAGS = TcpFlags.FIN or TcpFlags.ACK
        private const val ACK_FLAGS = TcpFlags.ACK

        /** 日志预览:前 96 字节转可打印 ASCII,方便识别 HTTP/协议。 */
        private fun ByteArray.previewAscii(): String {
            val end = minOf(size, 96)
            val sb = StringBuilder()
            for (i in 0 until end) {
                val c = this[i].toInt().toChar()
                sb.append(if (c in ' '..'~') c else '.')
            }
            return "[$sb]"
        }
    }
}
