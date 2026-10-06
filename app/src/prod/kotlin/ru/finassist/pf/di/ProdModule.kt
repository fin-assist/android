package ru.finassist.pf.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.BuildConfig
import javax.inject.Named

/**
 * Flavor `prod`: real HTTP client (`:core:network`), RuStore Remote Config, MyTracker, AppMetrica
 * (`:providers:*`). Keys come from BuildConfig; blank keys keep the SDKs off (local builds).
 */
@Module
@InstallIn(SingletonComponent::class)
object ProdModule {
    @Provides @Named("apiBaseUrl")
    fun apiBaseUrl(): String = BuildConfig.API_BASE_URL

    @Provides @Named("httpLogging")
    fun httpLogging(): Boolean = BuildConfig.DEBUG

    @Provides @Named("rustoreRemoteConfigAppId")
    fun rustoreAppId(): String = BuildConfig.RUSTORE_REMOTE_CONFIG_APP_ID

    @Provides @Named("mytrackerSdkKey")
    fun mytrackerKey(): String = BuildConfig.MYTRACKER_SDK_KEY

    @Provides @Named("appmetricaApiKey")
    fun appmetricaKey(): String = BuildConfig.APPMETRICA_API_KEY
}
