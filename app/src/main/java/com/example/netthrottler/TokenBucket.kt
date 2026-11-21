package com.example.netthrottler

import kotlin.math.min

class TokenBucket(private val bytesPerSecond: Long) {
    private var tokens: Double = bytesPerSecond.toDouble()
    private var lastRefillTime: Long = System.nanoTime()

    @Synchronized
    fun consume(bytes: Int) {
        if (bytesPerSecond <= 0) return // No limit

        while (true) {
            refill()
            if (tokens >= bytes) {
                tokens -= bytes
                return
            }
            val deficit = bytes - tokens
            val waitMs = ((deficit / bytesPerSecond) * 1000).toLong().coerceAtLeast(1)
            Thread.sleep(waitMs)
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastRefillTime) / 1_000_000_000.0
        if (elapsedSeconds > 0) {
            tokens = min(bytesPerSecond.toDouble(), tokens + elapsedSeconds * bytesPerSecond)
            lastRefillTime = now
        }
    }
}
