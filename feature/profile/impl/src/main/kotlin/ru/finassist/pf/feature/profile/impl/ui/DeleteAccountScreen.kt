package ru.finassist.pf.feature.profile.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.designsystem.components.ErrorMessage
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfDialog
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.SessionRepository
import javax.inject.Inject

/** The deletion dialog over the profile: shown after the passcode, busy while deleting, an error inside it. */
data class DeleteUiState(val dialog: Boolean = false, val deleting: Boolean = false, val error: String? = null, val errorPresentation: ErrorPresentation? = null, val requestRetryable: Boolean? = null, val retryReady: Boolean = true)

/**
 * Account deletion as on the mockups (`DeleteAccount*`, FIN-40): profile link → passcode (applock `Confirm`) →
 * «Удалить аккаунт?» over the profile → «Удалить навсегда». The request runs from the dialog, which shows its
 * progress and a failure itself.
 */
@HiltViewModel
class DeleteAccountViewModel @Inject constructor(
    private val profileApi: ProfileApi,
    private val session: SessionRepository,
    private val tracker: Tracker,
    private val elapsedClock: ElapsedClock,
) : ViewModel() {
    private val _state = MutableStateFlow(DeleteUiState())
    private val retryGate = RequestRetryGate(elapsedClock)
    init { viewModelScope.launch { retryGate.readiness.collect { ready -> _state.update { it.copy(retryReady = ready) } } } }
    val state: StateFlow<DeleteUiState> = _state

    /** The passcode step reported success: ask for the final confirmation. */
    fun onPasscodeConfirmed() = _state.update { DeleteUiState(dialog = true, retryReady = retryGate.isReady()) }

    /** «Отмена» or system back; ignored while the request runs. */
    fun dismiss() = _state.update { if (it.deleting) it else DeleteUiState() }

    /** «Удалить навсегда». A repeat after success also returns 204 (api.md 2.5). */
    fun delete() {
        if (_state.value.deleting || !retryGate.isReady()) return
        viewModelScope.launch {
            _state.update { it.copy(deleting = true, error = null, errorPresentation = null, requestRetryable = null) }
            try {
                profileApi.deleteAccount()
                tracker.track(Events.PROFILE_ACCOUNT_DELETED)
                session.clearLocal(reason = AuthRoutes.Phone.REASON_DELETED)
            } catch (e: AppError) {
                retryGate.record(e)
                _state.update { it.copy(deleting = false, errorPresentation = e.presentation, requestRetryable = e.requestRetryable, error = if (e is AppError.Offline) OFFLINE else FAILED) }
            }
        }
    }

    internal companion object {
        const val OFFLINE = "Аккаунт не удалён"
        const val FAILED = "Не получилось удалить аккаунт. Попробуйте ещё раз"
    }
}

/** «Удалить аккаунт?» (mockups `DeleteAccountDialog`, `DeleteAccountBusy`, `DeleteAccountOffline`). */
@Composable
internal fun DeleteAccountDialog(state: DeleteUiState, onDelete: () -> Unit, onDismiss: () -> Unit) {
    if (!state.dialog) return
    PfDialog(
        title = "Удалить аккаунт?",
        description = "Удалим аккаунт, выписки вместе с файлами, операции и ваши правки категорий, историю вопросов помощнику. Восстановить их будет нельзя.",
        confirmText = "Удалить навсегда",
        onConfirm = onDelete,
        onDismiss = onDismiss,
        busy = state.deleting,
        confirmEnabled = state.retryReady,
        busyText = "Удаляем…",
    ) {
        Spacer(Modifier.height(PfTheme.dimens.space2))
        Text("Копию данных можно запросить в поддержке до удаления.", style = PfTheme.type.body, color = PfTheme.colors.textMuted)
        state.error?.let {
            Spacer(Modifier.height(PfTheme.dimens.space3))
            val presentation = state.errorPresentation
            if (presentation != null) ErrorMessage(presentation, it) else PfNotice(it, tone = NoticeTone.WARNING, alert = true)
        }
    }
}
