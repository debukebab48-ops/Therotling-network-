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
        ThrottleProfile("gprs", "GPRS", "50 kbps / 20 kbps • 500ms", 50 * 1024L / 8, 20 * 1024L / 8, 500),
        ThrottleProfile("reg_2g", "Regular 2G", "250 kbps / 50 kbps • 300ms", 250 * 1024L / 8, 50 * 1024L / 8, 300),
        ThrottleProfile("good_2g", "Good 2G", "450 kbps / 150 kbps • 150ms", 450 * 1024L / 8, 150 * 1024L / 8, 150),
        ThrottleProfile("reg_3g", "Regular 3G", "750 kbps / 250 kbps • 100ms", 750 * 1024L / 8, 250 * 1024L / 8, 100),
        ThrottleProfile("good_3g", "Good 3G", "1.5 Mbps / 750 kbps • 40ms", 1500 * 1024L / 8, 750 * 1024L / 8, 40),
        ThrottleProfile("reg_4g", "Regular 4G/LTE", "4 Mbps / 3 Mbps • 20ms", 4000 * 1024L / 8, 3000 * 1024L / 8, 20),
        ThrottleProfile("wifi", "Wi-Fi", "30 Mbps / 15 Mbps • 2ms", 30000 * 1024L / 8, 15000 * 1024L / 8, 2),
        ThrottleProfile("dns", "High Latency DNS", "No Limit • 500ms Delay", 0, 0, 500)
    )

    fun createCustom(downKbps: Long, upKbps: Long, latencyMs: Long): ThrottleProfile {
        val downStr = if (downKbps <= 0) "No Limit" else "${downKbps} kbps"
        val upStr = if (upKbps <= 0) "No Limit" else "${upKbps} kbps"
        return ThrottleProfile(
            id = "custom",
            name = "Custom Limit",
            subtitle = "$downStr / $upStr • ${latencyMs}ms",
            downloadBps = if (downKbps <= 0) 0L else (downKbps * 1024L) / 8L,
            uploadBps = if (upKbps <= 0) 0L else (upKbps * 1024L) / 8L,
            latencyMs = latencyMs.coerceAtLeast(0L)
        )
    }
}
