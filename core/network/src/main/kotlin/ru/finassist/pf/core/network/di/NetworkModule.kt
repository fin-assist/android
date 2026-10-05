package ru.finassist.pf.core.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ru.finassist.pf.core.network.api.PfApi
import ru.finassist.pf.core.network.client.AuthHeaderInterceptor
import ru.finassist.pf.core.network.client.PfJson
import ru.finassist.pf.core.network.client.RequestIdInterceptor
import ru.finassist.pf.core.network.client.SessionTokens
import ru.finassist.pf.core.network.client.TokenAuthenticator
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Marks interceptors the flavor wants installed first in the application chain.
 * The mock flavor contributes its in-process backend this way: it answers every request itself, so the
 * rest of the stack (auth header, authenticator, SSE reader, error mapping) runs unchanged against it.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackendInterceptor

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @Named("refreshClient")
    fun refreshClient(@BackendInterceptor backend: Set<@JvmSuppressWildcards Interceptor>): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply { backend.forEach(::addInterceptor) }
            .addNetworkInterceptor(RequestIdInterceptor())
            .build()

    @Provides
    @Singleton
    fun okHttpClient(
        tokens: SessionTokens,
        @Named("apiBaseUrl") baseUrl: String,
        @Named("refreshClient") refreshClient: OkHttpClient,
        @BackendInterceptor backend: Set<@JvmSuppressWildcards Interceptor>,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // SSE streams idle between heartbeats (15 s); the read timeout must exceed that with margin.
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .apply { backend.forEach(::addInterceptor) }
            .addInterceptor(AuthHeaderInterceptor(tokens))
            .addNetworkInterceptor(RequestIdInterceptor())
            .authenticator(TokenAuthenticator(tokens, baseUrl, refreshClient))
            .build()

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, @Named("apiBaseUrl") baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(PfJson.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides
    @Singleton
    fun pfApi(retrofit: Retrofit): PfApi = retrofit.create(PfApi::class.java)
}
