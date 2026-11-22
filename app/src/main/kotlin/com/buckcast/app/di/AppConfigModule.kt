package com.buckcast.app.di

import com.buckcast.app.BuildConfig
import com.buckcast.domain.model.AppRuntimeConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppConfigModule {

    @Provides
    @Singleton
    fun provideAppRuntimeConfig(): AppRuntimeConfig = AppRuntimeConfig(
        isDebugBuild = BuildConfig.DEBUG,
        revenueCatPublicKey = BuildConfig.REVENUECAT_PUBLIC_KEY,
        mapTilerApiKey = BuildConfig.MAPTILER_API_KEY,
    )
}
