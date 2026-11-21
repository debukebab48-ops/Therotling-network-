package com.example.netthrottler

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.random.Random

class ThrottleVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val executor = Executors.newCachedThreadPool()
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()

    @Volatile
    private var isRunning = false

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_PROFILE_NAME = "EXTRA_PROFILE_NAME"
        const val EXTRA_PROFILE_SUB = "EXTRA_PROFILE_SUB"
        const val EXTRA_DOWN_BPS = "EXTRA_DOWN_BPS"
        const val EXTRA_UP_BPS = "EXTRA_UP_BPS"
        const val EXTRA_LATENCY_MS = "EXTRA_LATENCY_MS"
        const val EXTRA_TARGET_PKG = "EXTRA_TARGET_PKG"
    }

    private class TcpSession(
        val key: String,
        val clientIp: ByteArray,
        val serverIp: ByteArray,
        val clientPort: Int,
        val serverPort: Int,
        var clientSeq: Long,
        var serverSeq: Long,
        val socket: Socket = Socket()
    ) {
        @Volatile
        var closed = false

        fun close() {
            if (closed) return
            closed = true
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val name = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "No Throttling"
                val sub = intent.getStringExtra(EXTRA_PROFILE_SUB) ?: "No Limit / No Limit"
                val downBps = intent.getLongExtra(EXTRA_DOWN_BPS, 0L)
                val upBps = intent.getLongExtra(EXTRA_UP_BPS, 0L)
                val latencyMs = intent.getLongExtra(EXTRA_LATENCY_MS, 0L)
                val targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG)
                startVpn(name, sub, downBps, upBps, latencyMs, targetPkg)
            }
            ACTION_STOP -> stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn(
        profileName: String,
        profileSub: String,
        downBps: Long,
        upBps: Long,
        latencyMs: Long,
        targetPkg: String?
    ) {
        closeAllSessions()
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "throttle_channel")
            .setContentTitle("NetThrottler • $profileName")
            .setContentText(profileSub)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        startForeground(1, notification)

        val builder = Builder()
            .setSession("NetThrottler")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .setMtu(1500)

        if (!targetPkg.isNullOrBlank()) {
            try {
                builder.addAllowedApplication(targetPkg)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        vpnInterface = builder.establish() ?: return
        isRunning = true

        val upBucket = TokenBucket(upBps)
        val downBucket = TokenBucket(downBps)

        executor.execute {
            runPacketLoop(vpnInterface!!, upBucket, downBucket, latencyMs)
        }
    }

    private fun runPacketLoop(
        pfd: ParcelFileDescriptor,
        upBucket: TokenBucket,
        downBucket: TokenBucket,
        latencyMs: Long
    ) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(32767)

        try {
            while (isRunning) {
                val length = input.read(buffer)
                if (length > 0) {
                    val packetCopy = buffer.copyOfRange(0, length)
                    val version = (packetCopy[0].toInt() shr 4) and 0xF
                    if (version != 4 || length < 20) continue

                    val ihl = (packetCopy[0].toInt() and 0xF) * 4
                    val protocol = packetCopy[9].toInt() and 0xFF

                    when (protocol) {
                        6 -> handleTcpPacket(packetCopy, length, ihl, output, upBucket, downBucket, latencyMs)
                        17 -> handleUdpPacket(packetCopy, length, ihl, output, upBucket, downBucket, latencyMs)
                    }
                }
            }
        } catch (e: Exception) {
            if (isRunning) e.printStackTrace()
        }
    }

    // ==================== TCP RELAY ====================
    private fun handleTcpPacket(
        raw: ByteArray,
        length: Int,
        ihl: Int,
        vpnOut: FileOutputStream,
        upBucket: TokenBucket,
        downBucket: TokenBucket,
        latencyMs: Long
    ) {
        if (length < ihl + 20) return

        val srcIp = raw.copyOfRange(12, 16)
        val dstIp = raw.copyOfRange(16, 20)
        val srcPort = readU16(raw, ihl)
        val dstPort = readU16(raw, ihl + 2)
        val seqNum = readU32(raw, ihl + 4)
        val ackNum = readU32(raw, ihl + 8)
        val dataOffset = ((raw[ihl + 12].toInt() shr 4) and 0xF) * 4
        val flags = raw[ihl + 13].toInt() and 0xFF

        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        val payloadOffset = ihl + dataOffset
        val payloadLen = (length - payloadOffset).coerceAtLeast(0)
        val key = "${InetAddress.getByAddress(srcIp).hostAddress}:$srcPort->${InetAddress.getByAddress(dstIp).hostAddress}:$dstPort"

        if (isRst) {
            tcpSessions.remove(key)?.close()
            return
        }

        if (isSyn && !isAck) {
            tcpSessions.remove(key)?.close()
            val initialServerSeq = Random.nextLong(10000L, 900000000L)
            val session = TcpSession(
                key = key,
                clientIp = srcIp,
                serverIp = dstIp,
                clientPort = srcPort,
                serverPort = dstPort,
                clientSeq = (seqNum + 1) and 0xFFFFFFFFL,
                serverSeq = initialServerSeq
            )
            tcpSessions[key] = session

            executor.execute {
                try {
                    protect(session.socket)
                    session.socket.tcpNoDelay = true
                    session.socket.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 8000)

                    if (latencyMs > 0) Thread.sleep(latencyMs)

                    // Kirim SYN-ACK ke client
                    val synAck = buildTcpPacket(
                        srcIp = session.serverIp,
                        dstIp = session.clientIp,
                        srcPort = session.serverPort,
                        dstPort = session.clientPort,
                        seq = session.serverSeq,
                        ack = session.clientSeq,
                        flags = 0x12, // SYN + ACK
                        payload = ByteArray(0)
                    )
                    session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
                    writeToVpn(vpnOut, synAck)

                    // Thread baca data dari server -> client (Download)
                    startTcpDownstreamReader(session, vpnOut, downBucket, latencyMs)
                } catch (_: Exception) {
                    val rst = buildTcpPacket(
                        srcIp = session.serverIp,
                        dstIp = session.clientIp,
                        srcPort = session.serverPort,
                        dstPort = session.clientPort,
                        seq = session.serverSeq,
                        ack = session.clientSeq,
                        flags = 0x14, // RST + ACK
                        payload = ByteArray(0)
                    )
                    writeToVpn(vpnOut, rst)
                    tcpSessions.remove(key)?.close()
                }
            }
            return
        }

        val session = tcpSessions[key] ?: return

        if (payloadLen > 0) {
            val payload = raw.copyOfRange(payloadOffset, length)
            executor.execute {
                try {
                    if (session.closed || !session.socket.isConnected) return@execute
                    upBucket.consume(payload.size)
                    if (latencyMs > 0) Thread.sleep(latencyMs)

                    synchronized(session.socket) {
                        session.socket.getOutputStream().write(payload)
                        session.socket.getOutputStream().flush()
                    }

                    val nextClientSeq = (seqNum + payload.size) and 0xFFFFFFFFL
                    session.clientSeq = nextClientSeq

                    val ackPkt = buildTcpPacket(
                        srcIp = session.serverIp,
                        dstIp = session.clientIp,
                        srcPort = session.serverPort,
                        dstPort = session.clientPort,
                        seq = session.serverSeq,
                        ack = session.clientSeq,
                        flags = 0x10, // ACK
                        payload = ByteArray(0)
                    )
                    writeToVpn(vpnOut, ackPkt)
                } catch (_: Exception) {
                    tcpSessions.remove(key)?.close()
                }
            }
        }

        if (isFin) {
            session.clientSeq = (seqNum + payloadLen + 1) and 0xFFFFFFFFL
            val finAck = buildTcpPacket(
                srcIp = session.serverIp,
                dstIp = session.clientIp,
                srcPort = session.serverPort,
                dstPort = session.clientPort,
                seq = session.serverSeq,
                ack = session.clientSeq,
                flags = 0x11, // FIN + ACK
                payload = ByteArray(0)
            )
            session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
            writeToVpn(vpnOut, finAck)
            tcpSessions.remove(key)?.close()
        }
    }

    private fun startTcpDownstreamReader(
        session: TcpSession,
        vpnOut: FileOutputStream,
        downBucket: TokenBucket,
        latencyMs: Long
    ) {
        val readBuf = ByteArray(1360) // Aman di bawah MTU 1500
        try {
            val inStream = session.socket.getInputStream()
            while (isRunning && !session.closed) {
                val n = inStream.read(readBuf)
                if (n == -1) {
                    val finPkt = buildTcpPacket(
                        srcIp = session.serverIp,
                        dstIp = session.clientIp,
                        srcPort = session.serverPort,
                        dstPort = session.clientPort,
                        seq = session.serverSeq,
                        ack = session.clientSeq,
                        flags = 0x11, // FIN + ACK
                        payload = ByteArray(0)
                    )
                    session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
                    writeToVpn(vpnOut, finPkt)
                    break
                }
                if (n > 0) {
                    downBucket.consume(n)
                    if (latencyMs > 0) Thread.sleep(latencyMs)

                    val chunk = readBuf.copyOfRange(0, n)
                    val dataPkt = buildTcpPacket(
                        srcIp = session.serverIp,
                        dstIp = session.clientIp,
                        srcPort = session.serverPort,
                        dstPort = session.clientPort,
                        seq = session.serverSeq,
                        ack = session.clientSeq,
                        flags = 0x18, // PSH + ACK
                        payload = chunk
                    )
                    session.serverSeq = (session.serverSeq + n) and 0xFFFFFFFFL
                    writeToVpn(vpnOut, dataPkt)
                }
            }
        } catch (_: Exception) {
        } finally {
            tcpSessions.remove(session.key)?.close()
        }
    }

    // ==================== UDP RELAY ====================
    private fun handleUdpPacket(
        raw: ByteArray,
        length: Int,
        ihl: Int,
        vpnOut: FileOutputStream,
        upBucket: TokenBucket,
        downBucket: TokenBucket,
        latencyMs: Long
    ) {
        if (length <= ihl + 8) return

        val srcIp = raw.copyOfRange(12, 16)
        val dstIpBytes = raw.copyOfRange(16, 20)
        val dstIp = InetAddress.getByAddress(dstIpBytes)
        val srcPort = readU16(raw, ihl)
        val dstPort = readU16(raw, ihl + 2)
        val payloadOffset = ihl + 8
        val payload = raw.copyOfRange(payloadOffset, length)

        executor.execute {
            try {
                upBucket.consume(payload.size)
                if (latencyMs > 0) Thread.sleep(latencyMs)

                val socket = DatagramSocket()
                protect(socket)
                socket.soTimeout = 5000
                socket.send(DatagramPacket(payload, payload.size, dstIp, dstPort))

                val respBuf = ByteArray(4096)
                val respPacket = DatagramPacket(respBuf, respBuf.size)
                socket.receive(respPacket)
                socket.close()

                downBucket.consume(respPacket.length)
                if (latencyMs > 0) Thread.sleep(latencyMs)

                val ipPacket = buildUdpPacket(
                    srcIp = dstIpBytes,
                    dstIp = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    payload = respBuf.copyOfRange(0, respPacket.length)
                )
                writeToVpn(vpnOut, ipPacket)
            } catch (_: Exception) {
            }
        }
    }

    // ==================== PACKET BUILDERS & CHECKSUMS ====================
    private fun buildTcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        val tcpLen = 20 + payload.size
        val totalLen = 20 + tcpLen
        val buf = ByteBuffer.allocate(totalLen)

        // IPv4 Header
        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(Random.nextInt(1, 65535).toShort())
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(6.toByte()) // TCP
        buf.putShort(0)
        buf.put(srcIp)
        buf.put(dstIp)
        buf.putShort(10, computeIpChecksum(buf.array(), 0, 20))

        // TCP Header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt())
        buf.putInt(ack.toInt())
        buf.put(0x50.toByte()) // Data offset = 5 (20 bytes)
        buf.put(flags.toByte())
        buf.putShort(65535.toShort()) // Window size
        buf.putShort(0) // Checksum placeholder
        buf.putShort(0) // Urgent pointer
        buf.put(payload)

        val tcpChecksum = computeTcpChecksum(srcIp, dstIp, buf.array(), 20, tcpLen)
        buf.putShort(36, tcpChecksum)

        return buf.array()
    }

    private fun buildUdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLen = 20 + 8 + payload.size
        val buf = ByteBuffer.allocate(totalLen)

        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(17.toByte()) // UDP
        buf.putShort(0)
        buf.put(srcIp)
        buf.put(dstIp)
        buf.putShort(10, computeIpChecksum(buf.array(), 0, 20))

        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort((8 + payload.size).toShort())
        buf.putShort(0)
        buf.put(payload)

        return buf.array()
    }

    private fun computeIpChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0
        var i = offset
        while (i < offset + length) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toShort()
    }

    private fun computeTcpChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        packet: ByteArray,
        tcpOffset: Int,
        tcpLen: Int
    ): Short {
        var sum = 0
        // Pseudo-header
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += 6 // Protocol TCP
        sum += tcpLen

        // TCP header + payload
        var i = tcpOffset
        val end = tcpOffset + tcpLen
        while (i + 1 < end) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        while (sum ushr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toShort()
    }

    private fun writeToVpn(vpnOut: FileOutputStream, packet: ByteArray) {
        synchronized(vpnOut) {
            try {
                if (isRunning) vpnOut.write(packet)
            } catch (_: Exception) {
            }
        }
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readU32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private fun closeAllSessions() {
        tcpSessions.values.forEach { it.close() }
        tcpSessions.clear()
    }

    private fun stopVpn() {
        isRunning = false
        closeAllSessions()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "throttle_channel",
                "Network Throttler",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopVpn()
        executor.shutdownNow()
        super.onDestroy()
    }
}
