package ru.finassist.pf.mock

import android.content.Context
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.PfApi
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.mock.api.MockAnalyticsApi
import ru.finassist.pf.mock.api.MockAssistantApi
import ru.finassist.pf.mock.api.MockAuthApi
import ru.finassist.pf.mock.api.MockOperationsApi
import ru.finassist.pf.mock.api.MockProfileApi
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.ofx.OfxParser

/**
 * Wires the fake services together and preloads the statement. The private file
 * (`app/src/mock/assets/private/statement.ofx`, git-ignored) wins over the bundled anonymized fixture.
 * A session left in the [TokenStore] from a previous run is restored so a cold start does not sign the
 * tester out — the real server would still know the refresh token.
 */
class MockPfApi(
    context: Context,
    clock: Clock,
    tokenStore: TokenStore,
    config: MockConfig = MockConfig(),
) : PfApi {
    val backend = MockBackend(config, clock)

    override val auth = MockAuthApi(backend)
    override val profile = MockProfileApi(backend)
    override val statements = MockStatementsApi(backend)
    override val operations = MockOperationsApi(backend)
    override val categories = operations
    override val analytics = MockAnalyticsApi(backend)
    override val assistant = MockAssistantApi(backend)

    init {
        val appContext = context.applicationContext
        backend.scope.launch {
            try {
                tokenStore.current()?.let { session ->
                    backend.mutex.withLock {
                        backend.profile = backend.emptyProfile(config.existingPhone, session.userId)
                    }
                }
                if (config.preloadStatement) preload(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "mock preload failed", e)
            } finally {
                backend.ready.complete(Unit)
            }
        }
    }

    private suspend fun preload(context: Context) {
        val (name, text) = listOf(PRIVATE_ASSET, FIXTURE_ASSET).firstNotNullOfOrNull { path ->
            runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()?.let { path to it }
        } ?: run {
            Log.w(TAG, "no statement asset found; the mock starts empty")
            return
        }
        val doc = OfxParser.parse(text)
        val uploadedAt = backend.now().minusDays(1)
        statements.startUpload(fileName = name.substringAfterLast('/'), doc = doc, uploadedAt = uploadedAt, instant = true)
        Log.i(TAG, "preloaded $name: ${doc.transactions.size} operations, ${doc.accounts.size} accounts, ${doc.unreadLines.size} unread")
    }

    private companion object {
        const val TAG = "PfMock"
        const val PRIVATE_ASSET = "private/statement.ofx"
        const val FIXTURE_ASSET = "statements/fixture.ofx"
    }
}
