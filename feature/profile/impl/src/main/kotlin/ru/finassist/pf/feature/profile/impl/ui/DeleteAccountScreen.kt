package ru.finassist.pf.feature.profile.impl.ui

import ru.finassist.pf.core.designsystem.theme.PfInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfDialog
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.components.PfOrderedSteps
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.SessionRepository
import javax.inject.Inject

data class DeleteUiState(val askConfirm: Boolean = false, val deleting: Boolean = false, val error: String? = null)

@HiltViewModel
class DeleteAccountViewModel @Inject constructor(
    private val profileApi: ProfileApi,
    private val session: SessionRepository,
    private val tracker: Tracker,
) : ViewModel() {
    private val _state = MutableStateFlow(DeleteUiState())
    val state: StateFlow<DeleteUiState> = _state

    fun askConfirm(show: Boolean) = _state.update { it.copy(askConfirm = show, error = null) }

    /** After the passcode is confirmed. A repeat after success also returns 204 (api.md 2.5). */
    fun delete() {
        viewModelScope.launch {
            _state.update { it.copy(deleting = true, error = null) }
            try {
                profileApi.deleteAccount()
                tracker.track(Events.PROFILE_ACCOUNT_DELETED)
                session.clearLocal(reason = AuthRoutes.Phone.REASON_DELETED)
            } catch (e: AppError) {
                _state.update {
                    it.copy(deleting = false, error = if (e is AppError.Offline) "Нет сети — аккаунт не удалён. Проверьте интернет и повторите" else "Не получилось удалить аккаунт. Попробуйте ещё раз")
                }
            }
        }
    }
}

/** «Удалить аккаунт»: what will be deleted → dialog → passcode → DELETE /v1/profile. */
@Composable
fun DeleteAccountScreen(
    onBack: () -> Unit,
    onConfirmPasscode: () -> Unit,
    passcodeResult: State<String?>,
    onPasscodeResultConsumed: () -> Unit,
    vm: DeleteAccountViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val confirmed by passcodeResult
    val d = PfTheme.dimens
    LaunchedEffect(confirmed) {
        if (confirmed == "true") {
            onPasscodeResultConsumed()
            vm.delete()
        }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(PfInsets.navigationBars)) {
        PfPageHeader("Удаление аккаунта", onBack = if (state.deleting) null else onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = d.space5, vertical = d.space2),
            verticalArrangement = Arrangement.spacedBy(d.space4),
        ) {
            Text("Что удалим", style = PfTheme.type.title1, color = PfTheme.colors.text)
            PfOrderedSteps(
                listOf(
                    "Выписки вместе с файлами и историю загрузок",
                    "Операции и ваши правки категорий",
                    "Историю вопросов помощнику",
                    "Номер телефона и согласия",
                ),
            )
            Text(
                "Восстановить данные будет нельзя. Копию данных можно запросить в поддержке до удаления.",
                style = PfTheme.type.body, color = PfTheme.colors.textMuted,
            )
            state.error?.let { PfNotice(it, tone = NoticeTone.WARNING, alert = true) }
        }
        PfButton(
            "Удалить аккаунт",
            onClick = { vm.askConfirm(true) },
            variant = ButtonVariant.SECONDARY,
            block = true,
            busy = state.deleting,
            busyText = "Удаляем…",
            modifier = Modifier.fillMaxWidth().padding(horizontal = d.space5, vertical = d.space4),
        )
    }
    if (state.askConfirm) {
        PfDialog(
            title = "Удалить аккаунт?",
            description = "Удалим все данные без возможности восстановить. Подтвердите код-паролем.",
            confirmText = "Удалить",
            onConfirm = {
                vm.askConfirm(false)
                onConfirmPasscode()
            },
            onDismiss = { vm.askConfirm(false) },
        )
    }
}
