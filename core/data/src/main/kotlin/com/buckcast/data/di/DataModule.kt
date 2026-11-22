package com.buckcast.data.di

import android.content.Context
import androidx.room.Room
import com.buckcast.data.entitlement.BillingGateway
import com.buckcast.data.entitlement.EntitlementRepositoryImpl
import com.buckcast.data.entitlement.RevenueCatBillingGateway
import com.buckcast.data.export.BackupRepositoryImpl
import com.buckcast.data.local.BuckCastDatabase
import com.buckcast.data.local.dao.AppSettingsDao
import com.buckcast.data.local.dao.EntitlementCacheDao
import com.buckcast.data.local.dao.FieldLogDao
import com.buckcast.data.local.dao.SightingDao
import com.buckcast.data.local.dao.StandDao
import com.buckcast.data.local.dao.TrackDao
import com.buckcast.data.local.dao.WaypointDao
import com.buckcast.data.local.dao.WeaponProfileDao
import com.buckcast.data.local.dao.WeatherCacheDao
import com.buckcast.data.local.mapper.EntityMappers
import com.buckcast.data.remote.OpenMeteoClient
import com.buckcast.data.repository.FieldDataRepositoryImpl
import com.buckcast.data.repository.SettingsRepositoryImpl
import com.buckcast.data.repository.StandRepositoryImpl
import com.buckcast.data.repository.WeatherRepositoryImpl
import com.buckcast.data.sync.WeatherSyncScheduler
import com.buckcast.domain.model.AppRuntimeConfig
import com.buckcast.domain.repository.BackupRepository
import com.buckcast.domain.repository.EntitlementRepository
import com.buckcast.domain.repository.FieldDataRepository
import com.buckcast.domain.repository.SettingsRepository
import com.buckcast.domain.repository.StandRepository
import com.buckcast.domain.repository.WeatherRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideBuckCastDatabase(
        @ApplicationContext context: Context,
    ): BuckCastDatabase = Room.databaseBuilder(
        context,
        BuckCastDatabase::class.java,
        "buckcast.db",
    )
        .addMigrations(BuckCastDatabase.MIGRATION_1_2)
        .build()

    @Provides
    fun provideStandDao(db: BuckCastDatabase): StandDao = db.standDao()

    @Provides
    fun provideWaypointDao(db: BuckCastDatabase): WaypointDao = db.waypointDao()

    @Provides
    fun provideTrackDao(db: BuckCastDatabase): TrackDao = db.trackDao()

    @Provides
    fun provideFieldLogDao(db: BuckCastDatabase): FieldLogDao = db.fieldLogDao()

    @Provides
    fun provideSightingDao(db: BuckCastDatabase): SightingDao = db.sightingDao()

    @Provides
    fun provideWeatherCacheDao(db: BuckCastDatabase): WeatherCacheDao = db.weatherCacheDao()

    @Provides
    fun provideWeaponProfileDao(db: BuckCastDatabase): WeaponProfileDao = db.weaponProfileDao()

    @Provides
    fun provideAppSettingsDao(db: BuckCastDatabase): AppSettingsDao = db.appSettingsDao()

    @Provides
    fun provideEntitlementCacheDao(db: BuckCastDatabase): EntitlementCacheDao = db.entitlementCacheDao()

    @Provides
    @Singleton
    fun provideHttpClient(json: Json): HttpClient = HttpClient(Android) {
        engine {
            connectTimeout = 15_000
            socketTimeout = 15_000
        }
        install(ContentNegotiation) {
            json(json)
        }
    }

    @Provides
    @Singleton
    fun provideOpenMeteoClient(
        httpClient: HttpClient,
        json: Json,
    ): OpenMeteoClient = OpenMeteoClient(httpClient, json)

    @Provides
    @Singleton
    fun provideEntityMappers(json: Json): EntityMappers = EntityMappers(json)

    @Provides
    @Singleton
    fun provideStandRepository(
        standDao: StandDao,
        mappers: EntityMappers,
    ): StandRepository = StandRepositoryImpl(standDao, mappers)

    @Provides
    @Singleton
    fun provideFieldDataRepository(
        waypointDao: WaypointDao,
        trackDao: TrackDao,
        fieldLogDao: FieldLogDao,
        sightingDao: SightingDao,
        weaponProfileDao: WeaponProfileDao,
        mappers: EntityMappers,
    ): FieldDataRepository = FieldDataRepositoryImpl(
        waypointDao = waypointDao,
        trackDao = trackDao,
        fieldLogDao = fieldLogDao,
        sightingDao = sightingDao,
        weaponProfileDao = weaponProfileDao,
        mappers = mappers,
    )

    @Provides
    @Singleton
    fun provideSettingsRepository(
        appSettingsDao: AppSettingsDao,
        mappers: EntityMappers,
    ): SettingsRepository = SettingsRepositoryImpl(appSettingsDao, mappers)

    @Provides
    @Singleton
    fun provideWeatherRepository(
        weatherCacheDao: WeatherCacheDao,
        openMeteoClient: OpenMeteoClient,
        json: Json,
    ): WeatherRepository = WeatherRepositoryImpl(weatherCacheDao, openMeteoClient, json)

    @Provides
    @Singleton
    fun provideBackupRepository(
        database: BuckCastDatabase,
        json: Json,
    ): BackupRepository = BackupRepositoryImpl(database, json)

    @Provides
    @Singleton
    fun provideWeatherSyncScheduler(
        @ApplicationContext context: Context,
    ): WeatherSyncScheduler = WeatherSyncScheduler(context)

    @Provides
    @Singleton
    fun provideBillingGateway(
        @ApplicationContext context: Context,
        runtimeConfig: AppRuntimeConfig,
    ): BillingGateway = RevenueCatBillingGateway(context, runtimeConfig)

    @Provides
    @Singleton
    fun provideEntitlementRepository(
        entitlementCacheDao: EntitlementCacheDao,
        billingGateway: BillingGateway,
        runtimeConfig: AppRuntimeConfig,
    ): EntitlementRepository = EntitlementRepositoryImpl(
        cacheDao = entitlementCacheDao,
        billingGateway = billingGateway,
        runtimeConfig = runtimeConfig,
    )
}
