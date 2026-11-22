package com.buckcast.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import com.buckcast.domain.math.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * True-north hardware compass fusing `Sensor.TYPE_ROTATION_VECTOR` (with automatic fallback
 * to `Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR` on gyroless devices), display-rotation remapping,
 * direct 3x3 matrix column projection (`+Y` flat vs `-Z` skyward upright aiming), circular EMA
 * smoothing (~20 Hz), and 10-mile cached `GeomagneticField` declination.
 */
open class RotationVectorCompass(
    private val context: Context?,
    private val sensorManager: SensorManager?,
) {
    protected constructor() : this(null, null)

    private val currentLocation = AtomicReference<GeoPoint?>(null)
    private val declinationCache = DeclinationCache()

    open fun updateLocation(point: GeoPoint?) {
        currentLocation.set(point)
    }

    open fun observeHeading(): Flow<CompassReading> = callbackFlow {
        val sm = sensorManager
        val primarySensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val fallbackSensor = if (primarySensor == null) {
            sm?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        } else {
            null
        }
        val activeSensor = primarySensor ?: fallbackSensor
        val source = when {
            primarySensor != null -> CompassSensorSource.ROTATION_VECTOR
            fallbackSensor != null -> CompassSensorSource.GEOMAGNETIC_ROTATION_VECTOR
            else -> CompassSensorSource.UNAVAILABLE
        }

        if (activeSensor == null || sm == null) {
            trySend(
                CompassReading(
                    trueHeadingDeg = 0.0,
                    magneticHeadingDeg = 0.0,
                    declinationDeg = 0.0,
                    pitchDeg = 0.0,
                    posture = CompassPosture.FLAT_MAP,
                    accuracy = CompassAccuracy.CALIBRATION_NEEDED,
                    sensorSource = CompassSensorSource.UNAVAILABLE,
                    isSensorAvailable = false,
                    timestampEpochMs = System.currentTimeMillis(),
                ),
            )
            awaitClose {}
            return@callbackFlow
        }

        val displayManager = context?.getSystemService(DisplayManager::class.java)
        val rawMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)

        var currentPosture = CompassPosture.FLAT_MAP
        var smoothedTrueHeading: Double? = null
        var currentAccuracy = CompassAccuracy.MEDIUM

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rawMatrix, event.values)

                val displayRotation = displayManager
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
                    ?.rotation
                    ?: Surface.ROTATION_0
                val (axisX, axisY) = CompassFusionMath.displayAxisRemapFor(displayRotation)
                SensorManager.remapCoordinateSystem(rawMatrix, axisX, axisY, remappedMatrix)

                val pitchDeg = CompassFusionMath.extractSkywardPitchDeg(remappedMatrix)
                currentPosture = CompassFusionMath.resolvePostureFromSkywardZ(
                    r7 = remappedMatrix[7].toDouble(),
                    previousPosture = currentPosture,
                )
                val magneticAzimuthDeg = CompassFusionMath.extractMagneticAzimuthFromMatrix(
                    remappedMatrix = remappedMatrix,
                    posture = currentPosture,
                )

                val nowMs = System.currentTimeMillis()
                val declinationDeg = declinationCache.getDeclinationDegrees(
                    location = currentLocation.get(),
                    timestampEpochMs = nowMs,
                )

                val trueHeading = CompassFusionMath.computeTrueHeading(
                    magneticAzimuthDeg = magneticAzimuthDeg,
                    declinationDeg = declinationDeg,
                    previousSmoothedDeg = smoothedTrueHeading,
                )
                smoothedTrueHeading = trueHeading

                trySend(
                    CompassReading(
                        trueHeadingDeg = trueHeading,
                        magneticHeadingDeg = magneticAzimuthDeg,
                        declinationDeg = declinationDeg,
                        pitchDeg = pitchDeg,
                        posture = currentPosture,
                        accuracy = currentAccuracy,
                        sensorSource = source,
                        isSensorAvailable = true,
                        timestampEpochMs = nowMs,
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                currentAccuracy = CompassFusionMath.mapAccuracy(accuracy)
            }
        }

        sm.registerListener(listener, activeSensor, SAMPLING_PERIOD_US)
        awaitClose {
            sm.unregisterListener(listener)
        }
    }

    companion object {
        private const val SAMPLING_PERIOD_US = 50_000 // ~20 Hz
    }
}
