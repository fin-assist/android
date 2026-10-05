package ru.finassist.pf.core.mockbackend.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.Interceptor
import ru.finassist.pf.core.mockbackend.engine.MockServer
import ru.finassist.pf.core.mockbackend.http.MockBackendInterceptor
import ru.finassist.pf.core.network.di.BackendInterceptor
import javax.inject.Singleton

/**
 * Wires the in-process backend into the OkHttp chain of the mock flavor.
 * Preloaded data: `assets/private/statement.ofx` (your real export, gitignored) or `assets/fixture/statement.ofx`.
 */
@Module
@InstallIn(SingletonComponent::class)
object MockBackendModule {

    /** The only "existing" user of the mock; any other number goes through registration. */
    const val EXISTING_PHONE = "+79990000001"

    @Provides
    @Singleton
    fun mockServer(@ApplicationContext context: Context): MockServer {
        val preloaded = listOf("private/statement.ofx", "fixture/statement.ofx").firstNotNullOfOrNull { path ->
            runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()?.let { path.substringAfterLast('/') to it }
        }
        return MockServer(MockServer.Config(existingPhones = setOf(EXISTING_PHONE), preloaded = preloaded, callDelaySeconds = 5, accessTokenTtlSeconds = 120))
    }

    @Provides
    @Singleton
    fun mockInterceptor(server: MockServer): MockBackendInterceptor = MockBackendInterceptor(server)

    @Provides
    @IntoSet
    @BackendInterceptor
    fun backendInterceptor(mock: MockBackendInterceptor): Interceptor = mock
}
