package ru.finassist.pf.feature.profile.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.error.ErrorPresentation
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
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.api.BiometricAvailability
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.profile.impl.data.TimeZoneChecker
import ru.finassist.pf.feature.statements.api.StatementsRepository
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
    /** Biometric unlock on/off; null when the device has no biometrics. */
    val biometric: Boolean? = null,
    val flags: ProfileFlags,
    val errorPresentation: ErrorPresentation? = null,
    val requestRetryable: Boolean? = null,
    val retryReady: Boolean = true,
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
    appLock: AppLock,
    private val zoneChecker: TimeZoneChecker,
    private val elapsedClock: ElapsedClock,
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileUiState(flags = readFlags()))
    private val statementsRetryGate = RequestRetryGate(elapsedClock)
    private val limitRetryGate = RequestRetryGate(elapsedClock)
    private val consentRetryGate = RequestRetryGate(elapsedClock)
    private val themeRetryGate = RequestRetryGate(elapsedClock)
    private var blockedTheme: Theme? = null
    private var themeGeneration = 0L
    private var statementsGeneration = 0L
    private var profileGeneration = 0L

    private fun readFlags() = ProfileFlags(
        assistant = featureFlags.isEnabled(Flag.ASSISTANT),
        deleteAccount = featureFlags.isEnabled(Flag.PROFILE_DELETE_ACCOUNT),
        upload = featureFlags.isEnabled(Flag.STATEMENTS_UPLOAD),
    )
    private val retryGate = RequestRetryGate(elapsedClock)
    init { viewModelScope.launch { retryGate.readiness.collect { ready -> _state.update { it.copy(retryReady = ready) } } } }
    val state: StateFlow<ProfileUiState> = _state

    init {
        viewModelScope.launch {
            preferences.theme.collect { code -> _state.update { it.copy(theme = Theme.entries.firstOrNull { t -> t.name.equals(code, true) } ?: Theme.SYSTEM) } }
        }
        viewModelScope.launch {
            statementsRepository.events.collect {
                statementsGeneration++
                statementsRetryGate.clear()
                loadStatements()
            }
        }
        viewModelScope.launch { featureFlags.changes.collect { _state.update { it.copy(flags = readFlags()) } } }
        if (appLock.biometricAvailability() != BiometricAvailability.NONE) {
            viewModelScope.launch { appLock.biometricEnabled.collect { on -> _state.update { it.copy(biometric = on) } } }
        }
    }

    /** Called on every resume: the consent switch and the limit may have changed on other screens. */
    fun load() {
        if (retryGate.isReady()) {
            val generation = ++profileGeneration
            viewModelScope.launch {
                _state.update { it.copy(loading = it.profile == null, offline = false, errorPresentation = null, requestRetryable = null) }
                try {
                    val origin = zoneChecker.captureObservation()
                    val p = profileApi.getProfile()
                    if (generation != profileGeneration) return@launch
                    _state.update { it.copy(loading = false, profile = p) }
                    syncFromServer(p, origin)
                } catch (e: AppError) {
                    if (generation != profileGeneration) return@launch
                    retryGate.record(e)
                    _state.update { it.copy(loading = false, offline = it.profile == null, errorPresentation = e.presentation.takeUnless { e is AppError.Offline }, requestRetryable = e.requestRetryable) }
                }
            }
        }
        loadStatements()
        // The limit is requested only when the assistant is on (plan, decision 5).
        if (_state.value.flags.assistant && limitRetryGate.isReady()) {
            viewModelScope.launch { runCatching { assistantApi.getLimit() }.onSuccess { l -> _state.update { it.copy(limit = l) } }
                .onFailure { failure -> if (failure is AppError) {
                    limitRetryGate.record(failure)
                    if (failure !is AppError.Offline) _state.update { it.copy(errorPresentation = failure.presentation, snackbar = "Не получилось обновить лимит вопросов") }
                } } }
        }
    }

    /** A successful consent screen result is a confirmed server mutation, not an ordinary resume. */
    fun onConsentResult(result: String?) {
        if (result != "true") return
        changedProfileContext()
        consentRetryGate.clear()
        load()
    }

    private fun changedProfileContext() {
        profileGeneration++
        retryGate.clear()
        _state.update { it.copy(errorPresentation = null, requestRetryable = null) }
    }

    private fun loadStatements() {
        if (!statementsRetryGate.isReady()) return
        val generation = ++statementsGeneration
        viewModelScope.launch {
            runCatching { statementsApi.listStatements() }.onSuccess { list ->
                if (generation == statementsGeneration) _state.update { it.copy(statements = list.summary, statementsLoaded = true) }
            }.onFailure { failure ->
                if (generation == statementsGeneration && failure is AppError) {
                    statementsRetryGate.record(failure)
                    if (failure !is AppError.Offline) _state.update { it.copy(errorPresentation = failure.presentation, snackbar = "Не получилось обновить историю загрузок") }
                }
            }
        }
    }

    /**
     * The local theme drives rendering (works before sign-in and offline); the server copy is adopted only
     * when there is no local choice yet. The time zone is not sent from here: `TimeZoneSync` sends it once a
     * new device zone has held for 72 hours (FIN-91). The zone read here becomes the report zone (FIN-92): it may
     * have changed on the server since the start of the process.
     */
    private suspend fun syncFromServer(p: Profile, origin: TimeZoneChecker.Observation?) {
        if (preferences.theme.first() == null) preferences.setTheme(p.theme.name.lowercase())
        zoneChecker.observed(p, origin)
    }

    fun openThemeSheet(open: Boolean) = _state.update { it.copy(themeSheet = open) }

    fun setTheme(theme: Theme) {
        if (blockedTheme == theme && !themeRetryGate.isReady()) return
        val generation = ++themeGeneration
        if (blockedTheme != theme) themeRetryGate.clear()
        blockedTheme = theme
        _state.update { it.copy(themeSheet = false) }
        viewModelScope.launch {
            preferences.setTheme(theme.name.lowercase())
            tracker.track(Events.PROFILE_THEME_CHANGED, mapOf("theme" to theme.name.lowercase()))
            val origin = zoneChecker.captureObservation()
            runCatching { profileApi.updateProfile(ProfileUpdate(theme = theme)) }.onSuccess {
                if (generation == themeGeneration) zoneChecker.observed(it, origin)
            }.onFailure { failure ->
                if (generation == themeGeneration && failure is AppError) {
                    themeRetryGate.record(failure)
                    _state.update { it.copy(errorPresentation = failure.presentation.takeUnless { failure is AppError.Offline }, snackbar = "Не получилось сохранить тему. Попробуйте ещё раз") }
                }
            }
        }
    }

    /** Switch off: revoke without a dialog (api.md 2.4). Switch on goes through the consent screen. */
    fun revokeConsent() {
        if (!consentRetryGate.isReady()) return
        viewModelScope.launch {
            _state.update { it.copy(consentBusy = true, errorPresentation = null) }
            try {
                profileApi.revokeAssistantConsent()
                changedProfileContext()
                consentRetryGate.clear()
                tracker.track(Events.ASSISTANT_CONSENT_REVOKED)
                _state.update { s -> s.copy(consentBusy = false, profile = s.profile?.let { p -> p.assistantConsent?.let { c -> p.withConsent(c.copy(granted = false)) } ?: p }) }
            } catch (e: AppError) {
                consentRetryGate.record(e)
                _state.update { it.copy(consentBusy = false, errorPresentation = e.presentation.takeUnless { e is AppError.Offline }, snackbar = if (e is AppError.Offline) "Нет сети — согласие не отозвано" else "Не получилось. Попробуйте ещё раз") }
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

    fun consumeSnackbar() = _state.update { it.copy(snackbar = null, errorPresentation = null) }
}
