package com.buckcast.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.buckcast.data.entitlement.BillingGateway
import com.buckcast.data.sync.WeatherSyncScheduler
import com.buckcast.domain.repository.EntitlementRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil
import javax.inject.Inject

@HiltAndroidApp
class BuckCastApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var weatherSyncScheduler: WeatherSyncScheduler

    @Inject
    lateinit var billingGateway: BillingGateway

    @Inject
    lateinit var entitlementRepository: EntitlementRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        val tileHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(DropTineStyleInterceptor { BuildConfig.MAPTILER_API_KEY })
            .build()
        HttpRequestUtil.setOkHttpClient(tileHttpClient)

        billingGateway.configureIfReady()
        weatherSyncScheduler.schedulePeriodicSync()
        appScope.launch {
            entitlementRepository.ensureInitialized()
        }
    }

    companion object {
        private const val USER_AGENT = "DropTine/0.2.0 (Android; Tactical Whitetail Map)"
    }
}
