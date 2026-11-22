package com.buckcast.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure-Kotlin helper converting raw network exceptions into concise hunter-friendly offline status messages.
 */
object OfflineMessageFormatter {

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun formatWeatherError(
        error: Throwable?,
        cachedAtEpochMs: Long?,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val isConnectivityError = isOfflineCause(error)
        return if (isConnectivityError) {
            if (cachedAtEpochMs != null) {
                val timeStr = timeFormatter.withZone(zoneId).format(Instant.ofEpochMilli(cachedAtEpochMs))
                "Offline — showing cached forecast from $timeStr"
            } else {
                "Offline — connect once to cache local forecast"
            }
        } else if (cachedAtEpochMs != null) {
            val timeStr = timeFormatter.withZone(zoneId).format(Instant.ofEpochMilli(cachedAtEpochMs))
            "Refresh paused — using cached forecast from $timeStr"
        } else {
            "Unable to load forecast right now — pull to retry"
        }
    }

    private fun isOfflineCause(error: Throwable?): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            val className = current::class.java.name
            val msg = current.message.orEmpty()
            if (className.endsWith("UnknownHostException") ||
                className.endsWith("ConnectException") ||
                className.endsWith("SocketTimeoutException") ||
                className.endsWith("NoRouteToHostException") ||
                className.endsWith("UnresolvedAddressException") ||
                className.endsWith("IOException") ||
                msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("Offline", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }
}
