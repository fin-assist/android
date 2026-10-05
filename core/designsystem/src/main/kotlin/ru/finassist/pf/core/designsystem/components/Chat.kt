package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Day separator in the chat («Сегодня»). */
@Composable
fun PfChatDay(label: String, modifier: Modifier = Modifier) {
    Text(
        label.uppercase(), style = PfTheme.type.overline, color = PfTheme.colors.textFaint,
        modifier = modifier.fillMaxWidth().padding(vertical = PfTheme.dimens.space2), textAlign = TextAlign.Center,
    )
}

/** User message: bubble on the right, `accent` fill, `on-accent` text. */
@Composable
fun PfUserMessage(text: String, modifier: Modifier = Modifier) {
    val c = PfTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .padding(start = 48.dp)
                .background(c.accent, RoundedCornerShape(PfTheme.dimens.radiusXl, PfTheme.dimens.radiusXl, 4.dp, PfTheme.dimens.radiusXl))
                .padding(horizontal = PfTheme.dimens.space4, vertical = PfTheme.dimens.space3),
        ) {
            Text(text, style = PfTheme.type.body, color = c.onAccent)
        }
    }
}

/** Product mark: indigo square with three bars; also the assistant avatar. */
@Composable
fun PfMark(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val c = PfTheme.colors
    Box(modifier.size(size).background(c.accent, RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(size / 12)) {
            Box(Modifier.width(size / 7).height(size / 4).background(c.onAccent, RoundedCornerShape(1.dp)))
            Box(Modifier.width(size / 7).height(size / 2.4f).background(c.onAccent, RoundedCornerShape(1.dp)))
            Box(Modifier.width(size / 7).height(size / 1.7f).background(c.onAccent, RoundedCornerShape(1.dp)))
        }
    }
}

/**
 * Assistant message: mark on the left, content without a bubble, then the mandatory source line
 * («По вашим операциям, апрель — сентябрь 2026 · посчитано 25 сентября в 19:11») and an optional «stale» line.
 */
@Composable
fun PfAssistantMessage(
    modifier: Modifier = Modifier,
    source: String? = null,
    staleText: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = PfTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        PfMark()
        Spacer(Modifier.width(PfTheme.dimens.space3))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PfTheme.dimens.space3)) {
            content()
            if (source != null) Text(source, style = PfTheme.type.hint, color = c.textMuted)
            if (staleText != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PfIcon(PfIcons.INFO, contentDescription = null, size = PfTheme.dimens.iconSm, tint = c.textMuted)
                    Spacer(Modifier.width(PfTheme.dimens.space1))
                    Text(staleText, style = PfTheme.type.hint, color = c.textMuted)
                }
            }
        }
    }
}

enum class ChatStatusTone { BUSY, ERROR }

/** Status line in the thread: «Считаю по выписке…» or an error with an optional «Повторить». */
@Composable
fun PfChatStatus(text: String, modifier: Modifier = Modifier, tone: ChatStatusTone = ChatStatusTone.BUSY, action: (@Composable () -> Unit)? = null) {
    val c = PfTheme.colors
    Row(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.Top) {
        PfMark()
        Spacer(Modifier.width(PfTheme.dimens.space3))
        Column(Modifier.weight(1f)) {
            Text(text, style = PfTheme.type.body, color = if (tone == ChatStatusTone.ERROR) c.warningText else c.textMuted)
            if (action != null) action()
        }
    }
}

enum class ComposerState { IDLE, BUSY, OFFLINE, LIMIT }

/**
 * Input panel: mic (48), round field (48), send (48, `accent`), on `nav` with a top `border`. `offline` and
 * `limit` replace the panel with a notice; the disclaimer under the panel is always there.
 */
@Composable
fun PfChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    modifier: Modifier = Modifier,
    state: ComposerState = ComposerState.IDLE,
    placeholder: String = "Например: сколько я трачу на такси?",
    offlineText: String = "Нет сети — помощнику нужен интернет. Операции и аналитика доступны",
    limitText: String = "Вопросы на сегодня закончились. Новые — в 00:00 по Москве",
) {
    val c = PfTheme.colors
    Column(modifier.fillMaxWidth().background(c.nav)) {
        PfDivider()
        Column(Modifier.padding(horizontal = PfTheme.dimens.space3, vertical = PfTheme.dimens.space2)) {
            when (state) {
                ComposerState.OFFLINE -> PfNotice(offlineText, tone = NoticeTone.WARNING)
                ComposerState.LIMIT -> PfNotice(limitText, tone = NoticeTone.LIMIT)
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    PfIconButton(PfIcons.MIC, contentDescription = "Голосовой ввод", onClick = onVoice, enabled = state == ComposerState.IDLE, tint = c.accent)
                    val shape = RoundedCornerShape(PfTheme.dimens.radiusFull)
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        enabled = state == ComposerState.IDLE,
                        maxLines = 4,
                        textStyle = PfTheme.type.lead.copy(color = c.text, fontSize = 16.sp),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        modifier = Modifier
                            .weight(1f)
                            .background(c.surface, shape)
                            .border(1.dp, c.borderStrong, shape)
                            .padding(horizontal = PfTheme.dimens.space4, vertical = 14.dp),
                        decorationBox = { inner ->
                            Box {
                                if (value.isEmpty()) Text(placeholder, style = PfTheme.type.lead, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.width(PfTheme.dimens.space2))
                    Box(
                        Modifier
                            .size(PfTheme.dimens.touch)
                            .background(c.accent, RoundedCornerShape(PfTheme.dimens.radiusFull))
                            .clickable(enabled = state == ComposerState.IDLE, role = Role.Button, onClick = onSend),
                        contentAlignment = Alignment.Center,
                    ) {
                        PfIcon(PfIcons.SEND, contentDescription = "Отправить", tint = c.onAccent)
                    }
                }
            }
            Spacer(Modifier.height(PfTheme.dimens.space2))
            Text(
                "Помощник может ошибаться — сверяйте цифры с операциями",
                style = PfTheme.type.hint, color = c.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
    }
}
