package ru.finassist.pf.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import ru.finassist.pf.BuildConfig
import ru.finassist.pf.core.tracking.AppInitializer
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

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {
    /** SDK start-up hooks; the mock flavour contributes none. */
    @Multibinds abstract fun initializers(): Set<AppInitializer>
}
