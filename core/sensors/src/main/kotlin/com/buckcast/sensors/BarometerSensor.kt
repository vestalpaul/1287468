package com.buckcast.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.buckcast.domain.math.GeoUnits
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.pow

data class BarometerReading(
    val stationPressureInHg: Double?,
    val seaLevelPressureInHg: Double?,
    val pressureTrendInHg: Double?,
    val isSeaLevelCorrected: Boolean,
    val isAvailable: Boolean,
    val timestampEpochMs: Long,
)

/**
 * Pure barometric math converting raw Android station hPa to sea-level reduced inHg
 * when GPS altitude is known, plus tracking rolling local pressure trend deltas.
 */
object BarometerMath {

    private const val HYPSOMETRIC_SCALE_HEIGHT_METERS = 44_330.0
    private const val HYPSOMETRIC_EXPONENT = 5.255

    fun stationToSeaLevelHpa(stationHpa: Double, altitudeFeet: Double): Double {
        require(stationHpa.isFinite() && stationHpa > 0.0) { "Invalid stationHpa: $stationHpa" }
        require(altitudeFeet.isFinite()) { "Invalid altitudeFeet: $altitudeFeet" }
        val altMeters = GeoUnits.feetToMeters(altitudeFeet).coerceIn(-500.0, 12_000.0)
        val factor = (1.0 - altMeters / HYPSOMETRIC_SCALE_HEIGHT_METERS).pow(HYPSOMETRIC_EXPONENT)
        return stationHpa / factor
    }

    fun stationToSeaLevelInHg(stationHpa: Double, altitudeFeet: Double?): Pair<Double, Boolean> {
        return if (altitudeFeet != null) {
            GeoUnits.hpaToInHg(stationToSeaLevelHpa(stationHpa, altitudeFeet)) to true
        } else {
            GeoUnits.hpaToInHg(stationHpa) to false
        }
    }
}

class LocalPressureTrendTracker(
    private val windowMs: Long = 3L * 3600L * 1000L,
    private val minBaselineAgeMs: Long = 15L * 60L * 1000L,
) {
    private val history = ArrayDeque<Pair<Long, Double>>()

    @Synchronized
    fun recordAndComputeTrend(timestampMs: Long, pressureInHg: Double): Double? {
        history.addLast(timestampMs to pressureInHg)
        val cutoff = timestampMs - windowMs
        while (history.size > 1 && history.first().first < cutoff) {
            history.removeFirst()
        }
        val oldest = history.firstOrNull() ?: return null
        return if ((timestampMs - oldest.first) >= minBaselineAgeMs) {
            pressureInHg - oldest.second
        } else {
            null
        }
    }
}

/**
 * Optional `Sensor.TYPE_PRESSURE` reader that reports availability gracefully on devices
 * lacking a barometric chip and reduces station pressure to sea-level `inHg` using GPS altitude.
 */
class BarometerSensor(
    private val sensorManager: SensorManager,
) {
    private val currentAltitudeFeet = AtomicReference<Double?>(null)
    private val trendTracker = LocalPressureTrendTracker()

    fun updateAltitudeFeet(altitudeFeet: Double?) {
        currentAltitudeFeet.set(altitudeFeet)
    }

    fun observePressure(): Flow<BarometerReading> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        if (sensor == null) {
            trySend(
                BarometerReading(
                    stationPressureInHg = null,
                    seaLevelPressureInHg = null,
                    pressureTrendInHg = null,
                    isSeaLevelCorrected = false,
                    isAvailable = false,
                    timestampEpochMs = System.currentTimeMillis(),
                ),
            )
            awaitClose {}
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val stationHpa = event.values.firstOrNull()?.toDouble() ?: return
                if (!stationHpa.isFinite() || stationHpa <= 0.0) return

                val stationInHg = GeoUnits.hpaToInHg(stationHpa)
                val (seaLevelInHg, corrected) = BarometerMath.stationToSeaLevelInHg(
                    stationHpa = stationHpa,
                    altitudeFeet = currentAltitudeFeet.get(),
                )
                val nowMs = System.currentTimeMillis()
                val trend = trendTracker.recordAndComputeTrend(nowMs, seaLevelInHg)

                trySend(
                    BarometerReading(
                        stationPressureInHg = stationInHg,
                        seaLevelPressureInHg = seaLevelInHg,
                        pressureTrendInHg = trend,
                        isSeaLevelCorrected = corrected,
                        isAvailable = true,
                        timestampEpochMs = nowMs,
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
}
