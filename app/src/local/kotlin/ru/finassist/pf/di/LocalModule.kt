package ru.finassist.pf.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.BuildConfig
import javax.inject.Named

/**
 * Flavor `local` (FIN-120): real HTTP client (`:core:network`) against a bank-bff on the developer's machine
 * (`pf.apiBaseUrl`). Flags and tracking are the local providers of `mock` (`:providers:toggles-local`,
 * `:providers:tracking-log`): no RuStore Remote Config or MyTracker, so no production keys are needed and no event
 * of a local build reaches production analytics.
 */
@Module
@InstallIn(SingletonComponent::class)
object LocalModule {
    @Provides @Named("apiBaseUrl")
    fun apiBaseUrl(): String = BuildConfig.API_BASE_URL

    @Provides @Named("httpLogging")
    fun httpLogging(): Boolean = BuildConfig.DEBUG
}
