package ru.finassist.pf.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.BuildConfig
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.StaticFlags
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.NoopCrashReporter
import ru.finassist.pf.core.tracking.NoopTracker
import ru.finassist.pf.core.tracking.Tracker
import javax.inject.Named
import javax.inject.Singleton

/** Flavor `prod`: real HTTP client (`:core:network`). */
@Module
@InstallIn(SingletonComponent::class)
object ProdModule {
    @Provides @Named("apiBaseUrl")
    fun apiBaseUrl(): String = BuildConfig.API_BASE_URL

    @Provides @Named("httpLogging")
    fun httpLogging(): Boolean = BuildConfig.DEBUG

    // Until stage 8 (RuStore Remote Config, MyTracker, AppMetrica): code defaults and no-op tracking.
    @Provides @Singleton
    fun flags(): FeatureFlags = StaticFlags()

    @Provides @Singleton
    fun tracker(): Tracker = NoopTracker

    @Provides @Singleton
    fun crashReporter(): CrashReporter = NoopCrashReporter
}
