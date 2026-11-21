package com.buckcast.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.buckcast.data.local.dao.AppSettingsDao
import com.buckcast.data.local.dao.WeatherCacheDao
import com.buckcast.domain.repository.WeatherRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Network-constrained periodic 3-hour background weather refresh worker anchored to
 * persisted `AppSettingsEntity` coordinates (or latest cached weather cell fallback).
 */
@HiltWorker
class WeatherRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val settingsDao: AppSettingsDao,
    private val weatherCacheDao: WeatherCacheDao,
    private val weatherRepository: WeatherRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val settings = settingsDao.getSettingsOnce()
        val lat = settings?.lastKnownLat
        val lon = settings?.lastKnownLon

        val targetCoords = if (lat != null && lon != null) {
            lat to lon
        } else {
            val latest = weatherCacheDao.getLatestOnce()
            if (latest != null) latest.latitude to latest.longitude else null
        } ?: return Result.success()

        val refreshResult = weatherRepository.refreshForecast(targetCoords.first, targetCoords.second)
        return if (refreshResult.isSuccess) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "buckcast_weather_periodic_sync"
    }
}

class WeatherSyncScheduler(private val context: Context) {

    fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<WeatherRefreshWorker>(3L, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WeatherRefreshWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
