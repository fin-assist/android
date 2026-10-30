package ru.finassist.pf.core.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import ru.finassist.pf.core.api.AnalyticsApi
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.CategoriesApi
import ru.finassist.pf.core.api.OperationsApi
import ru.finassist.pf.core.api.PfApi
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.network.AnalyticsService
import ru.finassist.pf.core.network.AssistantService
import ru.finassist.pf.core.common.availability.ClientVersion
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.network.AndroidElapsedClock
import ru.finassist.pf.core.network.AuthInterceptor
import ru.finassist.pf.core.network.ClientVersionInterceptor
import ru.finassist.pf.core.network.AuthService
import ru.finassist.pf.core.network.HttpAnalyticsApi
import ru.finassist.pf.core.network.HttpAssistantApi
import ru.finassist.pf.core.network.HttpAuthApi
import ru.finassist.pf.core.network.HttpOperationsApi
import ru.finassist.pf.core.network.HttpPfApi
import ru.finassist.pf.core.network.HttpProfileApi
import ru.finassist.pf.core.network.HttpStatementsApi
import ru.finassist.pf.core.network.OperationsService
import ru.finassist.pf.core.network.ProfileService
import ru.finassist.pf.core.network.RequestIdInterceptor
import ru.finassist.pf.core.network.ServiceGateInterceptor
import ru.finassist.pf.core.network.SseRequests
import ru.finassist.pf.core.network.StatementsService
import ru.finassist.pf.core.network.TlsFailureReporter
import ru.finassist.pf.core.network.TokenAuthenticator
import ru.finassist.pf.core.tracking.CrashReporter
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Base URL and debug logging are supplied by `:app` (`@Named("apiBaseUrl")`, `@Named("httpLogging")`), and so are the
 * [ClientVersion], the [ServiceGate], the [Clock] and the redirect policy (`@Named("httpFollowRedirects")`).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object NetworkModule {

    @Qualifier
    @Retention(AnnotationRetention.BINARY)
    annotation class Sse

    @Provides
    @Singleton
    fun baseUrl(@Named("apiBaseUrl") url: String): HttpUrl = url.toHttpUrl()

    /**
     * Client for refresh-token calls only: no authenticator, so a 401 on refresh cannot recurse. Every client is built
     * from it, so all of them report a refused server certificate (pinning, FIN-121) through [TlsFailureReporter];
     * SSE streams report through [SseRequests], whose OkHttp path drops the event listener.
     */
    @Provides
    @Singleton
    @Named("plain")
    fun plainClient(
        @Named("httpLogging") logging: Boolean,
        tlsFailures: TlsFailureReporter,
        version: ClientVersion,
        gate: ServiceGate,
        clock: Clock,
        elapsedClock: ElapsedClock = AndroidElapsedClock,
        @Named("clientInterceptors") clientInterceptors: Set<@JvmSuppressWildcards Interceptor> = emptySet(),
        @Named("httpFollowRedirects") followRedirects: Boolean = true,
    ): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(followRedirects)
        .followSslRedirects(followRedirects)
        .connectTimeout(15, TimeUnit.SECONDS)
        .eventListenerFactory(tlsFailures.eventListenerFactory())
        .readTimeout(30, TimeUnit.SECONDS)
        // FIN-22: the version on every request (refresh included), and what the server says about it and maintenance.
        .addInterceptor(ClientVersionInterceptor(version))
        .addInterceptor(ServiceGateInterceptor(gate, clock, elapsedClock))
        .apply { clientInterceptors.forEach(::addInterceptor) }
        // Network interceptor: runs per attempt (auth retry, redirect), so every attempt gets a fresh X-Request-Id.
        .addNetworkInterceptor(RequestIdInterceptor())
        .apply { if (logging) addInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)) }
        .build()

    /** One reporter for every client and stream: each kind of refusal is reported once per process. */
    @Provides
    @Singleton
    fun tlsFailures(crashReporter: CrashReporter): TlsFailureReporter = TlsFailureReporter(crashReporter)

    @Provides
    @Singleton
    fun client(
        @Named("plain") plain: OkHttpClient,
        baseUrl: HttpUrl,
        tokenStore: TokenStore,
        tlsFailures: TlsFailureReporter,
        elapsedClock: ElapsedClock = AndroidElapsedClock,
    ): OkHttpClient {
        // The refresh call gets its own Dispatcher: requests waiting in TokenAuthenticator hold slots of the
        // main dispatcher (5 per host), and a refresh queued behind them would never start.
        // The total-time limit belongs here: a stuck refresh blocks every request waiting in the authenticator.
        // The main client has none — a 10 MB statement on a slow mobile link legitimately takes minutes.
        // AuthInterceptor here only strips the NoAuth marker (every auth endpoint carries it), so it never reaches the server.
        val refreshClient = plain.newBuilder()
            .dispatcher(Dispatcher())
            .callTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor(tokenStore))
            .build()
        val refreshApi = HttpAuthApi(retrofit(refreshClient, baseUrl).create(AuthService::class.java), SseRequests(baseUrl, refreshClient, tlsFailures))
        return plain.newBuilder()
            .addInterceptor(AuthInterceptor(tokenStore))
            .authenticator(TokenAuthenticator(tokenStore, elapsedClock) { key, refresh -> refreshApi.refreshTokens(key, refresh) })
            .build()
    }

    /** Same pipeline, no read timeout: SSE streams idle between heartbeats and live for minutes. */
    @Provides
    @Singleton
    @Sse
    fun sseClient(client: OkHttpClient): OkHttpClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Provides
    @Singleton
    fun retrofit(client: OkHttpClient, baseUrl: HttpUrl): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(ApiJson.asConverterFactory("application/json".toMediaType()))
        .build()

    @Provides
    @Singleton
    fun sseRequests(baseUrl: HttpUrl, @Sse client: OkHttpClient, tlsFailures: TlsFailureReporter): SseRequests =
        SseRequests(baseUrl, client, tlsFailures)

    @Provides @Singleton
    fun authApi(retrofit: Retrofit, sse: SseRequests): AuthApi = HttpAuthApi(retrofit.create(AuthService::class.java), sse)

    @Provides @Singleton
    fun profileApi(retrofit: Retrofit): ProfileApi = HttpProfileApi(retrofit.create(ProfileService::class.java))

    @Provides @Singleton
    fun statementsApi(retrofit: Retrofit, sse: SseRequests): StatementsApi =
        HttpStatementsApi(retrofit.create(StatementsService::class.java), sse)

    @Provides @Singleton
    fun operationsApi(retrofit: Retrofit): HttpOperationsApi = HttpOperationsApi(retrofit.create(OperationsService::class.java))

    @Provides @Singleton
    fun operations(api: HttpOperationsApi): OperationsApi = api

    @Provides @Singleton
    fun categories(api: HttpOperationsApi): CategoriesApi = api

    @Provides @Singleton
    fun analyticsApi(retrofit: Retrofit): AnalyticsApi = HttpAnalyticsApi(retrofit.create(AnalyticsService::class.java))

    @Provides @Singleton
    fun assistantApi(retrofit: Retrofit, sse: SseRequests): AssistantApi =
        HttpAssistantApi(retrofit.create(AssistantService::class.java), sse)

    @Provides @Singleton
    fun pfApi(
        auth: AuthApi,
        profile: ProfileApi,
        statements: StatementsApi,
        operations: OperationsApi,
        categories: CategoriesApi,
        analytics: AnalyticsApi,
        assistant: AssistantApi,
    ): PfApi = HttpPfApi(auth, profile, statements, operations, categories, analytics, assistant)
}
