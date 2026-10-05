package ru.finassist.pf.feature.statements.impl.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pendingUploadStore: DataStore<Preferences> by preferencesDataStore("pf_statements")

/**
 * Survives process death during an import: the `Idempotency-Key` of the file being sent (so a retried POST returns
 * the same upload instead of creating a second one, api.md «Идемпотентность») and the id of an upload whose
 * progress stream was open, so the screen can re-attach after the app was killed mid-parse.
 */
@Singleton
class PendingUploadStore @Inject constructor(@ApplicationContext private val context: Context) {
    data class Pending(val key: String, val fileName: String, val uploadId: String?)

    private val keyPref = stringPreferencesKey("idempotency_key")
    private val namePref = stringPreferencesKey("file_name")
    private val uploadPref = stringPreferencesKey("upload_id")

    suspend fun read(): Pending? {
        val p = context.pendingUploadStore.data.first()
        val key = p[keyPref] ?: return null
        return Pending(key, p[namePref] ?: "", p[uploadPref])
    }

    suspend fun start(key: String, fileName: String) = context.pendingUploadStore.edit {
        it[keyPref] = key; it[namePref] = fileName; it.remove(uploadPref)
    }

    suspend fun accepted(uploadId: String) = context.pendingUploadStore.edit { it[uploadPref] = uploadId }

    suspend fun clear() = context.pendingUploadStore.edit { it.remove(keyPref); it.remove(namePref); it.remove(uploadPref) }
}
