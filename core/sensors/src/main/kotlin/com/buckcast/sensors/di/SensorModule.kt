package com.buckcast.sensors.di

import android.content.Context
import android.hardware.SensorManager
import com.buckcast.sensors.BarometerSensor
import com.buckcast.sensors.LocationProvider
import com.buckcast.sensors.RotationVectorCompass
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SensorModule {

    @Provides
    @Singleton
    fun provideSensorManager(
        @ApplicationContext context: Context,
    ): SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Provides
    @Singleton
    fun provideFusedLocationProviderClient(
        @ApplicationContext context: Context,
    ): FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)

    @Provides
    @Singleton
    fun provideRotationVectorCompass(
        @ApplicationContext context: Context,
        sensorManager: SensorManager,
    ): RotationVectorCompass = RotationVectorCompass(context, sensorManager)

    @Provides
    @Singleton
    fun provideLocationProvider(
        fusedClient: FusedLocationProviderClient,
    ): LocationProvider = LocationProvider(fusedClient)

    @Provides
    @Singleton
    fun provideBarometerSensor(
        sensorManager: SensorManager,
    ): BarometerSensor = BarometerSensor(sensorManager)
}
