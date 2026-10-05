package ru.finassist.pf.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.BuildConfig
import javax.inject.Named

/** Flavor `prod`: real HTTP client (`:core:network`). Toggles/tracking SDK providers arrive in stage 8. */
@Module
@InstallIn(SingletonComponent::class)
object ProdModule {
    @Provides @Named("apiBaseUrl")
    fun apiBaseUrl(): String = BuildConfig.API_BASE_URL

    @Provides @Named("httpLogging")
    fun httpLogging(): Boolean = BuildConfig.DEBUG
}
