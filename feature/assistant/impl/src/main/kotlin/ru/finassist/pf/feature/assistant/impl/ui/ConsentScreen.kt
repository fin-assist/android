package ru.finassist.pf.feature.assistant.impl.ui

import ru.finassist.pf.core.designsystem.theme.PfInsets
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfCheckbox
import ru.finassist.pf.core.designsystem.components.PfDataRow
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.theme.PfTheme
import javax.inject.Inject

data class AiConsentUiState(
    val document: ConsentDocument? = null,
    val accepted: Boolean = false,
    val error: String? = null,
    val formError: String? = null,
    val busy: Boolean = false,
    val granted: Boolean = false,
)

@HiltViewModel
class AiConsentViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val profileApi: ProfileApi,
) : ViewModel() {
    private val _state = MutableStateFlow(AiConsentUiState())
    val state: StateFlow<AiConsentUiState> = _state

    init {
        loadDocument()
    }

    private fun loadDocument() {
        viewModelScope.launch {
            runCatching { authApi.getConsentDocument(ConsentType.ASSISTANT) }.onSuccess { d -> _state.update { it.copy(document = d) } }
        }
    }

    fun setAccepted(v: Boolean) = _state.update { it.copy(accepted = v, error = null) }

    /** PUT is idempotent by itself (api.md 2.3) — no key needed. */
    fun grant() {
        val s = _state.value
        if (!s.accepted) {
            _state.update { it.copy(error = "Отметьте согласие, чтобы задавать вопросы помощнику") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, formError = null) }
            try {
                val version = s.document?.version ?: authApi.getConsentDocument(ConsentType.ASSISTANT).version
                profileApi.grantAssistantConsent(AssistantConsentGrant(version))
                _state.update { it.copy(busy = false, granted = true) }
            } catch (e: AppError) {
                val text = when {
                    e is AppError.Api && e.code == ErrorCodes.CONSENT_OUTDATED -> {
                        loadDocument()
                        "Текст согласия обновился — прочитайте его ещё раз и подтвердите"
                    }
                    e is AppError.Offline -> "Нет сети — проверьте интернет и повторите"
                    else -> "Не получилось сохранить. Попробуйте ещё раз"
                }
                _state.update { it.copy(busy = false, formError = text) }
            }
        }
    }
}

/** «Передача данных помощнику» (decisions edit ux-1, gpt-7). */
@Composable
fun AiConsentScreen(onBack: () -> Unit, onGranted: () -> Unit, vm: AiConsentViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val d = PfTheme.dimens
    LaunchedEffect(state.granted) { if (state.granted) onGranted() }
    Column(Modifier.fillMaxSize().windowInsetsPadding(PfInsets.navigationBars).testTag(AssistantTags.CONSENT)) {
        PfPageHeader("Помощник", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = d.space5, vertical = d.space2),
            verticalArrangement = Arrangement.spacedBy(d.space4),
        ) {
            Text("Разрешите передавать данные помощнику", style = PfTheme.type.title1, color = PfTheme.colors.text)
            Text(
                "Данные уходят, только когда вы задаёте вопрос, и только те, что нужны для ответа",
                style = PfTheme.type.lead, color = PfTheme.colors.textMuted,
            )
            PfCard {
                PfDataRow("Передаём", "операции, категории и суммы за нужный период", divider = true)
                PfDataRow("Не передаём", "ваш номер телефона", divider = false)
            }
            Text(
                "Помощник работает на Alice AI (Яндекс). Отозвать согласие можно в профиле в любой момент — история ответов сохранится",
                style = PfTheme.type.body, color = PfTheme.colors.textMuted,
            )
            PfCheckbox(state.accepted, vm::setAccepted, label = "Согласен на передачу данных помощнику", error = state.error, modifier = Modifier.testTag(AssistantTags.CONSENT_CHECKBOX))
            state.document?.let { doc ->
                PfLink(doc.title, onClick = { runCatching { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(doc.url)) } })
            }
            state.formError?.let { PfNotice(it, tone = NoticeTone.WARNING, alert = true) }
        }
        PfButton(
            "Продолжить", onClick = vm::grant, variant = ButtonVariant.PRIMARY, block = true, busy = state.busy, busyText = "Сохраняем…",
            modifier = Modifier.fillMaxWidth().padding(horizontal = d.space5, vertical = d.space4).testTag(AssistantTags.CONSENT_SUBMIT),
        )
    }
}
