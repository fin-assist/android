package ru.finassist.pf.feature.assistant.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.designsystem.components.ErrorMessage
import ru.finassist.pf.core.common.error.ErrorContext
import ru.finassist.pf.core.designsystem.theme.PfInsets
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.components.PfDivider
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
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
    val errorPresentation: ErrorPresentation? = null,
    val requestRetryable: Boolean? = null,
    val retryReady: Boolean = true,
)

@HiltViewModel
class AiConsentViewModel @Inject constructor(
    private val authApi: AuthApi,
    private val profileApi: ProfileApi,
    private val elapsedClock: ElapsedClock,
) : ViewModel() {
    private val _state = MutableStateFlow(AiConsentUiState())
    private val documentGate = RequestRetryGate(elapsedClock)
    private var documentFailure: AppError? = null
    private val retryGate = RequestRetryGate(elapsedClock)
    init {
        viewModelScope.launch { retryGate.readiness.collect { ready -> _state.update { it.copy(retryReady = ready && documentGate.isReady()) } } }
        viewModelScope.launch { documentGate.readiness.collect { ready -> _state.update { it.copy(retryReady = ready && retryGate.isReady()) } } }
    }
    val state: StateFlow<AiConsentUiState> = _state

    init {
        loadDocument()
    }

    private fun loadDocument() {
        viewModelScope.launch {
            runCatching { fetchDocument() }.onSuccess { d -> _state.update { it.copy(document = d) } }
                .onFailure { failure -> if (failure is AppError) _state.update { it.copy(errorPresentation = failure.presentation.takeUnless { failure is AppError.Offline }, formError = "Не получилось загрузить документ согласия") } }
        }
    }

    private suspend fun fetchDocument(): ru.finassist.pf.core.api.model.ConsentDocument {
        if (!documentGate.isReady()) throw checkNotNull(documentFailure)
        return try { authApi.getConsentDocument(ConsentType.ASSISTANT).also { documentFailure = null } }
        catch (error: AppError) { documentFailure = error; documentGate.record(error); throw error }
    }

    fun setAccepted(v: Boolean) = _state.update { it.copy(accepted = v, error = null) }

    /** PUT is idempotent by itself (api.md 2.3) — no key needed. */
    fun grant() {
        val s = _state.value
        if (s.busy || !retryGate.isReady() || !documentGate.isReady()) return
        if (!s.accepted) {
            _state.update { it.copy(error = CONSENT_REQUIRED) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, formError = null, errorPresentation = null) }
            try {
                val version = s.document?.version ?: fetchDocument().version
                profileApi.grantAssistantConsent(AssistantConsentGrant(version))
                _state.update { it.copy(busy = false, granted = true) }
            } catch (e: AppError) {
                retryGate.record(e)
                val text = when {
                    e is AppError.Api && e.response?.context != ErrorContext.RESULT_EVENT && e.code == ErrorCodes.CONSENT_OUTDATED -> {
                        retryGate.clear()
                        documentGate.clear()
                        loadDocument()
                        "Текст согласия обновился — прочитайте его ещё раз и подтвердите"
                    }
                    e is AppError.Offline -> "Нет сети — проверьте интернет и повторите"
                    else -> "Не получилось сохранить. Попробуйте ещё раз"
                }
                _state.update { it.copy(busy = false, formError = text, errorPresentation = e.presentation.takeUnless { e is AppError.Offline }, requestRetryable = e.requestRetryable.takeUnless { e is AppError.Api && e.code == ErrorCodes.CONSENT_OUTDATED }) }
            }
        }
    }
}

/** «Передача данных помощнику» (decisions edit ux-1, gpt-7). */
@Composable
fun AiConsentScreen(onBack: () -> Unit, onGranted: () -> Unit, vm: AiConsentViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.granted) { if (state.granted) onGranted() }
    AiConsentContent(
        state = state,
        onBack = onBack,
        onAccepted = vm::setAccepted,
        onOpenDocument = { doc -> runCatching { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(doc.url)) } },
        onGrant = vm::grant,
    )
}

/** The checkbox error when «Продолжить» is pressed without consent (mockup `AiConsentError`). */
internal const val CONSENT_REQUIRED = "Без согласия помощник не сможет ответить"

/**
 * Mockup `AiConsent`: what is sent and what is not, the daily limit, the consent and «Продолжить» at the bottom
 * of the viewport, «Не сейчас» under it. «Как помощник использует данные» opens the consent document.
 */
@Composable
internal fun AiConsentContent(
    state: AiConsentUiState,
    onBack: () -> Unit,
    onAccepted: (Boolean) -> Unit,
    onOpenDocument: (ConsentDocument) -> Unit,
    onGrant: () -> Unit,
) {
    val d = PfTheme.dimens
    Column(Modifier.fillMaxSize().windowInsetsPadding(PfInsets.navigationBars).testTag(AssistantTags.CONSENT)) {
        PfPageHeader("Помощник", onBack = onBack)
        BoxWithConstraints(Modifier.weight(1f)) {
            val viewport = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewport)
                    .padding(start = d.space5, end = d.space5, top = d.space4, bottom = d.space4),
            ) {
                Text("Прежде чем спросить", style = PfTheme.type.title1, color = PfTheme.colors.text)
                Text(
                    "Данные уходят, только когда вы задаёте вопрос, и только те, что нужны для ответа. Помощник передаёт их нейросети Alice AI от Яндекса",
                    style = PfTheme.type.lead, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = d.space1),
                )
                Spacer(Modifier.height(d.space6))
                PfCard {
                    DataList(
                        "Передаём", PfIcons.CHECK, PfTheme.colors.accent,
                        listOf("Суммы, даты и категории операций", "Описания операций, включая имена получателей переводов", "Текст вашего вопроса"),
                    )
                    Spacer(Modifier.height(d.space4))
                    PfDivider()
                    Spacer(Modifier.height(d.space4))
                    DataList("Не передаём", PfIcons.X, PfTheme.colors.textMuted, listOf("Ваш номер телефона", "Номера карт и счетов"))
                }
                Spacer(Modifier.height(d.space1))
                PfLink(
                    "Как помощник использует данные",
                    onClick = { state.document?.let(onOpenDocument) },
                    modifier = Modifier.testTag(AssistantTags.CONSENT_DOCUMENT),
                )
                Spacer(Modifier.height(d.space3))
                PfNotice("5 вопросов в день. Новые — в 00:00 по Москве", tone = NoticeTone.INFO, icon = PfIcons.CLOCK)
                Spacer(Modifier.height(d.space5))
                PfCheckbox(
                    state.accepted, onAccepted, label = "Даю согласие на передачу этих данных помощнику", error = state.error,
                    modifier = Modifier.testTag(AssistantTags.CONSENT_CHECKBOX),
                )
                state.formError?.let {
                    Spacer(Modifier.height(d.space3))
                    val presentation = state.errorPresentation
                    if (presentation != null) ErrorMessage(presentation, it) else PfNotice(it, tone = NoticeTone.WARNING, alert = true)
                }
                Spacer(Modifier.weight(1f).heightIn(min = d.space6))
                PfButton(
                    "Продолжить", enabled = state.retryReady, onClick = onGrant, variant = ButtonVariant.PRIMARY, block = true, busy = state.busy, busyText = "Сохраняем…",
                    modifier = Modifier.testTag(AssistantTags.CONSENT_SUBMIT),
                )
                Spacer(Modifier.height(d.space2))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    PfLink("Не сейчас", onClick = onBack, modifier = Modifier.testTag(AssistantTags.CONSENT_LATER))
                }
            }
        }
    }
}

/** «Передаём» / «Не передаём»: a heading and 20 dp-icon items 8 dp apart. */
@Composable
private fun DataList(title: String, icon: String, tint: Color, items: List<String>) {
    val d = PfTheme.dimens
    Text(title, style = PfTheme.type.bodyStrong, color = PfTheme.colors.text, modifier = Modifier.semantics { heading() })
    Column(Modifier.padding(top = d.space2), verticalArrangement = Arrangement.spacedBy(d.space2)) {
        items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(d.space3), verticalAlignment = Alignment.Top) {
                PfIcon(icon, contentDescription = null, size = d.iconMd, tint = tint)
                Text(item, style = PfTheme.type.body, color = PfTheme.colors.text)
            }
        }
    }
}
