package ru.finassist.pf.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.BuildConfig
import javax.inject.Named
import javax.inject.Singleton

/** App-level wiring that depends on the build flavour. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** `mock` flavour points at a fake host answered in-process by the mock backend interceptor. */
    @Provides @Singleton @Named("apiBaseUrl")
    fun apiBaseUrl(): String = BuildConfig.API_BASE_URL
}
