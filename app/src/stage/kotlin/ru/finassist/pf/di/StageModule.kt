package ru.finassist.pf.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.BuildConfig
import javax.inject.Named

/** Stage HTTP/SSE client and SDK providers. Only stage-specific build properties supply SDK keys. */
@Module
@InstallIn(SingletonComponent::class)
object StageModule {
    @Provides @Named("apiBaseUrl")
    fun apiBaseUrl(): String = BuildConfig.API_BASE_URL

    @Provides @Named("httpLogging")
    fun httpLogging(): Boolean = BuildConfig.DEBUG

    @Provides @Named("rustoreRemoteConfigAppId")
    fun rustoreAppId(): String = BuildConfig.RUSTORE_REMOTE_CONFIG_APP_ID

    @Provides @Named("mytrackerSdkKey")
    fun mytrackerKey(): String = BuildConfig.MYTRACKER_SDK_KEY
}
