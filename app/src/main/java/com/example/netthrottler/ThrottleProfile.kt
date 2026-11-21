package com.example.netthrottler

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ThrottleProfile(
    val id: String,
    val name: String,
    val subtitle: String,
    val downloadBps: Long,
    val uploadBps: Long,
    val latencyMs: Long = 0L,
    val isCustom: Boolean = false
)

object Presets {
    val list = listOf(
        ThrottleProfile("none", "No Throttling", "No Limit / No Limit", 0, 0, 0, false),
        ThrottleProfile("gprs", "GPRS", "50 kbps / 20 kbps • 500ms", 50 * 1024L / 8, 20 * 1024L / 8, 500, false),
        ThrottleProfile("reg_2g", "Regular 2G", "250 kbps / 50 kbps • 300ms", 250 * 1024L / 8, 50 * 1024L / 8, 300, false),
        ThrottleProfile("good_2g", "Good 2G", "450 kbps / 150 kbps • 150ms", 450 * 1024L / 8, 150 * 1024L / 8, 150, false),
        ThrottleProfile("reg_3g", "Regular 3G", "750 kbps / 250 kbps • 100ms", 750 * 1024L / 8, 250 * 1024L / 8, 100, false),
        ThrottleProfile("good_3g", "Good 3G", "1.5 Mbps / 750 kbps • 40ms", 1500 * 1024L / 8, 750 * 1024L / 8, 40, false),
        ThrottleProfile("reg_4g", "Regular 4G/LTE", "4 Mbps / 3 Mbps • 20ms", 4000 * 1024L / 8, 3000 * 1024L / 8, 20, false),
        ThrottleProfile("wifi", "Wi-Fi", "30 Mbps / 15 Mbps • 2ms", 30000 * 1024L / 8, 15000 * 1024L / 8, 2, false),
        ThrottleProfile("dns", "High Latency DNS", "No Limit • 500ms Delay", 0, 0, 500, false)
    )

    fun createCustom(
        name: String,
        downKbps: Long,
        upKbps: Long,
        latencyMs: Long,
        existingId: String? = null
    ): ThrottleProfile {
        val cleanName = name.trim().ifEmpty { "Custom Limit" }
        val downStr = if (downKbps <= 0) "No Limit" else "${downKbps} kbps"
        val upStr = if (upKbps <= 0) "No Limit" else "${upKbps} kbps"
        return ThrottleProfile(
            id = existingId ?: "custom_${UUID.randomUUID()}",
            name = cleanName,
            subtitle = "$downStr / $upStr • ${latencyMs.coerceAtLeast(0L)}ms",
            downloadBps = if (downKbps <= 0) 0L else (downKbps * 1024L) / 8L,
            uploadBps = if (upKbps <= 0) 0L else (upKbps * 1024L) / 8L,
            latencyMs = latencyMs.coerceAtLeast(0L),
            isCustom = true
        )
    }

    fun loadCustomProfiles(context: Context): List<ThrottleProfile> {
        val prefs = context.getSharedPreferences("netthrottler_prefs", Context.MODE_PRIVATE)
        val rawJson = prefs.getString("custom_profiles", null) ?: return emptyList()
        return try {
            val arr = JSONArray(rawJson)
            val result = mutableListOf<ThrottleProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.getString("name")
                val downKbps = obj.getLong("downKbps")
                val upKbps = obj.getLong("upKbps")
                val latencyMs = obj.getLong("latencyMs")
                result.add(createCustom(name, downKbps, upKbps, latencyMs, id))
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCustomProfiles(context: Context, customs: List<ThrottleProfile>) {
        val arr = JSONArray()
        for (p in customs) {
            val downKbps = if (p.downloadBps > 0) (p.downloadBps * 8L) / 1024L else 0L
            val upKbps = if (p.uploadBps > 0) (p.uploadBps * 8L) / 1024L else 0L
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("downKbps", downKbps)
                put("upKbps", upKbps)
                put("latencyMs", p.latencyMs)
            }
            arr.put(obj)
        }
        context.getSharedPreferences("netthrottler_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("custom_profiles", arr.toString())
            .apply()
    }
}
