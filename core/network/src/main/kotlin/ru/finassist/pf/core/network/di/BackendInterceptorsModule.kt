package ru.finassist.pf.core.network.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import okhttp3.Interceptor

@Module
@InstallIn(SingletonComponent::class)
abstract class BackendInterceptorsModule {
    @Multibinds
    @BackendInterceptor
    abstract fun backendInterceptors(): Set<Interceptor>
}
