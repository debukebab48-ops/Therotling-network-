package com.example.netthrottler

data class ThrottleProfile(
    val id: String,
    val name: String,
    val subtitle: String,
    val downloadBps: Long, // bytes per second (0 = unlimited)
    val uploadBps: Long,   // bytes per second (0 = unlimited)
    val latencyMs: Long = 0L
)

object Presets {
    val list = listOf(
        ThrottleProfile("none", "No Throttling", "No Limit / No Limit", 0, 0, 0),
        ThrottleProfile("gprs", "GPRS", "50kbps / 20kbps", 50 * 1024 / 8, 20 * 1024 / 8, 500),
        ThrottleProfile("reg_2g", "Regular 2G", "250kbps / 50kbps", 250 * 1024 / 8, 50 * 1024 / 8, 300),
        ThrottleProfile("good_2g", "Good 2G", "450kbps / 150kbps", 450 * 1024 / 8, 150 * 1024 / 8, 150),
        ThrottleProfile("reg_3g", "Regular 3G", "750kbps / 250kbps", 750 * 1024 / 8, 250 * 1024 / 8, 100),
        ThrottleProfile("good_3g", "Good 3G", "1.5Mbps / 750kbps", 1500 * 1024 / 8, 750 * 1024 / 8, 40),
        ThrottleProfile("reg_4g", "Regular 4G/LTE", "4Mbps / 3Mbps", 4000 * 1024 / 8, 3000 * 1024 / 8, 20),
        ThrottleProfile("wifi", "Wi-Fi", "30Mbps / 15Mbps", 30000 * 1024 / 8, 15000 * 1024 / 8, 2),
        ThrottleProfile("dns", "High Latency DNS", "No Limit / No Limit (500ms)", 0, 0, 500)
    )
}
