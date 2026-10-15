package ru.finassist.pf.core.network

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import ru.finassist.pf.core.api.PfApi
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.common.availability.ClientVersion
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.time.SystemClock
import ru.finassist.pf.core.network.di.NetworkModule

/**
 * The production object graph of [NetworkModule] pointed at a [MockWebServer]: the same clients, interceptors,
 * authenticator and Retrofit setup the app gets from Hilt, so tests see what goes over the wire.
 */
internal class NetworkStack(
    server: MockWebServer,
    val tokenStore: TokenStore = InMemoryTokenStore(),
    val gate: ServiceGate = ServiceGate(),
    val version: ClientVersion = ClientVersion("0.2.7", 41),
) {
    private val baseUrl = NetworkModule.baseUrl(server.url("/").toString())
    val plain: OkHttpClient = NetworkModule.plainClient(logging = false, version = version, gate = gate, clock = SystemClock())
    val client: OkHttpClient = NetworkModule.client(plain, baseUrl, tokenStore)
    val sseClient: OkHttpClient = NetworkModule.sseClient(client)
    private val retrofit: Retrofit = NetworkModule.retrofit(client, baseUrl)
    private val sse: SseRequests = NetworkModule.sseRequests(baseUrl, sseClient)
    private val operationsApi = NetworkModule.operationsApi(retrofit)

    val api: PfApi = NetworkModule.pfApi(
        auth = NetworkModule.authApi(retrofit, sse),
        profile = NetworkModule.profileApi(retrofit),
        statements = NetworkModule.statementsApi(retrofit, sse),
        operations = NetworkModule.operations(operationsApi),
        categories = NetworkModule.categories(operationsApi),
        analytics = NetworkModule.analyticsApi(retrofit),
        assistant = NetworkModule.assistantApi(retrofit, sse),
    )
}
