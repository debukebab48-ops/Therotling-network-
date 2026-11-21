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
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class ThrottleVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var isRunning = false

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_PROFILE_ID = "EXTRA_PROFILE_ID"
        const val EXTRA_TARGET_PKG = "EXTRA_TARGET_PKG" // Kosong = Global Mode
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: "none"
                val targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG)
                val profile = Presets.list.find { it.id == profileId } ?: Presets.list[0]
                startVpn(profile, targetPkg)
            }
            ACTION_STOP -> stopVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn(profile: ThrottleProfile, targetPkg: String?) {
        stopVpn()
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "throttle_channel")
            .setContentTitle("NetThrottler Aktif")
            .setContentText("Profil: ${profile.name} (${profile.subtitle})")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        startForeground(1, notification)

        val builder = Builder()
            .setSession("NetThrottler")
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(1500)

        // Jika bukan Global Mode, batasi hanya ke 1 aplikasi target
        if (!targetPkg.isNullOrBlank()) {
            try {
                builder.addAllowedApplication(targetPkg)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            // Exclude aplikasi ini sendiri biar tidak loop
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        vpnInterface = builder.establish() ?: return
        isRunning = true

        val upBucket = TokenBucket(profile.uploadBps)
        val downBucket = TokenBucket(profile.downloadBps)

        executor.execute {
            runPacketLoop(vpnInterface!!, upBucket, downBucket, profile.latencyMs)
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
        val packet = ByteBuffer.allocate(32767)

        try {
            while (isRunning) {
                val length = input.read(packet.array())
                if (length > 0) {
                    // Terapkan limit upload & simulasi latency
                    upBucket.consume(length)
                    if (latencyMs > 0) Thread.sleep(latencyMs)

                    // Forward UDP (DNS & QUIC) via protected socket
                    handleOutgoingPacket(packet.array(), length, output, downBucket, latencyMs)
                    packet.clear()
                }
            }
        } catch (e: Exception) {
            if (isRunning) e.printStackTrace()
        }
    }

    private fun handleOutgoingPacket(
        raw: ByteArray,
        length: Int,
        vpnOut: FileOutputStream,
        downBucket: TokenBucket,
        latencyMs: Long
    ) {
        // Parsing header IPv4 minimal untuk meneruskan UDP (termasuk DNS port 53)
        val version = (raw[0].toInt() shr 4) and 0xF
        if (version != 4) return

        val ihl = (raw[0].toInt() and 0xF) * 4
        val protocol = raw[9].toInt() and 0xFF

        if (protocol == 17 && length > ihl + 8) { // UDP
            val dstIp = InetAddress.getByAddress(raw.copyOfRange(16, 20))
            val srcPort = ((raw[ihl].toInt() and 0xFF) shl 8) or (raw[ihl + 1].toInt() and 0xFF)
            val dstPort = ((raw[ihl + 2].toInt() and 0xFF) shl 8) or (raw[ihl + 3].toInt() and 0xFF)
            val payloadOffset = ihl + 8
            val payloadLength = length - payloadOffset
            if (payloadLength <= 0) return

            val payload = raw.copyOfRange(payloadOffset, length)

            executor.execute {
                try {
                    val socket = DatagramSocket()
                    protect(socket) // Penting: bypass VPN agar tidak looping
                    socket.soTimeout = 5000
                    socket.send(DatagramPacket(payload, payload.size, dstIp, dstPort))

                    val respBuf = ByteArray(4096)
                    val respPacket = DatagramPacket(respBuf, respBuf.size)
                    socket.receive(respPacket)
                    socket.close()

                    // Terapkan limit download & latency pada balasan
                    downBucket.consume(respPacket.length)
                    if (latencyMs > 0) Thread.sleep(latencyMs)

                    val ipPacket = buildIpv4UdpPacket(
                        dstIp.address,
                        raw.copyOfRange(12, 16),
                        dstPort,
                        srcPort,
                        respBuf.copyOfRange(0, respPacket.length)
                    )
                    synchronized(vpnOut) {
                        vpnOut.write(ipPacket)
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun buildIpv4UdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val totalLen = 20 + 8 + payload.size
        val buf = ByteBuffer.allocate(totalLen)

        // IPv4 Header
        buf.put(0x45.toByte())
        buf.put(0x00.toByte())
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0x4000.toShort())
        buf.put(64.toByte())
        buf.put(17.toByte()) // UDP
        buf.putShort(0) // Checksum placeholder
        buf.put(srcIp)
        buf.put(dstIp)

        // Hitung IPv4 Header Checksum
        var sum = 0
        for (i in 0 until 20 step 2) {
            sum += ((buf.get(i).toInt() and 0xFF) shl 8) or (buf.get(i + 1).toInt() and 0xFF)
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        buf.putShort(10, sum.inv().toShort())

        // UDP Header
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort((8 + payload.size).toShort())
        buf.putShort(0) // UDP Checksum optional di IPv4
        buf.put(payload)

        return buf.array()
    }

    private fun stopVpn() {
        isRunning = false
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
