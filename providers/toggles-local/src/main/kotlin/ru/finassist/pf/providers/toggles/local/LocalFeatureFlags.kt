package ru.finassist.pf.providers.toggles.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.FlagOverrides
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Flags for the `mock` flavor: `assets/flags.json` (`{"assistant": false}`) overridden by values set from the
 * debug screen (DataStore). Mirrors the provider contract: values are snapshotted, [changes] fires on every
 * update, [setUser] / [refresh] are no-ops.
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

    override val overrides: Flow<Map<Flag, Boolean>> = dataStore.data.map { prefs -> prefs.toOverrides() }

    init {
        assetValues = readAsset()
        overrides.onEach { values ->
            overrideValues = values
            changesFlow.tryEmit(Unit)
        }.launchIn(scope)
    }

    override fun isEnabled(flag: Flag): Boolean =
        overrideValues[flag] ?: assetValues[flag] ?: flag.defaultValue

    override val changes: Flow<Unit> = changesFlow

    override suspend fun setUser(userId: String?) = Unit

    override suspend fun refresh() {
        assetValues = readAsset()
        changesFlow.tryEmit(Unit)
    }

    override suspend fun set(flag: Flag, enabled: Boolean?) {
        dataStore.edit { prefs ->
            val key = booleanPreferencesKey(PREFIX + flag.key)
            if (enabled == null) prefs.remove(key) else prefs[key] = enabled
        }
    }

    override suspend fun clear() {
        dataStore.edit { prefs ->
            Flag.entries.forEach { prefs.remove(booleanPreferencesKey(PREFIX + it.key)) }
        }
    }

    /** Blocks until the first DataStore read so flags are stable before the first screen renders. */
    suspend fun awaitReady() {
        overrideValues = dataStore.data.first().toOverrides()
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
    }
}
