package com.example.netthrottler
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Typeface
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

class ThrottleVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null

    @Volatile
    private var globalExecutor: ExecutorService = Executors.newCachedThreadPool()

    @Synchronized
    private fun ensureExecutor() {
        if (globalExecutor.isShutdown || globalExecutor.isTerminated) {
            globalExecutor = Executors.newCachedThreadPool()
        }
    }
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()

    private val downBytesCounter = AtomicLong(0L)
    private val upBytesCounter = AtomicLong(0L)
    private var proxyServerSocket: ServerSocket? = null
    private val proxyClients = ConcurrentHashMap.newKeySet<Socket>()
    private val proxyRemotes = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var activeDownBps = 0L
    
    @Volatile
    private var isHotspotProxyActive = false
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var isRunning = false

    @Volatile
    private var speedUnit = "KB/s"

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isFilterBitmap = true
    }
    private val iconBounds = Rect()
    private fun createSpeedIconBitmap(downBytes: Long): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val speedText = formatSpeedShort(downBytes)
        var textSize = 72f
        iconPaint.textScaleX = 1.0f
        iconPaint.textSize = textSize
        iconPaint.getTextBounds(speedText, 0, speedText.length, iconBounds)

        if (iconBounds.width() > size - 4) {
            val ratio = ((size - 4).toFloat() / iconBounds.width()).coerceIn(0.65f, 1.0f)
            iconPaint.textScaleX = ratio
            iconPaint.getTextBounds(speedText, 0, speedText.length, iconBounds)
            while (iconBounds.width() > size - 4 && textSize > 32f) {
                textSize -= 2f
                iconPaint.textSize = textSize
                iconPaint.getTextBounds(speedText, 0, speedText.length, iconBounds)
            }
        }

        val x = size / 2f
        val y = size / 2f - iconBounds.exactCenterY()
        canvas.drawText(speedText, x, y, iconPaint)

        return bitmap
    }

    private fun formatSpeedShort(bytesPerSec: Long): String {
        return when (speedUnit) {
            "Mbps" -> String.format(Locale.US, "%.1f", bytesPerSec * 8.0 / 1_000_000.0)
            "MBps" -> String.format(Locale.US, "%.1f", bytesPerSec / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.1f", bytesPerSec / 1024.0)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        return when (speedUnit) {
            "Mbps" -> String.format(Locale.US, "%.1f Mbps", bytesPerSec * 8.0 / 1_000_000.0)
            "MBps" -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
        }
    }

// Fix notif gonta-ganti: tiap START baru, loop notif lama harus mati
    @Volatile
    private var notifGeneration = 0

// Anti preset gonta-ganti: tolak START duplikat yang sama dalam 1,5 detik
    private var lastPresetKey = ""
    private var lastPresetTime = 0L

    fun getLocalHotspotIp(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return "127.0.0.1"
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_PROFILE_NAME = "EXTRA_PROFILE_NAME"
        const val EXTRA_HOTSPOT_MODE = "EXTRA_HOTSPOT_MODE"
        const val PROXY_PORT = 8080

        const val EXTRA_PROFILE_SUB = "EXTRA_PROFILE_SUB"
        const val EXTRA_DOWN_BPS = "EXTRA_DOWN_BPS"
        const val EXTRA_UP_BPS = "EXTRA_UP_BPS"
        const val EXTRA_LATENCY_MS = "EXTRA_LATENCY_MS"
        const val EXTRA_TARGET_PKG = "EXTRA_TARGET_PKG"
        const val EXTRA_SPEED_UNIT = "EXTRA_SPEED_UNIT"
        private const val CHANNEL_ID = "throttle_channel_v2"
        private const val NOTIF_ID = 1
    }

    private class TcpSession(
        val key: String,
        val clientIp: ByteArray,
        val serverIp: ByteArray,
        val clientPort: Int,
        val serverPort: Int,
        @Volatile var clientSeq: Long,
        @Volatile var serverSeq: Long,
        val socket: Socket = Socket(),
        val writeExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
    ) {
        val seqLock = Any()

        @Volatile
        var closed = false

        @Volatile
        var connected = false

        fun close() {
            if (closed) return
            closed = true
            try {
                socket.close()
            } catch (_: Exception) {
            }
            writeExecutor.shutdownNow()
        }
    }

    private class UdpSession(
        val key: String,
        val clientIp: ByteArray,
        val serverIp: ByteArray,
        val clientPort: Int,
        val serverPort: Int,
        val socket: DatagramSocket,
        val sendExecutor: ExecutorService = Executors.newSingleThreadExecutor(),
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
            sendExecutor.shutdownNow()
        }
    }

    // rateBps <= 0 = tanpa limit. Batasi burst rapat biar preset pelan nggak jebol di awal.
    private class TokenBucket(private val rateBps: Long) {
        private val maxBurst: Double = if (rateBps > 0) {
            (rateBps * 0.02).coerceIn(64.0, 2048.0)
        } else {
            0.0
        }
        private var tokens: Double = 0.0
        private var lastRefillNanos: Long = System.nanoTime()
        private val lock = Any()

        fun isLimited(): Boolean = rateBps > 0

        fun consume(bytes: Int) {
if (rateBps <= 0 || bytes <= 0) return
            var remaining = bytes.toDouble()
            while (remaining > 0) {
                val waitMs: Long
                synchronized(lock) {
                    val now = System.nanoTime()
                    val elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0
                    if (elapsedSec > 0) {
                        tokens = (tokens + elapsedSec * rateBps).coerceAtMost(maxBurst)
                        lastRefillNanos = now
                    }
                    val take = minOf(tokens, remaining)
                    tokens -= take
                    remaining -= take
                    if (remaining <= 0) return
                    waitMs = ((remaining / rateBps) * 1000).toLong().coerceAtLeast(1L)
                }
                try {
                    Thread.sleep(waitMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val name = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: "Throttle"
                val sub = intent.getStringExtra(EXTRA_PROFILE_SUB) ?: "No Limit"
                val downBps = intent.getLongExtra(EXTRA_DOWN_BPS, 0L)
                val upBps = intent.getLongExtra(EXTRA_UP_BPS, 0L)
                val latencyMs = intent.getLongExtra(EXTRA_LATENCY_MS, 0L)
                val hotspotMode = intent.getBooleanExtra(EXTRA_HOTSPOT_MODE, false)
                val targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG)
                speedUnit = intent.getStringExtra(EXTRA_SPEED_UNIT) ?: "KB/s"
                // Debounce preset - jangan restart kalau presetnya sama persis
                val presetKey = "$name|$sub|$downBps|$upBps|$latencyMs|$hotspotMode|$targetPkg"
                val nowMs = System.currentTimeMillis()
                if (presetKey == lastPresetKey && nowMs - lastPresetTime < 1500) {
                    return START_STICKY
                }
                lastPresetKey = presetKey
                lastPresetTime = nowMs

                try {
                    ensureExecutor()
                    if (hotspotMode) {
                        startHotspotProxy(name, sub, downBps, upBps, latencyMs)
                    } else {
                        startVpn(name, sub, downBps, upBps, latencyMs, targetPkg)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Jangan crash: service tetap jalan sebagai foreground dengan notif error, biar user bisa STOP
                    try {
                        createNotificationChannel()
                        startForeground(NOTIF_ID, buildNotification(name, "Gagal start: " + (e.message ?: "unknown"), 0L))
                    } catch (_: Exception) {}
                }
                return START_STICKY
            }
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }
    private fun buildNotification(profileName: String, trafficText: String, downBytes: Long) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Preset: $profileName")
            .setContentText(trafficText)
            .setSmallIcon(IconCompat.createWithBitmap(createSpeedIconBitmap(downBytes)))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NetThrottler::ThrottleLock").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun startVpn(
        profileName: String,
        profileSub: String,
        downBps: Long,
        upBps: Long,
        latencyMs: Long,
        targetPkg: String?,
    ) {
        stopHotspotProxyInternal()
        closeAllSessions()
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }

        activeDownBps = downBps
        downBytesCounter.set(0L)
        upBytesCounter.set(0L)
        acquireWakeLock()
        createNotificationChannel()
        startForeground(
            NOTIF_ID,
            buildNotification(profileName, "↓ ${formatSpeed(0)}  •  ↑ ${formatSpeed(0)} ($profileSub)", 0L),
        )

        val builder = Builder()
            .setSession("NetThrottler")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addAddress("fd00:1:fd00::1", 128)
            .addRoute("::", 0)
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

        globalExecutor.execute {
            runPacketLoop(vpnInterface!!, upBucket, downBucket, latencyMs)
        }

        val myGen = ++notifGeneration
        globalExecutor.execute {
            runNotificationStatsLoop(profileName, profileSub, myGen)
        }
    }

    private fun runNotificationStatsLoop(profileName: String, profileSub: String, gen: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        while (isRunning && gen == notifGeneration) {
            try {
                Thread.sleep(1000)
                if (!isRunning || gen != notifGeneration) break
                val downBytes = downBytesCounter.getAndSet(0L)
                val upBytes = upBytesCounter.getAndSet(0L)
                val downKBs = downBytes / 1024.0
                val upKBs = upBytes / 1024.0
                val text = "↓ ${formatSpeed(downBytes)}  •  ↑ ${formatSpeed(upBytes)}  ($profileSub)"
                nm.notify(NOTIF_ID, buildNotification(profileName, text, downBytes))
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
            }
        }
    }

    private fun runPacketLoop(
        pfd: ParcelFileDescriptor,
        upBucket: TokenBucket,
        downBucket: TokenBucket,
        latencyMs: Long,
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
        latencyMs: Long,
    ) {
        if (length < ihl + 20) return

        val srcIp = raw.copyOfRange(12, 16)
        val dstIp = raw.copyOfRange(16, 20)
        val srcPort = readU16(raw, ihl)
        val dstPort = readU16(raw, ihl + 2)
        val seqNum = readU32(raw, ihl + 4)
        val dataOffset = ((raw[ihl + 12].toInt() shr 4) and 0xF) * 4
        val flags = raw[ihl + 13].toInt() and 0xFF

        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        val payloadOffset = ihl + dataOffset
        val payloadLen = (length - payloadOffset).coerceAtLeast(0)
        val key = "${srcIp[0]}.${srcIp[1]}.${srcIp[2]}.${srcIp[3]}:$srcPort->${dstIp[0]}.${dstIp[1]}.${dstIp[2]}.${dstIp[3]}:$dstPort"

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
                serverSeq = initialServerSeq,
            )
            tcpSessions[key] = session

            session.writeExecutor.execute {
                try {
                    protect(session.socket)
                    session.socket.tcpNoDelay = true
                    if (activeDownBps > 0) {
                        runCatching { session.socket.receiveBufferSize = 8192 }
                    }
                    session.socket.connect(InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort), 10000)
session.connected = true

                    if (latencyMs > 0) Thread.sleep(latencyMs)

                    synchronized(session.seqLock) {
                        val synAck = buildTcpPacket(
                            srcIp = session.serverIp,
                            dstIp = session.clientIp,
                            srcPort = session.serverPort,
                            dstPort = session.clientPort,
                            seq = session.serverSeq,
                            ack = session.clientSeq,
                            flags = 0x12,
                            payload = ByteArray(0),
                            includeMss = true,
                        )
                        session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
                        writeToVpn(vpnOut, synAck)
                    }

                    globalExecutor.execute {
                        startTcpDownstreamReader(session, vpnOut, downBucket, latencyMs)
                    }
                } catch (_: Exception) {
                    synchronized(session.seqLock) {
                        val rst = buildTcpPacket(
                            srcIp = session.serverIp,
                            dstIp = session.clientIp,
                            srcPort = session.serverPort,
                            dstPort = session.clientPort,
                            seq = session.serverSeq,
                            ack = session.clientSeq,
                            flags = 0x14,
                            payload = ByteArray(0),
                        )
                        writeToVpn(vpnOut, rst)
                    }
                    tcpSessions.remove(key)?.close()
                }
            }
            return
        }

        val session = tcpSessions[key] ?: return

        if (payloadLen > 0) {
            val payload = raw.copyOfRange(payloadOffset, length)
            session.writeExecutor.execute {
                try {
                    if (session.closed || !session.connected) return@execute

                    val diff = seqDiff(seqNum, session.clientSeq)
                    if (diff == 0) {
                        upBucket.consume(payload.size)
                        if (latencyMs > 0) Thread.sleep(latencyMs)

                        session.socket.getOutputStream().write(payload)
                        session.socket.getOutputStream().flush()
                        session.clientSeq = (seqNum + payload.size) and 0xFFFFFFFFL
                        upBytesCounter.addAndGet(payload.size.toLong())
                    }

                    synchronized(session.seqLock) {
                        val ackPkt = buildTcpPacket(
                            srcIp = session.serverIp,
                            dstIp = session.clientIp,
                            srcPort = session.serverPort,
                            dstPort = session.clientPort,
                            seq = session.serverSeq,
                            ack = session.clientSeq,
                            flags = 0x10,
                            payload = ByteArray(0),
                        )
                        writeToVpn(vpnOut, ackPkt)
                    }
                } catch (_: Exception) {
                    tcpSessions.remove(key)?.close()
                }
            }
        }

        if (isFin) {
            session.writeExecutor.execute {
                try {
                    if (session.closed) return@execute
                    val expectedFinSeq = (seqNum + payloadLen) and 0xFFFFFFFFL
                    if (seqDiff(expectedFinSeq, session.clientSeq) == 0) {
                        session.clientSeq = (expectedFinSeq + 1) and 0xFFFFFFFFL
                    }
                    synchronized(session.seqLock) {
                        val finAck = buildTcpPacket(
                            srcIp = session.serverIp,
                            dstIp = session.clientIp,
                            srcPort = session.serverPort,
                            dstPort = session.clientPort,
                            seq = session.serverSeq,
                            ack = session.clientSeq,
                            flags = 0x11,
                            payload = ByteArray(0),
                        )
                        session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
                        writeToVpn(vpnOut, finAck)
                    }
                } catch (_: Exception) {
                } finally {
                    tcpSessions.remove(key)?.close()
                }
            }
        }
    }

    private fun startTcpDownstreamReader(
        session: TcpSession,
        vpnOut: FileOutputStream,
        downBucket: TokenBucket,
        latencyMs: Long,
    ) {
        val maxRead = if (downBucket.isLimited()) 256 else 1360
        val readBuf = ByteArray(maxRead)
        try {
            val inStream = session.socket.getInputStream()
            while (isRunning && !session.closed) {
                val n = inStream.read(readBuf, 0, maxRead)
if (n == -1) {
                    synchronized(session.seqLock) {
                        val finPkt = buildTcpPacket(
                            srcIp = session.serverIp,
                            dstIp = session.clientIp,
                            srcPort = session.serverPort,
                            dstPort = session.clientPort,
                            seq = session.serverSeq,
                            ack = session.clientSeq,
                            flags = 0x11,
                            payload = ByteArray(0),
                        )
                        session.serverSeq = (session.serverSeq + 1) and 0xFFFFFFFFL
                        writeToVpn(vpnOut, finPkt)
                    }
                    break
                }
                if (n > 0) {
                    downBucket.consume(n)
                    if (latencyMs > 0) Thread.sleep(latencyMs)
                    downBytesCounter.addAndGet(n.toLong())

                    val chunk = readBuf.copyOfRange(0, n)
                    synchronized(session.seqLock) {
                        val dataPkt = buildTcpPacket(
                            srcIp = session.serverIp,
                            dstIp = session.clientIp,
                            srcPort = session.serverPort,
                            dstPort = session.clientPort,
                            seq = session.serverSeq,
                            ack = session.clientSeq,
                            flags = 0x18,
                            payload = chunk,
                        )
                        session.serverSeq = (session.serverSeq + n) and 0xFFFFFFFFL
                        writeToVpn(vpnOut, dataPkt)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            tcpSessions.remove(session.key)?.close()
        }
    }

    // ==================== HOTSPOT PROXY ====================
    private fun startHotspotProxy(
        profileName: String,
        profileSub: String,
        downBps: Long,
        upBps: Long,
        latencyMs: Long,
    ) {
        isHotspotProxyActive = false
        isRunning = false
        try {
            proxyServerSocket?.close()
        } catch (_: Exception) {
        }
        proxyServerSocket = null
        proxyClients.toList().forEach {
            try {
                it.close()
            } catch (_: Exception) {}
        }
        proxyClients.clear()
        proxyRemotes.toList().forEach {
            try {
                it.close()
            } catch (_: Exception) {}
        }
        proxyRemotes.clear()
        closeAllSessions()
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null

        activeDownBps = downBps
        downBytesCounter.set(0L)
        upBytesCounter.set(0L)
        acquireWakeLock()

        createNotificationChannel()
        val activeIp = getLocalHotspotIp()
        startForeground(
            NOTIF_ID,
            buildNotification(profileName, "Proxy $activeIp:$PROXY_PORT • ↓ ${formatSpeed(0)} • ↑ ${formatSpeed(0)} ($profileSub)", 0L),
        )
        
        isHotspotProxyActive = true
        isRunning = true

        val upBucket = TokenBucket(upBps)
        val downBucket = TokenBucket(downBps)

        globalExecutor.execute {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PROXY_PORT))
                }
                proxyServerSocket = server
                while (isRunning && isHotspotProxyActive) {
                    try {
                        val clientSock = server.accept()
                        try {
                            clientSock.soTimeout = 30_000
                            clientSock.tcpNoDelay = true
                        } catch (_: Exception) {}
                        try {
                            ensureExecutor()
                            globalExecutor.execute {
                                // Satu klien error TIDAK BOLEH bunuh service
                                try {
                                    handleProxyClient(clientSock, upBucket, downBucket, latencyMs)
                                } catch (t: Throwable) {
                                    try {
                                        clientSock.close()
                                    } catch (_: Exception) {}
                                }
                            }
                        } catch (t: Throwable) {
                            // RejectedExecutionException dll -> tutup socket klien ini saja
                            try {
                                clientSock.close()
                            } catch (_: Exception) {}
                        }
                    } catch (e: java.net.SocketException) {
                        // ServerSocket ditutup dari stopHotspotProxyInternal -> keluar loop dengan tenang
                        if (!isRunning || !isHotspotProxyActive) break
                    } catch (_: Exception) {
                        if (!isRunning || !isHotspotProxyActive) break
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val myGenHotspot = ++notifGeneration
        globalExecutor.execute {
            val nm = getSystemService(NotificationManager::class.java)
            while (isRunning && isHotspotProxyActive && myGenHotspot == notifGeneration) {
                try {
                    Thread.sleep(1000)
                    if (!isRunning || !isHotspotProxyActive || myGenHotspot != notifGeneration) break
                    val dBytes = downBytesCounter.getAndSet(0L)
                    val uBytes = upBytesCounter.getAndSet(0L)
                    val dKBs = dBytes / 1024.0
                    val uKBs = uBytes / 1024.0
                    val text = "Proxy $activeIp:$PROXY_PORT • ↓ ${formatSpeed(dBytes)} • ↑ ${formatSpeed(uBytes)} ($profileSub)"
                    nm.notify(NOTIF_ID, buildNotification(profileName, text, dBytes))
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun stopHotspotProxyInternal() {
        isHotspotProxyActive = false
        try {
            proxyServerSocket?.close()
        } catch (_: Exception) {
        }
        proxyServerSocket = null
        proxyClients.toList().forEach {
            try {
                it.close()
            } catch (_: Exception) {}
        }
        proxyClients.clear()
        proxyRemotes.toList().forEach {
            try {
                it.close()
            } catch (_: Exception) {}
        }
        proxyRemotes.clear()
    }
    
    private fun handleProxyClient(
        client: Socket,
        upBucket: TokenBucket,
        downBucket: TokenBucket,
        latencyMs: Long,
    ) {
        proxyClients.add(client)
        // FIX UTAMA crash 4 detik random:
        // Dulu: remote.connect() ada di try-finally TANPA catch.
        // Kalau DNS gagal / timeout / refused / klien putus duluan, IOException naik ke
        // Executor thread -> uncaught -> Android bunuh proses -> "NetThrottler telah berhenti".
        // Sekarang: SEMUA exception ditangkap per-koneksi, klien itu saja yang gagal, service tetap hidup.
        try {
            client.use { cSock ->
                val input: InputStream
                val out: OutputStream
                try {
                    input = BufferedInputStream(cSock.getInputStream(), 1024)
                    out = cSock.getOutputStream()
                } catch (_: Exception) {
                    return
                }

                val firstLine = try {
                    readAsciiLine(input)
                } catch (_: Exception) {
                    return
                } ?: return

                // Tolak request aneh / kosong tanpa crash
                if (firstLine.isBlank()) return
                val headers = mutableListOf<String>()
                try {
                    while (true) {
                        val line = readAsciiLine(input) ?: return
                        if (line.isEmpty()) break
                        if (line.length > 8192) return
                        if (!line.startsWith("Proxy-", ignoreCase = true)) {
                            headers.add(line)
                        }
                    }
                } catch (_: Exception) {
                    return
                }

                val parts = firstLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase(Locale.US)
                val target = parts[1]
                if (target.isBlank() || target == "*") {
                    // OPTIONS * / probe captive-portal tanpa target -> jawab OK saja
                    try {
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        out.flush()
                    } catch (_: Exception) {}
                    return
                }

                if (method == "CONNECT") {
                    // FIX IPv6: dulu target.split(":") hancur untuk [IPv6]:port
                    // Pakai lastIndexOf(':') biar [2606:4700::...]:443 kepotong benar
                    val host: String
                    val port: Int
                    try {
                        val clean = target.removeSurrounding("[", "]")
                        // Cari host:port dengan cara aman untuk IPv6
                        if (target.startsWith("[")) {
                            val closeIdx = target.indexOf(']')
                            if (closeIdx == -1) return
                            host = target.substring(1, closeIdx)
                            val rest = target.substring(closeIdx + 1)
                            port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: 443 else 443
                        } else {
                            val lastColon = target.lastIndexOf(':')
                            if (lastColon > 0 && lastColon < target.length - 1) {
                                host = target.substring(0, lastColon)
                                port = target.substring(lastColon + 1).toIntOrNull() ?: 443
                            } else {
                                host = target
                                port = 443
                            }
                        }
                        if (host.isBlank()) return
                    } catch (_: Exception) {
                        return
                    }

                    val remote = Socket()
                    proxyRemotes.add(remote)
                    try {
                        try {
                            protect(remote)
                        } catch (_: Exception) {}
                        try {
                            remote.soTimeout = 30_000
                        } catch (_: Exception) {}
                        try {
                            remote.tcpNoDelay = true
                        } catch (_: Exception) {}
                        if (activeDownBps > 0) {
                            runCatching { remote.receiveBufferSize = 8192 }
                        }
                        remote.connect(InetSocketAddress(host, port), 10_000)
                        if (latencyMs > 0) Thread.sleep(latencyMs)
                        try {
                            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                            out.flush()
                        } catch (_: Exception) {
                            return
                        }
                        relayBidirectional(input, out, remote, upBucket, downBucket, latencyMs)
                    } catch (e: Exception) {
                        // Gagal connect ke tujuan (DNS / timeout / refused) -> kirim 502 ke klien ini saja
                        try {
                            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            out.flush()
                        } catch (_: Exception) {}
                    } finally {
                        proxyRemotes.remove(remote)
                        try {
                            remote.close()
                        } catch (_: Exception) {}
                    }
} else {
                    val uri: URI
                    try {
                        uri = URI(target)
                    } catch (_: Exception) {
                        try {
                            out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            out.flush()
                        } catch (_: Exception) {}
                        return
                    }
                    // Klien kadang kirim origin-form "/path" bukan absolute URI -> host dari header Host
                    val host: String
                    val port: Int
                    val path: String
                    try {
                        if (uri.host != null) {
                            host = uri.host
                            port = if (uri.port != -1) uri.port else 80
                            path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                        } else {
                            // Cari Host header
                            val hostHeader = headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
                                ?.substringAfter(":", "")?.trim() ?: return
                            val hParts = hostHeader.split(":")
                            host = hParts[0]
                            port = hParts.getOrNull(1)?.toIntOrNull() ?: 80
                            path = target.ifEmpty { "/" }
                        }
                        if (host.isBlank()) return
                    } catch (_: Exception) {
                        return
                    }
                    val remote = Socket()
                    proxyRemotes.add(remote)
                    try {
                        try {
                            protect(remote)
                        } catch (_: Exception) {}
                        try {
                            remote.soTimeout = 30_000
                        } catch (_: Exception) {}
                        try {
                            remote.tcpNoDelay = true
                        } catch (_: Exception) {}
                        if (activeDownBps > 0) {
                            runCatching { remote.receiveBufferSize = 8192 }
                        }
                        remote.connect(InetSocketAddress(host, port), 10_000)
                        if (latencyMs > 0) Thread.sleep(latencyMs)
                        val sb = StringBuilder("$method $path HTTP/1.1\r\n")
                        headers.forEach { sb.append(it).append("\r\n") }
                        sb.append("\r\n")
                        val reqBytes = sb.toString().toByteArray()
                        try {
                            upBucket.consume(reqBytes.size)
                            upBytesCounter.addAndGet(reqBytes.size.toLong())
                            remote.getOutputStream().write(reqBytes)
                            remote.getOutputStream().flush()
                        } catch (_: Exception) {
                            return
                        }
                        relayBidirectional(input, out, remote, upBucket, downBucket, latencyMs)
                    } catch (_: Exception) {
                        try {
                            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                            out.flush()
                        } catch (_: Exception) {}
                    } finally {
                        proxyRemotes.remove(remote)
                        try {
                            remote.close()
                        } catch (_: Exception) {}
                    }
}
            }
        } catch (t: Throwable) {
            // Jaring pengaman terakhir: jangan pernah lempar ke Executor thread
        } finally {
            proxyClients.remove(client)
            try {
                client.close()
            } catch (_: Exception) {}
        }
    }

    private fun relayBidirectional(
        clientIn: InputStream,
        clientOut: OutputStream,
        remote: Socket,
        upBucket: TokenBucket,
        downBucket: TokenBucket,
        latencyMs: Long,
    ) {
        val remoteIn = remote.getInputStream()
        val remoteOut = remote.getOutputStream()

        val maxUp = if (upBucket.isLimited()) 256 else 1024
        val upTask = globalExecutor.submit {
            val buf = ByteArray(maxUp)
            try {
                while (isRunning && isHotspotProxyActive) {
                    val n = clientIn.read(buf, 0, maxUp)
                    if (n <= 0) break
                    upBucket.consume(n)
                    if (latencyMs > 0) Thread.sleep(latencyMs)
                    upBytesCounter.addAndGet(n.toLong())
                    remoteOut.write(buf, 0, n)
                    remoteOut.flush()
                }
            } catch (_: Exception) {
            }
        }

        val maxDown = if (downBucket.isLimited()) 256 else 1024
        val bufDown = ByteArray(maxDown)
        try {
            while (isRunning && isHotspotProxyActive) {
                val n = remoteIn.read(bufDown, 0, maxDown)
if (n <= 0) break
                downBucket.consume(n)
                if (latencyMs > 0) Thread.sleep(latencyMs)
                downBytesCounter.addAndGet(n.toLong())
                clientOut.write(bufDown, 0, n)
                clientOut.flush()
            }
        } catch (_: Exception) {
        } finally {
            upTask.cancel(true)
            try {
                remote.close()
            } catch (_: Exception) {}
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb.last() == '\r') {
                    sb.deleteCharAt(sb.length - 1)
                }
                return sb.toString()
            }
            // Batasi 8KB: kalau klien kirim TLS mentah / binary, jangan baca selamanya
            if (sb.length > 8192) return sb.toString()
            // Abaikan byte non-ASCII yang bukan bagian request HTTP
            if (b in 0..31 && b != 9) continue
            sb.append(b.toChar())
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
        latencyMs: Long,
    ) {
        if (length <= ihl + 8) return

        val srcIp = raw.copyOfRange(12, 16)
        val dstIpBytes = raw.copyOfRange(16, 20)
        val dstIp = InetAddress.getByAddress(dstIpBytes)
        val srcPort = readU16(raw, ihl)
        val dstPort = readU16(raw, ihl + 2)
        val payloadOffset = ihl + 8
        val payload = raw.copyOfRange(payloadOffset, length)

        val key = "$srcPort->${dstIpBytes[0]}.${dstIpBytes[1]}.${dstIpBytes[2]}.${dstIpBytes[3]}:$dstPort"

        var session = udpSessions[key]
        if (session == null || session.closed) {
            try {
                val sock = DatagramSocket()
                protect(sock)
                sock.soTimeout = 30000
                val newSession = UdpSession(key, srcIp, dstIpBytes, srcPort, dstPort, sock)
                udpSessions[key] = newSession
                session = newSession

                globalExecutor.execute {
                    val respBuf = ByteArray(4096)
                    val respPacket = DatagramPacket(respBuf, respBuf.size)
                    try {
                        while (isRunning && !newSession.closed) {
                            sock.receive(respPacket)
                            if (respPacket.length > 0) {
                                downBucket.consume(respPacket.length)
                                if (latencyMs > 0) Thread.sleep(latencyMs)
                                downBytesCounter.addAndGet(respPacket.length.toLong())

                                val ipPacket = buildUdpPacket(
                                    srcIp = newSession.serverIp,
                                    dstIp = newSession.clientIp,
                                    srcPort = newSession.serverPort,
                                    dstPort = newSession.clientPort,
                                    payload = respBuf.copyOfRange(0, respPacket.length),
                                )
                                writeToVpn(vpnOut, ipPacket)
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                    } finally {
                        udpSessions.remove(key)?.close()
                    }
                }
            } catch (_: Exception) {
                return
            }
        }

        val activeSession = session
        activeSession.sendExecutor.execute {
            try {
                if (activeSession.closed) return@execute
                upBucket.consume(payload.size)
                if (latencyMs > 0) Thread.sleep(latencyMs)
                activeSession.socket.send(DatagramPacket(payload, payload.size, dstIp, dstPort))
                upBytesCounter.addAndGet(payload.size.toLong())
            } catch (_: Exception) {
                udpSessions.remove(key)?.close()
            }
        }
    }

    // ==================== PACKET BUILDERS & CHECKSUMS ====================
    private fun seqDiff(a: Long, b: Long): Int = ((a - b) and 0xFFFFFFFFL).toInt()

    private fun buildTcpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray,
        includeMss: Boolean = false,
    ): ByteArray {
        val optionsLen = if (includeMss) 4 else 0
        val tcpHeaderLen = 20 + optionsLen
        val tcpLen = tcpHeaderLen + payload.size
        val totalLen = 20 + tcpLen
        val buf = ByteBuffer.allocate(totalLen)

        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(Random.nextInt(1, 65535).toShort())
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(6.toByte())
        buf.putShort(0)
        buf.put(srcIp)
        buf.put(dstIp)
        buf.putShort(10, computeIpChecksum(buf.array(), 0, 20))

        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq.toInt())
        buf.putInt(ack.toInt())
        val advertisedWindow = if (activeDownBps > 0) 4096 else 65535
        buf.put(((tcpHeaderLen / 4) shl 4).toByte())
        buf.put(flags.toByte())
        buf.putShort(advertisedWindow.toShort())
        buf.putShort(0)
buf.putShort(0)
        if (includeMss) {
            buf.put(2.toByte())
            buf.put(4.toByte())
            buf.putShort(1360.toShort())
        }
        buf.put(payload)

        val tcpChecksum = computeTransportChecksum(srcIp, dstIp, 6, buf.array(), 20, tcpLen)
        buf.putShort(36, tcpChecksum)

        return buf.array()
    }

    private fun buildUdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val buf = ByteBuffer.allocate(totalLen)

        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(Random.nextInt(1, 65535).toShort())
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(17.toByte())
        buf.putShort(0)
        buf.put(srcIp)
        buf.put(dstIp)
        buf.putShort(10, computeIpChecksum(buf.array(), 0, 20))

        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0)
        buf.put(payload)

        val udpChecksum = computeTransportChecksum(srcIp, dstIp, 17, buf.array(), 20, udpLen)
        buf.putShort(26, if (udpChecksum.toInt() == 0) 0xFFFF.toShort() else udpChecksum)

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

    private fun computeTransportChecksum(
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        packet: ByteArray,
        transportOffset: Int,
        transportLen: Int,
    ): Short {
        var sum = 0
        sum += ((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)
        sum += ((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)
        sum += ((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)
        sum += ((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)
        sum += protocol
        sum += transportLen

        var i = transportOffset
        val end = transportOffset + transportLen
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
        udpSessions.values.forEach { it.close() }
        udpSessions.clear()
    }

    private fun stopVpn() {
        isRunning = false
        activeDownBps = 0L
        stopHotspotProxyInternal()
                releaseWakeLock()
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
                CHANNEL_ID,
                "Network Throttler",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopVpn()
        globalExecutor.shutdownNow()
        super.onDestroy()
    }
}
