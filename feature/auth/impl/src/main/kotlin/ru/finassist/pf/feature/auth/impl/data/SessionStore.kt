package ru.finassist.pf.feature.auth.impl.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sessionStore: DataStore<Preferences> by preferencesDataStore("session")

/** Non-secret session facts: user id and phone (the phone is shown on the lock screen title). */
@Singleton
class SessionStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val userIdKey = stringPreferencesKey("user_id")
    private val phoneKey = stringPreferencesKey("phone")

    val userId: Flow<String?> = context.sessionStore.data.map { it[userIdKey] }
    val phone: Flow<String?> = context.sessionStore.data.map { it[phoneKey] }

    suspend fun set(userId: String, phone: String) = context.sessionStore.edit { it[userIdKey] = userId; it[phoneKey] = phone }
    suspend fun clear() = context.sessionStore.edit { it.clear() }
}
