package com.e7orbit.vpn

import java.net.InetAddress

/**
 * IPv4/IPv6 报文解析、构造与校验和工具。
 * 地址统一用 ByteArray:4 字节 = IPv4,16 字节 = IPv6。
 */
object IpChecksum {
    /** 16 位反码和。length 为字节数(应取偶)。 */
    fun compute(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i < end - 1) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum shr 16)
        return ((sum.inv() and 0xFFFF).toInt())
    }

    /** TCP 校验和(含 IPv4/IPv6 伪头)。 */
    fun tcpChecksum(
        source: ByteArray,
        destination: ByteArray,
        tcpSegment: ByteArray,
    ): Int {
        var sum = 0L
        var i = 0
        while (i < source.size - 1) {
            sum += ((source[i].toInt() and 0xFF) shl 8) or (source[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < source.size) sum += (source[i].toInt() and 0xFF) shl 8
        i = 0
        while (i < destination.size - 1) {
            sum += ((destination[i].toInt() and 0xFF) shl 8) or (destination[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < destination.size) sum += (destination[i].toInt() and 0xFF) shl 8

        if (source.size == 16) {
            // IPv6 伪头:源(16B) + 目的(16B) + 长度(4B) + 3 字节零 + next header
            sum += (tcpSegment.size ushr 16) and 0xFFFF
            sum += tcpSegment.size and 0xFFFF
            sum += 6 // next header = TCP
        } else {
            // IPv4 伪头:源(4B) + 目的(4B) + 0 + protocol + 长度
            sum += 6
            sum += tcpSegment.size
        }

        i = 0
        while (i < tcpSegment.size - 1) {
            sum += ((tcpSegment[i].toInt() and 0xFF) shl 8) or (tcpSegment[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < tcpSegment.size) sum += (tcpSegment[i].toInt() and 0xFF) shl 8
        while (sum > 0xFFFF) sum = (sum and 0xFFFF) + (sum shr 16)
        return ((sum.inv() and 0xFFFF).toInt())
    }
}

object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}

/** 解析出的 IP 头(IPv4 或 IPv6)。 */
class IpHeader(
    val version: Int,
    val headerLength: Int,
    val totalLength: Int,
    val protocol: Int,
    val source: ByteArray,
    val destination: ByteArray,
) {
    val isV6: Boolean
        get() = version == 6

    companion object {
        fun parse(packet: ByteArray, offset: Int = 0): IpHeader? {
            if (packet.size - offset < 20) return null
            val version = (packet[offset].toInt() ushr 4) and 0x0F
            return when (version) {
                4 -> parseV4(packet, offset)
                6 -> parseV6(packet, offset)
                else -> null
            }
        }

        private fun parseV4(packet: ByteArray, offset: Int): IpHeader? {
            val ihl = packet[offset].toInt() and 0x0F
            if (ihl < 5) return null
            val headerLength = ihl * 4
            val totalLength = readShort(packet, offset + 2)
            if (totalLength < headerLength || packet.size - offset < totalLength) return null
            return IpHeader(
                version = 4,
                headerLength = headerLength,
                totalLength = totalLength,
                protocol = packet[offset + 9].toInt() and 0xFF,
                source = packet.copyOfRange(offset + 12, offset + 16),
                destination = packet.copyOfRange(offset + 16, offset + 20),
            )
        }

        private fun parseV6(packet: ByteArray, offset: Int): IpHeader? {
            if (packet.size - offset < 40) return null
            val payloadLength = readShort(packet, offset + 4)
            val totalLength = 40 + payloadLength
            if (packet.size - offset < totalLength) return null
            return IpHeader(
                version = 6,
                headerLength = 40,
                totalLength = totalLength,
                protocol = packet[offset + 6].toInt() and 0xFF,
                source = packet.copyOfRange(offset + 8, offset + 24),
                destination = packet.copyOfRange(offset + 24, offset + 40),
            )
        }

        private fun readShort(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }
}

/** 解析出的 TCP 段(不含 IP 头)。 */
class TcpSegment(
    val sourcePort: Int,
    val destinationPort: Int,
    val seq: Long,
    val ack: Long,
    val flags: Int,
    val window: Int,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    companion object {
        fun parse(ipHeader: IpHeader, packet: ByteArray, ipOffset: Int = 0): TcpSegment? {
            val tcpOffset = ipOffset + ipHeader.headerLength
            if (tcpOffset + 20 > packet.size) return null
            val dataOffset = ((packet[tcpOffset + 12].toInt() ushr 4) and 0x0F) * 4
            if (dataOffset < 20 || tcpOffset + dataOffset > packet.size) return null
            val totalLength = ipHeader.totalLength
            val payloadOffset = tcpOffset + dataOffset
            val payloadLength = (ipOffset + totalLength - payloadOffset).coerceAtLeast(0)
            return TcpSegment(
                sourcePort = readShort(packet, tcpOffset),
                destinationPort = readShort(packet, tcpOffset + 2),
                seq = readUInt(packet, tcpOffset + 4),
                ack = readUInt(packet, tcpOffset + 8),
                flags = packet[tcpOffset + 13].toInt() and 0x3F,
                window = readShort(packet, tcpOffset + 14),
                payloadOffset = payloadOffset,
                payloadLength = payloadLength,
            )
        }

        private fun readShort(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

        private fun readUInt(data: ByteArray, offset: Int): Long =
            ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
    }
}

/** 构造发往 tun 的 TCP/UDP 报文(自动选择 IPv4/IPv6)。 */
object TcpPacketBuilder {
    fun build(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray = EMPTY,
    ): ByteArray = if (source.size == 4) {
        buildV4(source, destination, sourcePort, destinationPort, seq, ack, flags, window, payload)
    } else {
        buildV6(source, destination, sourcePort, destinationPort, seq, ack, flags, window, payload)
    }

    fun buildUdp(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray = if (source.size == 4) {
        buildUdpV4(source, destination, sourcePort, destinationPort, payload)
    } else {
        buildUdpV6(source, destination, sourcePort, destinationPort, payload)
    }

    private fun buildV4(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray,
    ): ByteArray {
        val tcpLength = 20 + payload.size
        val packet = ByteArray(20 + tcpLength)

        packet[0] = 0x45.toByte()
        writeShort(packet, 2, 20 + tcpLength)
        writeShort(packet, 4, 0x0000)
        writeShort(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 6
        source.copyInto(packet, 12)
        destination.copyInto(packet, 16)
        writeShort(packet, 10, IpChecksum.compute(packet, 0, 20))

        writeTcpHeader(packet, 20, sourcePort, destinationPort, seq, ack, flags, window, payload)
        writeShort(
            packet,
            20 + 16,
            IpChecksum.tcpChecksum(source, destination, packet.copyOfRange(20, 20 + tcpLength)),
        )
        return packet
    }

    private fun buildV6(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray,
    ): ByteArray {
        val tcpLength = 20 + payload.size
        val packet = ByteArray(40 + tcpLength)

        packet[0] = 0x60.toByte()
        writeShort(packet, 4, tcpLength)
        packet[6] = 6
        packet[7] = 64
        source.copyInto(packet, 8)
        destination.copyInto(packet, 24)

        writeTcpHeader(packet, 40, sourcePort, destinationPort, seq, ack, flags, window, payload)
        writeShort(
            packet,
            40 + 16,
            IpChecksum.tcpChecksum(source, destination, packet.copyOfRange(40, 40 + tcpLength)),
        )
        return packet
    }

    private fun writeTcpHeader(
        packet: ByteArray,
        tcpOffset: Int,
        sourcePort: Int,
        destinationPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        window: Int,
        payload: ByteArray,
    ) {
        writeShort(packet, tcpOffset, sourcePort)
        writeShort(packet, tcpOffset + 2, destinationPort)
        writeUInt(packet, tcpOffset + 4, seq)
        writeUInt(packet, tcpOffset + 8, ack)
        packet[tcpOffset + 12] = (5 shl 4).toByte()
        packet[tcpOffset + 13] = flags.toByte()
        writeShort(packet, tcpOffset + 14, window)
        writeShort(packet, tcpOffset + 16, 0) // checksum 占位
        writeShort(packet, tcpOffset + 18, 0)
        if (payload.isNotEmpty()) {
            payload.copyInto(packet, tcpOffset + 20)
        }
    }

    private fun buildUdpV4(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(20 + udpLength)
        packet[0] = 0x45.toByte()
        writeShort(packet, 2, 20 + udpLength)
        writeShort(packet, 4, 0x0000)
        writeShort(packet, 6, 0x4000)
        packet[8] = 64
        packet[9] = 17
        source.copyInto(packet, 12)
        destination.copyInto(packet, 16)
        writeShort(packet, 10, IpChecksum.compute(packet, 0, 20))
        writeUdpHeader(packet, 20, sourcePort, destinationPort, payload)
        return packet
    }

    private fun buildUdpV6(
        source: ByteArray,
        destination: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLength = 8 + payload.size
        val packet = ByteArray(40 + udpLength)
        packet[0] = 0x60.toByte()
        writeShort(packet, 4, udpLength)
        packet[6] = 17
        packet[7] = 64
        source.copyInto(packet, 8)
        destination.copyInto(packet, 24)
        writeUdpHeader(packet, 40, sourcePort, destinationPort, payload)
        return packet
    }

    private fun writeUdpHeader(
        packet: ByteArray,
        udpOffset: Int,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ) {
        writeShort(packet, udpOffset, sourcePort)
        writeShort(packet, udpOffset + 2, destinationPort)
        writeShort(packet, udpOffset + 4, 8 + payload.size)
        writeShort(packet, udpOffset + 6, 0) // checksum(可 0)
        if (payload.isNotEmpty()) {
            payload.copyInto(packet, udpOffset + 8)
        }
    }

    private val EMPTY = ByteArray(0)

    private fun writeShort(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeUInt(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }
}

fun ipToString(ip: ByteArray): String = try {
    InetAddress.getByAddress(ip).hostAddress ?: "?"
} catch (_: Throwable) {
    "?"
}
