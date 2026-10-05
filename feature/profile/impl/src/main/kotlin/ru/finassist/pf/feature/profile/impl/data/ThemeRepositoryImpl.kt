package ru.finassist.pf.feature.profile.impl.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.finassist.pf.core.network.api.PfApi
import ru.finassist.pf.core.network.dto.ProfileUpdateDto
import ru.finassist.pf.core.network.dto.ThemeDto
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.profile.api.AppTheme
import ru.finassist.pf.feature.profile.api.ThemeRepository
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeStore: DataStore<Preferences> by preferencesDataStore("theme")

/**
 * Local copy is the source of truth for rendering (needed before login and offline);
 * the server's `profile.theme` is updated best-effort after login (android-plan.md §6.1).
 */
@Singleton
class ThemeRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PfApi,
    private val session: SessionRepository,
) : ThemeRepository {
    private val key = stringPreferencesKey("theme")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val theme: Flow<AppTheme> = context.themeStore.data.map { prefs -> prefs[key]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() } ?: AppTheme.System }

    override suspend fun set(theme: AppTheme) {
        context.themeStore.edit { it[key] = theme.name }
        if (session.state.value is SessionState.LoggedIn) {
            scope.launch { runCatching { api.updateProfile(ProfileUpdateDto(theme = theme.toDto())) } }
        }
    }

    /** Called with the server value after the profile is loaded: adopts it only if nothing was chosen locally yet. */
    suspend fun adoptServerTheme(dto: ThemeDto) {
        context.themeStore.edit { prefs -> if (prefs[key] == null) prefs[key] = dto.toApp().name }
    }

    private fun AppTheme.toDto() = when (this) { AppTheme.System -> ThemeDto.system; AppTheme.Light -> ThemeDto.light; AppTheme.Dark -> ThemeDto.dark }
    private fun ThemeDto.toApp() = when (this) { ThemeDto.system -> AppTheme.System; ThemeDto.light -> AppTheme.Light; ThemeDto.dark -> AppTheme.Dark }
}
