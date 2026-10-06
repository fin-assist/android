package ru.finassist.pf.feature.profile.impl.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.StatementsSummary
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.storage.AppPreferences
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.statements.api.StatementsRepository
import java.util.TimeZone
import javax.inject.Inject

data class ProfileFlags(val assistant: Boolean, val deleteAccount: Boolean, val upload: Boolean)

data class ProfileUiState(
    val loading: Boolean = true,
    val offline: Boolean = false,
    val profile: Profile? = null,
    val statements: StatementsSummary? = null,
    val statementsLoaded: Boolean = false,
    val limit: AssistantLimit? = null,
    val theme: Theme = Theme.SYSTEM,
    val themeSheet: Boolean = false,
    val askLogout: Boolean = false,
    val loggingOut: Boolean = false,
    val consentBusy: Boolean = false,
    val snackbar: String? = null,
    val flags: ProfileFlags,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileApi: ProfileApi,
    private val statementsApi: StatementsApi,
    private val assistantApi: AssistantApi,
    private val preferences: AppPreferences,
    private val session: SessionRepository,
    statementsRepository: StatementsRepository,
    private val featureFlags: FeatureFlags,
    private val tracker: Tracker,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState(flags = readFlags()))

    private fun readFlags() = ProfileFlags(
        assistant = featureFlags.isEnabled(Flag.ASSISTANT),
        deleteAccount = featureFlags.isEnabled(Flag.PROFILE_DELETE_ACCOUNT),
        upload = featureFlags.isEnabled(Flag.STATEMENTS_UPLOAD),
    )
    val state: StateFlow<ProfileUiState> = _state

    init {
        viewModelScope.launch {
            preferences.theme.collect { code -> _state.update { it.copy(theme = Theme.entries.firstOrNull { t -> t.name.equals(code, true) } ?: Theme.SYSTEM) } }
        }
        viewModelScope.launch { statementsRepository.events.collect { loadStatements() } }
        viewModelScope.launch { featureFlags.changes.collect { _state.update { it.copy(flags = readFlags()) } } }
    }

    /** Called on every resume: the consent switch and the limit may have changed on other screens. */
    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.profile == null, offline = false) }
            try {
                val p = profileApi.getProfile()
                _state.update { it.copy(loading = false, profile = p) }
                syncFromServer(p)
            } catch (e: AppError) {
                _state.update { it.copy(loading = false, offline = it.profile == null) }
            }
        }
        loadStatements()
        // The limit is requested only when the assistant is on (plan, decision 5).
        if (_state.value.flags.assistant) {
            viewModelScope.launch { runCatching { assistantApi.getLimit() }.onSuccess { l -> _state.update { it.copy(limit = l) } } }
        }
    }

    private fun loadStatements() {
        viewModelScope.launch {
            runCatching { statementsApi.listStatements() }.onSuccess { list -> _state.update { it.copy(statements = list.summary, statementsLoaded = true) } }
        }
    }

    /**
     * The local theme drives rendering (works before sign-in and offline); the server copy is adopted only
     * when there is no local choice yet. The device time zone is pushed when it changed (api.md 2.2).
     */
    private suspend fun syncFromServer(p: Profile) {
        if (preferences.theme.first() == null) preferences.setTheme(p.theme.name.lowercase())
        val tz = TimeZone.getDefault().id
        if (p.timezone != tz) runCatching { profileApi.updateProfile(ProfileUpdate(timezone = tz)) }.onSuccess { np -> _state.update { it.copy(profile = np) } }
    }

    fun openThemeSheet(open: Boolean) = _state.update { it.copy(themeSheet = open) }

    fun setTheme(theme: Theme) {
        _state.update { it.copy(themeSheet = false) }
        viewModelScope.launch {
            preferences.setTheme(theme.name.lowercase())
            tracker.track(Events.PROFILE_THEME_CHANGED, mapOf("theme" to theme.name.lowercase()))
            runCatching { profileApi.updateProfile(ProfileUpdate(theme = theme)) }
        }
    }

    /** Switch off: revoke without a dialog (api.md 2.4). Switch on goes through the consent screen. */
    fun revokeConsent() {
        viewModelScope.launch {
            _state.update { it.copy(consentBusy = true) }
            try {
                profileApi.revokeAssistantConsent()
                tracker.track(Events.ASSISTANT_CONSENT_REVOKED)
                _state.update { s -> s.copy(consentBusy = false, profile = s.profile?.let { it.copy(assistantConsent = it.assistantConsent.copy(granted = false)) }) }
            } catch (e: AppError) {
                _state.update { it.copy(consentBusy = false, snackbar = if (e is AppError.Offline) "Нет сети — согласие не отозвано" else "Не получилось. Попробуйте ещё раз") }
            }
        }
    }

    fun askLogout(show: Boolean) = _state.update { it.copy(askLogout = show) }

    fun logout() {
        viewModelScope.launch {
            _state.update { it.copy(loggingOut = true) }
            session.signOut(reason = AuthRoutes.Phone.REASON_LOGGED_OUT)
        }
    }

    fun consumeSnackbar() = _state.update { it.copy(snackbar = null) }
}
