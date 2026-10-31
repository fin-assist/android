package ru.finassist.pf.feature.assistant.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme

@Composable
internal fun VoicePanel(state: VoiceState, onFinish: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, retryEnabled: Boolean = true) {
    if (state.phase == VoicePhase.IDLE) return
    val c = PfTheme.colors
    val d = PfTheme.dimens
    PfCard(Modifier.padding(horizontal = d.space5, vertical = d.space2).testTag("assistant.voice.panel")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.space3)) {
            PfIcon(if (state.phase == VoicePhase.ERROR) PfIcons.ALERT else PfIcons.MIC, contentDescription = null,
                size = 32.dp, tint = c.accent,
                modifier = Modifier.background(c.accentSoft, RoundedCornerShape(d.radiusMd)).padding(d.space3))
            Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(VoiceTexts.title(state), style = PfTheme.type.title2, modifier = Modifier.testTag("assistant.voice.${state.phase.name.lowercase()}"))
                Text(VoiceTexts.description(state),
                    style = PfTheme.type.caption, color = c.textMuted)
            }
        }
        if (state.phase == VoicePhase.LISTENING) {
            LinearProgressIndicator(progress = { state.level }, modifier = Modifier.fillMaxWidth().padding(vertical = d.space3).height(6.dp)
                .testTag("assistant.voice.level"), color = c.accent, trackColor = c.accentSoft)
        }
        Row(Modifier.fillMaxWidth().padding(top = d.space3), horizontalArrangement = Arrangement.spacedBy(d.space2)) {
            if (state.phase == VoicePhase.LISTENING) {
                PfButton("Завершить", onFinish, Modifier.weight(1f).testTag("assistant.voice.finish"), variant = ButtonVariant.PRIMARY)
            } else if (state.phase == VoicePhase.ERROR) {
                PfButton("Повторить", onRetry, Modifier.weight(1f).testTag("assistant.voice.retry"), enabled = retryEnabled)
            }
            PfButton(if (state.active) "Отмена" else "Закрыть", onCancel, Modifier.weight(1f).testTag("assistant.voice.cancel"))
        }
    }
}

internal object VoiceTexts {
    fun description(state: VoiceState): String = when {
        state.active -> "Текст появится в черновике. Отправьте его после проверки."
        state.failure == VoiceFailure.PERMISSION -> "Черновик сохранён. Разрешите доступ к микрофону в настройках приложения или введите текст."
        else -> "Черновик сохранён. Можно попробовать ещё раз или ввести текст."
    }

    fun title(state: VoiceState): String = when (state.phase) {
        VoicePhase.IDLE -> ""
        VoicePhase.PREPARING -> "Подготавливаем микрофон…"
        VoicePhase.LISTENING -> "Говорите, я слушаю"
        VoicePhase.RECOGNIZING -> "Распознаём речь…"
        VoicePhase.ERROR -> when (state.failure) {
            VoiceFailure.PERMISSION -> "Нужен доступ к микрофону"
            VoiceFailure.NO_SPEECH -> "Не удалось расслышать речь"
            VoiceFailure.UNAVAILABLE -> "Распознавание речи недоступно"
            else -> "Не получилось распознать речь"
        }
    }
}
