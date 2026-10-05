package ru.finassist.pf.feature.profile.impl.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.network.api.PfApi
import ru.finassist.pf.core.network.client.apiCall
import ru.finassist.pf.core.network.dto.ProfileDto
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flags
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.assistant.api.AssistantLimitRepository
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.profile.api.AppTheme
import ru.finassist.pf.feature.profile.impl.data.ThemeRepositoryImpl
import ru.finassist.pf.feature.statements.api.StatementsRepository
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val api: PfApi,
    private val session: SessionRepository,
    private val appLock: AppLock,
    private val statements: StatementsRepository,
    private val assistantLimit: AssistantLimitRepository,
    private val themeRepo: ThemeRepositoryImpl,
    private val flags: FeatureFlags,
    private val tracker: Tracker,
) : ViewModel() {

    data class UiState(
        val profile: ProfileDto? = null,
        val loadError: AppError? = null,
        val statementText: String = "…",
        val assistantText: String? = null,
        val assistantConsent: Boolean = false,
        val biometricEnabled: Boolean = false,
        val theme: AppTheme = AppTheme.System,
        val showThemeSheet: Boolean = false,
        val showDeleteDialog: Boolean = false,
        val deleting: Boolean = false,
        val deleteError: AppError? = null,
        val assistantEnabled: Boolean = true,
        val deleteEnabled: Boolean = true,
        val uploadEnabled: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch { themeRepo.theme.collect { t -> _state.update { it.copy(theme = t) } } }
        viewModelScope.launch { appLock.biometricEnabled.collect { b -> _state.update { it.copy(biometricEnabled = b) } } }
        viewModelScope.launch {
            appLock.confirmations.collect { reason -> if (reason == CONFIRM_DELETE) _state.update { it.copy(showDeleteDialog = true, deleteError = null) } }
        }
        refreshFlags()
        load()
    }

    fun refreshFlags() = _state.update {
        it.copy(assistantEnabled = flags.isEnabled(Flags.assistant), deleteEnabled = flags.isEnabled(Flags.profileDeleteAccount), uploadEnabled = flags.isEnabled(Flags.statementsUpload))
    }

    fun load() = viewModelScope.launch {
        try {
            val p = withContext(Dispatchers.IO) { apiCall { api.getProfile() } }
            themeRepo.adoptServerTheme(p.theme)
            _state.update { it.copy(profile = p, loadError = null, assistantConsent = p.assistantConsent.granted) }
        } catch (e: AppError) { _state.update { it.copy(loadError = e) } }
        runCatching { statements.summary() }.onSuccess { s ->
            _state.update { it.copy(statementText = s?.let { "${plural(it.uploadCount.toLong(), "файл", "файла", "файлов")} · ${plural(it.operationCount.toLong(), "операция", "операции", "операций")}" } ?: "Не загружена") }
        }
        if (_state.value.assistantEnabled) runCatching { assistantLimit.limit() }.onSuccess { l ->
            _state.update { it.copy(assistantText = if (l.remaining >= l.dailyMax) "${l.dailyMax} вопросов в день" else "осталось ${l.remaining} из ${l.dailyMax}") }
        }
    }

    fun onAssistantConsentToggle(enabled: Boolean) = viewModelScope.launch {
        // Turning on happens on the consent screen (api.md 2.3); the switch only revokes.
        if (!enabled) {
            runCatching { withContext(Dispatchers.IO) { apiCall { api.revokeAssistantConsent() } } }
                .onSuccess { _state.update { it.copy(assistantConsent = false) }; tracker.track("profile.assistant_consent.revoked") }
        }
    }

    fun onThemeSheet(show: Boolean) = _state.update { it.copy(showThemeSheet = show) }
    fun onTheme(theme: AppTheme) = viewModelScope.launch { themeRepo.set(theme); _state.update { it.copy(showThemeSheet = false) }; tracker.track("profile.theme.changed", mapOf("theme" to theme.name.lowercase())) }

    fun logout() = viewModelScope.launch { tracker.track("profile.logout"); session.logout() }

    fun onDeleteDismiss() = _state.update { it.copy(showDeleteDialog = false, deleteError = null) }

    fun deleteAccount() = viewModelScope.launch {
        _state.update { it.copy(deleting = true, deleteError = null) }
        try {
            withContext(Dispatchers.IO) { apiCall { api.deleteAccount() } }
            tracker.track("profile.account.deleted")
            session.onAccountDeleted()
        } catch (e: AppError) {
            _state.update { it.copy(deleting = false, deleteError = e) }
        }
    }

    private fun plural(n: Long, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        val form = when { m100 in 11..14 -> many; m10 == 1L -> one; m10 in 2..4 -> few; else -> many }
        return "${n.toString().reversed().chunked(3).joinToString(" ").reversed()} $form"
    }

    companion object { const val CONFIRM_DELETE = "delete_account" }
}
