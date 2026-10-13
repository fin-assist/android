package ru.finassist.pf.providers.toggles.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.FlagOverrides
import ru.finassist.pf.core.toggles.flagsVersion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Flags for the `mock` flavor: `assets/flags.json` (`{"assistant": false}`) overridden by values set from the
 * debug screen (DataStore). Mirrors the provider contract: values are snapshotted, [changes] fires when the
 * overrides read from the store change and on [refresh], [setUser] is a no-op.
 */
@Singleton
class LocalFeatureFlags @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>,
) : FeatureFlags, FlagOverrides {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile private var assetValues: Map<Flag, Boolean> = emptyMap()
    @Volatile private var overrideValues: Map<Flag, Boolean> = emptyMap()

    /**
     * The overrides as last read by [reload]; null until the first read. DataStore collectors cannot be trusted with
     * them: one that starts while a write is in flight can miss that write for good (FIN-87, FIN-89), e.g. the e2e
     * control link setting a flag right after the app starts. So nothing here publishes a collected value: every
     * value is a fresh read of the store, taken after each own write and whenever the store reports a change.
     */
    private val stored = MutableStateFlow<Map<Flag, Boolean>?>(null)

    /** Serializes [reload]: a read is published only after every read started before it. */
    private val reads = Mutex()

    override val overrides: Flow<Map<Flag, Boolean>> = stored.filterNotNull()

    init {
        assetValues = readAsset()
        // A trigger only (its values may be stale, see [stored]); it also takes the first read. A failed read (I/O)
        // would end the collection for good, so it starts over after a pause.
        scope.launch {
            dataStore.data
                .retryWhen { e, _ -> (e !is CancellationException).also { if (it) delay(READ_RETRY_MS) } }
                .collect { reloadUntilRead() }
        }
    }

    override fun isEnabled(flag: Flag): Boolean =
        overrideValues[flag] ?: assetValues[flag] ?: flag.defaultValue

    override val changes: Flow<Unit> = changesFlow

    /** Asset values with the debug overrides on top — what [isEnabled] actually answers from. */
    override val version: String
        get() = flagsVersion((assetValues + overrideValues).mapKeys { it.key.key })

    override suspend fun setUser(userId: String?) = Unit

    /** Re-reads the asset; [changes] fires unconditionally, as a remote provider's fetch would. */
    override suspend fun refresh() {
        assetValues = readAsset()
        changesFlow.tryEmit(Unit)
    }

    /** Answers with the new value as soon as it returns. */
    override suspend fun set(flag: Flag, enabled: Boolean?) {
        try {
            dataStore.edit { prefs ->
                val key = booleanPreferencesKey(PREFIX + flag.key)
                if (enabled == null) prefs.remove(key) else prefs[key] = enabled
            }
        } finally {
            reloadAfterWrite()
        }
    }

    /** Answers without overrides as soon as it returns. */
    override suspend fun clear() {
        try {
            dataStore.edit { prefs ->
                Flag.entries.forEach { prefs.remove(booleanPreferencesKey(PREFIX + it.key)) }
            }
        } finally {
            reloadAfterWrite()
        }
    }

    /** Reads the overrides once, so flags are stable before the first screen renders. */
    suspend fun awaitReady() {
        reload()
    }

    /** A failed read is retried in the background: the writer gets its own outcome now. */
    private suspend fun reloadAfterWrite() {
        if (!reload()) scheduleRetry()
    }

    /** The background retry after a write that failed to read back; one at a time, however many writes fail. */
    private var retry: Job? = null

    /** A write failed to read back while [retry] was running: it reads again once more before it ends. */
    private var retryAgain = false

    /**
     * Starts the retry, or has the running one read once more. Both sides decide under the same lock: a retry that
     * has just read cannot end while a later failed write counts on it.
     */
    private fun scheduleRetry() = synchronized(this) {
        if (retry != null) retryAgain = true else retry = scope.launch { retryUntilCurrent() }
    }

    private suspend fun retryUntilCurrent() {
        while (true) {
            reloadUntilRead()
            synchronized(this) {
                if (!retryAgain) {
                    retry = null
                    return
                }
                retryAgain = false
            }
        }
    }

    /**
     * Until a read lands, the overrides are behind the store, and no later change may come to fix it: a failed read
     * is retried, not left waiting for the next write.
     */
    private suspend fun reloadUntilRead() {
        while (!reload()) delay(READ_RETRY_MS)
    }

    /**
     * Reads the overrides afresh and publishes them; [changes] fires when they differ from the last published ones
     * (and on the first read). NonCancellable: a write that has landed is published even if its caller has gone away.
     * A failed read keeps the last values and does not throw: it runs after every own write, where it would replace
     * the write's own outcome, and in the trigger, which would stop. Returns whether the read landed.
     */
    private suspend fun reload(): Boolean = withContext(NonCancellable) {
        val changed = reads.withLock {
            val values = try {
                dataStore.data.first().toOverrides()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock null
            }
            overrideValues = values
            (stored.value != values).also { stored.value = values }
        } ?: return@withContext false
        if (changed) changesFlow.tryEmit(Unit)
        true
    }

    private fun Preferences.toOverrides(): Map<Flag, Boolean> = Flag.entries.mapNotNull { flag ->
        this[booleanPreferencesKey(PREFIX + flag.key)]?.let { flag to it }
    }.toMap()

    private fun readAsset(): Map<Flag, Boolean> = runCatching {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject.mapNotNull { (key, value) ->
            val flag = Flag.byKey(key) ?: return@mapNotNull null
            value.jsonPrimitive.booleanOrNull?.let { flag to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private companion object {
        const val ASSET = "flags.json"
        const val PREFIX = "flags.override."
        const val READ_RETRY_MS = 1_000L
    }
}
