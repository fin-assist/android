package ru.finassist.pf.core.toggles.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import javax.inject.Inject
import javax.inject.Singleton

private val Context.flagStore: DataStore<Preferences> by preferencesDataStore("feature_flags")

/**
 * Flags for the mock flavor: code defaults overridden by values stored on the device (edited from the debug menu).
 * Reads are synchronous against a cached snapshot so screens never wait for a flag.
 */
@Singleton
class LocalFeatureFlags @Inject constructor(@ApplicationContext private val context: Context) : FeatureFlags {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshot: StateFlow<Map<String, Boolean>> = context.flagStore.data
        .map { prefs -> prefs.asMap().mapNotNull { (k, v) -> (v as? Boolean)?.let { k.name to it } }.toMap() }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override fun isEnabled(flag: Flag): Boolean = snapshot.value[flag.key] ?: flag.default

    override fun observe(flag: Flag): Flow<Boolean> = snapshot.map { it[flag.key] ?: flag.default }.distinctUntilChanged()

    override suspend fun refresh() { snapshot.first() }

    override suspend fun setUser(userId: String?) = Unit

    /** Debug menu: override or clear (null) a flag. */
    suspend fun override(flag: Flag, value: Boolean?) {
        context.flagStore.edit { prefs ->
            val key = booleanPreferencesKey(flag.key)
            if (value == null) prefs.remove(key) else prefs[key] = value
        }
    }

    fun overrides(): Map<String, Boolean> = snapshot.value
}
