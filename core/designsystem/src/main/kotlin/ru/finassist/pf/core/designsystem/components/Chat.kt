package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.theme.PfRadius
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Day separator in the chat. */
@Composable
fun ChatDay(label: String, modifier: Modifier = Modifier) {
    Text(label, style = PfTheme.type.hint, color = PfTheme.colors.textMuted, textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth().padding(vertical = PfSpace.s2))
}

/** User bubble: accent fill, right-aligned. */
@Composable
fun ChatUserMessage(text: String, modifier: Modifier = Modifier) {
    val c = PfTheme.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(Modifier.widthIn(max = 300.dp).background(c.accent, RoundedCornerShape(PfRadius.xl)).padding(horizontal = PfSpace.s4, vertical = PfSpace.s3)) {
            Text(text, style = PfTheme.type.body, color = c.onAccent)
        }
    }
}

/** Assistant message: app mark on the left, content without a bubble, source line and optional «changed since». */
@Composable
fun ChatAssistantMessage(modifier: Modifier = Modifier, source: String? = null, changedSince: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = PfTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        AppMark(28.dp)
        Spacer(Modifier.width(PfSpace.s3))
        Column(Modifier.weight(1f)) {
            content()
            if (source != null) {
                Spacer(Modifier.height(PfSpace.s2))
                Text(source, style = PfTheme.type.hint, color = c.textMuted)
            }
            if (changedSince != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PfIcon("info", size = PfSize.iconSm, tint = c.textMuted)
                    Spacer(Modifier.width(PfSpace.s1))
                    Text(changedSince, style = PfTheme.type.hint, color = c.textMuted)
                }
            }
        }
    }
}

/** The product mark: indigo square with three bars. Also the assistant avatar. */
@Composable
fun AppMark(size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    val c = PfTheme.colors
    Box(modifier.size(size).background(c.accent, RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(size / 14)) {
            listOf(0.35f, 0.6f, 0.85f).forEach { h ->
                Box(Modifier.width(size / 7).height(size * h * 0.6f).background(c.onAccent, RoundedCornerShape(1.dp)))
            }
        }
    }
}

enum class ChatStatusTone { Busy, Error }

/** Status row under the thread: «Считаю по выписке…» or an error with optional retry. */
@Composable
fun ChatStatus(title: String, tone: ChatStatusTone, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = PfTheme.colors
    Row(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        AppMark(28.dp)
        Spacer(Modifier.width(PfSpace.s3))
        Column(Modifier.weight(1f)) {
            Text(title, style = PfTheme.type.body, color = if (tone == ChatStatusTone.Error) c.warningText else c.textMuted)
            if (action != null) action()
        }
    }
}

enum class ComposerState { Idle, Busy, Offline, Limit }

/** Composer bar on `nav`: mic 48, field 48 (radius full), send 48 accent; plus the «может ошибаться» note. */
@Composable
fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    state: ComposerState,
    modifier: Modifier = Modifier,
    placeholder: String = "Например: сколько я трачу на такси?",
    offlineText: String = "Нет сети — помощнику нужен интернет. Операции и аналитика доступны",
    limitText: String = "Вопросы на сегодня закончились. Новые — в 00:00 по Москве",
) {
    val c = PfTheme.colors
    Column(modifier.fillMaxWidth().background(c.nav)) {
        Divider()
        Column(Modifier.padding(horizontal = PfSpace.s5, vertical = PfSpace.s2).imePadding().windowInsetsPadding(WindowInsets.navigationBars)) {
            when (state) {
                ComposerState.Offline -> Notice(offlineText, tone = NoticeTone.Warning)
                ComposerState.Limit -> Notice(limitText, tone = NoticeTone.Limit)
                else -> {
                    val enabled = state == ComposerState.Idle
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                        PfIconButton("mic", contentDescription = "Голосовой ввод", onClick = { if (enabled) onVoice() }, tint = if (enabled) c.accent else c.textMuted)
                        Box(
                            Modifier
                                .weight(1f)
                                .height(PfSize.control)
                                .background(c.surface, RoundedCornerShape(PfRadius.full))
                                .border1(c.borderStrong, RoundedCornerShape(PfRadius.full))
                                .padding(horizontal = PfSpace.s4),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            BasicTextField(
                                value = value,
                                onValueChange = onValueChange,
                                enabled = enabled,
                                textStyle = PfTheme.type.input.copy(color = c.text),
                                cursorBrush = SolidColor(c.accent),
                                maxLines = 4,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { if (enabled && value.isNotBlank()) onSend() }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (value.isEmpty()) Text(placeholder, style = PfTheme.type.input, color = c.textMuted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        Box(
                            Modifier
                                .size(PfSize.touch)
                                .clip(RoundedCornerShape(PfRadius.full))
                                .background(if (enabled && value.isNotBlank()) c.accent else c.border)
                                .clickable(enabled = enabled && value.isNotBlank(), role = Role.Button, onClick = onSend)
                                .semantics { contentDescription = "Отправить" },
                            contentAlignment = Alignment.Center,
                        ) {
                            PfIcon("send", size = PfSize.iconLg, tint = if (enabled && value.isNotBlank()) c.onAccent else c.textMuted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(PfSpace.s2))
            Text("Помощник может ошибаться — сверяйте цифры с операциями", style = PfTheme.type.hint, color = c.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}
