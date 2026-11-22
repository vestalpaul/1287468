package com.buckcast.sensors

import android.annotation.SuppressLint
import android.os.Looper
import com.buckcast.domain.math.AngleMath
import com.buckcast.domain.math.GeoPoint
import com.buckcast.domain.math.GeoUnits
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

enum class LocationPowerMode {
    HIGH_ACCURACY,
    BATTERY_SAVER,
}

sealed interface LocationState {
    data object Searching : LocationState
    data object PermissionRequired : LocationState
    data class Fix(
        val point: GeoPoint,
        val accuracyFeet: Double,
        val bearingDeg: Double?,
        val timestampEpochMs: Long,
    ) : LocationState
}

/**
 * Coroutine flow wrapper around [FusedLocationProviderClient] supporting high-accuracy tracking
 * and battery-saving stand-sit intervals, converting elevation & accuracy to US imperial feet.
 */
open class LocationProvider(
    private val fusedClient: FusedLocationProviderClient,
) {
    @SuppressLint("MissingPermission")
    open fun observeLocation(mode: LocationPowerMode = LocationPowerMode.HIGH_ACCURACY): Flow<LocationState> = callbackFlow {
        trySend(LocationState.Searching)

        val intervalMs = when (mode) {
            LocationPowerMode.HIGH_ACCURACY -> 2_000L
            LocationPowerMode.BATTERY_SAVER -> 30_000L
        }
        val priority = when (mode) {
            LocationPowerMode.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
            LocationPowerMode.BATTERY_SAVER -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val request = LocationRequest.Builder(priority, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val elevFeet = if (loc.hasAltitude()) GeoUnits.metersToFeet(loc.altitude) else null
                val bearing = if (loc.hasBearing()) AngleMath.normalizeDegrees(loc.bearing.toDouble()) else null
                trySend(
                    LocationState.Fix(
                        point = GeoPoint(
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            elevationFeet = elevFeet,
                        ),
                        accuracyFeet = GeoUnits.metersToFeet(loc.accuracy.toDouble()),
                        bearingDeg = bearing,
                        timestampEpochMs = loc.time,
                    ),
                )
            }
        }

        try {
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            trySend(LocationState.PermissionRequired)
            close()
            return@callbackFlow
        }

        awaitClose {
            fusedClient.removeLocationUpdates(callback)
        }
    }
}
