package ru.finassist.pf.feature.assistant.impl.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.RussianDates.plural
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PageHeader
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfCheckbox
import ru.finassist.pf.core.designsystem.components.PfIcon
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.assistant.impl.data.AssistantRepository
import javax.inject.Inject

@HiltViewModel
class AiConsentViewModel @Inject constructor(private val repo: AssistantRepository, private val tracker: Tracker) : ViewModel() {
    data class UiState(
        val version: String? = null,
        val url: String? = null,
        val title: String? = null,
        val dailyMax: Int = 5,
        val checked: Boolean = false,
        val error: String? = null,
        val busy: Boolean = false,
        val done: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        runCatching { repo.consentDocument() }.onSuccess { d -> _state.update { it.copy(version = d.version, url = d.url, title = d.title, error = null) } }
            .onFailure { _state.update { it.copy(error = "Не получилось загрузить документ согласия") } }
        runCatching { repo.limit() }.onSuccess { l -> _state.update { it.copy(dailyMax = l.dailyMax) } }
    }

    fun onChecked(v: Boolean) = _state.update { it.copy(checked = v, error = null) }

    fun accept() = viewModelScope.launch {
        val s = _state.value
        if (!s.checked) { _state.update { it.copy(error = "Без согласия помощник не сможет ответить") }; return@launch }
        val version = s.version ?: run { load(); return@launch }
        _state.update { it.copy(busy = true, error = null) }
        try {
            repo.grantConsent(version)
            tracker.track("assistant.consent.granted")
            _state.update { it.copy(busy = false, done = true) }
        } catch (e: AppError) {
            _state.update { it.copy(busy = false, error = when (e) {
                is AppError.ConsentOutdated -> { load(); "Документ обновился — прочитайте новую версию и подтвердите ещё раз" }
                is AppError.Offline -> "Нет сети. Проверьте интернет и повторите"
                else -> "Не получилось сохранить согласие. Повторите позже"
            }) }
        }
    }
}

/** Consent to send data to Alice AI (mockup AiConsent). */
@Composable
fun AiConsentScreen(state: AiConsentViewModel.UiState, vm: AiConsentViewModel, onBack: () -> Unit) {
    val c = PfTheme.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageHeader(title = "Согласие для помощника", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = PfSpace.s2), verticalArrangement = Arrangement.spacedBy(PfSpace.s5)) {
            Column {
                Text("Прежде чем спросить", style = PfTheme.type.title1, color = c.text)
                Spacer(Modifier.height(PfSpace.s2))
                Text("Данные уходят, только когда вы задаёте вопрос, и только те, что нужны для ответа. Помощник передаёт их нейросети Alice AI от Яндекса", style = PfTheme.type.body, color = c.textMuted)
            }
            PfCard {
                Text("Передаём", style = PfTheme.type.captionStrong, color = c.textMuted)
                Spacer(Modifier.height(PfSpace.s2))
                listOf("Суммы, даты и категории операций", "Описания операций, включая имена получателей переводов", "Текст вашего вопроса").forEach { Point("check", it, c.positive) }
                Spacer(Modifier.height(PfSpace.s3))
                Text("Не передаём", style = PfTheme.type.captionStrong, color = c.textMuted)
                Spacer(Modifier.height(PfSpace.s2))
                listOf("Ваш номер телефона", "Номера карт и счетов").forEach { Point("x", it, c.textMuted) }
            }
            Column {
                state.url?.let { url -> PfLink(state.title ?: "Как помощник использует данные", onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }) }
                Text("${plural(state.dailyMax.toLong(), "вопрос", "вопроса", "вопросов")} в день. Новые — в 00:00 по Москве", style = PfTheme.type.caption, color = c.textMuted)
            }
            PfCheckbox(checked = state.checked, onCheckedChange = vm::onChecked, label = "Даю согласие на передачу этих данных помощнику", error = state.error != null && !state.checked)
            state.error?.let { Notice(it, tone = NoticeTone.Warning, alert = true) }
            Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                PfButton("Продолжить", onClick = vm::accept, variant = ButtonVariant.Primary, block = true, busy = state.busy, busyText = "Сохраняем…", enabled = state.version != null || state.error != null)
                PfButton("Не сейчас", onClick = onBack, variant = ButtonVariant.Ghost, block = true, enabled = !state.busy)
            }
        }
    }
}

@Composable
private fun Point(icon: String, text: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 2.dp)) {
        PfIcon(icon, size = PfSize.iconMd, tint = tint)
        Spacer(Modifier.width(PfSpace.s2))
        Text(text, style = PfTheme.type.body, color = PfTheme.colors.text)
    }
}


