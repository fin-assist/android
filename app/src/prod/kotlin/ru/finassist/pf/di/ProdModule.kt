package ru.finassist.pf.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.BuildConfig
import javax.inject.Named

/** SDK keys of the prod flavour; empty values come from `gradle.properties` placeholders until the accounts exist. */
@Module
@InstallIn(SingletonComponent::class)
object ProdModule {
    @Provides @Named("rustoreAppId") fun rustoreAppId(): String = BuildConfig.RUSTORE_APP_ID
    @Provides @Named("myTrackerId") fun myTrackerId(): String = BuildConfig.MYTRACKER_ID
    @Provides @Named("appMetricaKey") fun appMetricaKey(): String = BuildConfig.APPMETRICA_KEY
}
