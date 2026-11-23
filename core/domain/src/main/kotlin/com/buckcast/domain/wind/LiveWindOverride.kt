package com.buckcast.domain.wind

import com.buckcast.domain.math.AngleMath

/**
 * Ephemeral hunter-observed wind override (e.g., milkweed float in timber or hollow)
 * that supersedes the regional 10m Open-Meteo wind forecast until [expiresAtEpochMs]
 * (4 hours by default) or manual reset.
 */
data class LiveWindOverride(
    val windFromDeg: Double,
    val windSpeedMph: Double,
    val setAtEpochMs: Long,
    val expiresAtEpochMs: Long = setAtEpochMs + DEFAULT_DURATION_MS,
) {
    init {
        require(windFromDeg.isFinite() && windFromDeg in 0.0..<360.0) {
            "windFromDeg must be finite in [0.0, 360.0): $windFromDeg"
        }
        require(windSpeedMph.isFinite() && windSpeedMph >= 0.0) {
            "windSpeedMph must be finite and non-negative: $windSpeedMph"
        }
        require(expiresAtEpochMs >= setAtEpochMs) {
            "expiresAtEpochMs ($expiresAtEpochMs) must be >= setAtEpochMs ($setAtEpochMs)"
        }
    }

    fun isExpired(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs

    companion object {
        const val DEFAULT_DURATION_MS: Long = 4L * 60L * 60L * 1000L

        fun fromMilkweedFloat(
            downwindFloatHeadingDeg: Double,
            windSpeedMph: Double,
            setAtEpochMs: Long,
            durationMs: Long = DEFAULT_DURATION_MS,
        ): LiveWindOverride = LiveWindOverride(
            windFromDeg = windFromMilkweedFloatDeg(downwindFloatHeadingDeg),
            windSpeedMph = windSpeedMph,
            setAtEpochMs = setAtEpochMs,
            expiresAtEpochMs = setAtEpochMs + durationMs.coerceAtLeast(0L),
        )
    }
}

/**
 * Converts the compass bearing milkweed floats *toward* (downwind scent direction)
 * into standard meteorological wind-FROM degrees in `[0.0, 360.0)`.
 */
fun windFromMilkweedFloatDeg(downwindFloatHeadingDeg: Double): Double {
    require(downwindFloatHeadingDeg.isFinite()) {
        "downwindFloatHeadingDeg must be finite: $downwindFloatHeadingDeg"
    }
    return AngleMath.normalizeDegrees(((downwindFloatHeadingDeg + 180.0) % 360.0 + 360.0) % 360.0)
}
