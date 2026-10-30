package ru.finassist.pf.core.network.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import okhttp3.Interceptor
import javax.inject.Named

/** Optional application interceptors. Ordinary HTTP builds contribute none. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class ClientInterceptors {
    @Multibinds
    @Named("clientInterceptors")
    abstract fun interceptors(): Set<Interceptor>
}
