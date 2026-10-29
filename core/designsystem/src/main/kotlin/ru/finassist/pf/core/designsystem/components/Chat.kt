package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.unit.sp
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfInsets
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Day separator in the chat (mockup `.pf-chatday`): «Сегодня» as written, `13/20` muted, centred. */
@Composable
fun PfChatDay(label: String, modifier: Modifier = Modifier) {
    Text(
        label, style = PfTheme.type.caption, color = PfTheme.colors.textMuted,
        modifier = modifier.fillMaxWidth().semantics { heading() }, textAlign = TextAlign.Center,
    )
}

/** User message (mockup `.pf-msg-user`): bubble on the right, at most 78 % wide, `accent` fill, `15/20` text. */
@Composable
fun PfUserMessage(text: String, modifier: Modifier = Modifier) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Box(
            Modifier
                .widthIn(max = maxWidth * 0.78f)
                .background(c.accent, RoundedCornerShape(d.radiusXl, d.radiusXl, d.space1, d.radiusXl))
                .padding(horizontal = d.space4, vertical = d.space3),
        ) {
            Text(text, style = PfTheme.type.lead, color = c.onAccent)
        }
    }
}

/**
 * Product mark: indigo square with three bars; also the assistant avatar. [corner] and [glyph] default to the
 * app mark's proportions (radius a quarter of the side, glyph 32/56 of it); the chat avatar is 28 dp with an
 * 8 dp radius and an 18 dp glyph (`.pf-msg-mark`).
 */
@Composable
fun PfMark(modifier: Modifier = Modifier, size: Dp = 32.dp, corner: Dp = size / 4, glyph: Dp = size * 32f / 56f) {
    val c = PfTheme.colors
    Box(modifier.size(size).background(c.accent, RoundedCornerShape(corner)), contentAlignment = Alignment.Center) {
        // Three thin round-capped bars of the mockup's 24-unit glyph (x 7/12/17, tops 13/7/10, bottom 17).
        Canvas(Modifier.size(glyph)) {
            val u = this.size.width / 24f
            val stroke = 2.25f * u
            listOf(7f to 13f, 12f to 7f, 17f to 10f).forEach { (x, top) ->
                drawLine(c.onAccent, Offset(x * u, top * u), Offset(x * u, 17f * u), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}

/**
 * Assistant message (mockup `.pf-msg-ai`): a 28 dp mark, 8 dp gap, content without a bubble (parts 12 dp
 * apart), then the mandatory source line («По вашим операциям, апрель — сентябрь 2026 · посчитано 25 сентября
 * в 19:11», `hint`) with the optional «stale» line right under it.
 */
@Composable
fun PfAssistantMessage(
    modifier: Modifier = Modifier,
    source: String? = null,
    staleText: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(d.space2), verticalAlignment = Alignment.Top) {
        PfMark(size = 28.dp, corner = d.space2, glyph = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d.space3)) {
            content()
            if (source != null) {
                Column {
                    Text(source, style = PfTheme.type.hint, color = c.textMuted)
                    if (staleText != null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.space1)) {
                            PfIcon(PfIcons.INFO, contentDescription = null, size = d.iconSm, tint = c.textMuted)
                            Text(staleText, style = PfTheme.type.hint, color = c.textMuted)
                        }
                    }
                }
            }
        }
    }
}

enum class ChatStatusTone { BUSY, ERROR }

/**
 * Status in the thread (mockup `ChatStatus`):
 * - busy — three 8 dp `accent` dots (opacity 1 / 0.6 / 0.3) and «Считаю по выписке…» (`13/20` muted), 48 dp tall;
 * - error — a warning notice with the text and [action] (a ghost «Повторить», lined up with the text).
 */
@Composable
fun PfChatStatus(text: String, modifier: Modifier = Modifier, tone: ChatStatusTone = ChatStatusTone.BUSY, action: (@Composable () -> Unit)? = null) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    if (tone == ChatStatusTone.ERROR) {
        PfNotice(
            text, modifier = modifier, tone = NoticeTone.WARNING, alert = true,
            action = action?.let { a -> { Box(Modifier.offset(x = -d.space4)) { a() } } },
        )
        return
    }
    Row(
        modifier.fillMaxWidth().defaultMinSize(minHeight = d.touch).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(d.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(d.space1)) {
            listOf(1f, 0.6f, 0.3f).forEach { alpha ->
                Box(Modifier.size(8.dp).background(c.accent.copy(alpha = alpha), RoundedCornerShape(d.radiusFull)))
            }
        }
        Text(text, style = PfTheme.type.caption, color = c.textMuted)
        if (action != null) action()
    }
}

enum class ComposerState { IDLE, BUSY, OFFLINE, LIMIT }

/**
 * Input panel (mockup `.pf-composer`) on `nav` with a top `border`: mic (48, `text`), round field (48, 16 sp),
 * send (48, `accent`), 8 dp apart; padding 8 / 20 / 8 / 8 (the send button ends 20 dp from the edge).
 * - busy: the field is inactive (`bg`, `border`), mic and send are grey;
 * - offline: an info notice replaces the panel; [onRetry] adds «Повторить» inside it — the app has no network
 *   monitor, so this is how the user brings the panel back (owner's decision on FIN-40);
 * - limit: a limit notice replaces the panel.
 * The disclaimer under the panel is always there; notices sit on a 20 dp margin.
 */
@Composable
fun PfChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    modifier: Modifier = Modifier,
    state: ComposerState = ComposerState.IDLE,
    placeholder: String = "Например: траты на такси",
    offlineText: String = "Нет сети — помощнику нужен интернет. Операции и аналитика доступны",
    limitText: String = "Вопросы на сегодня закончились. Новые — в 00:00 по Москве",
    onRetry: (() -> Unit)? = null,
    sendEnabled: Boolean = true,
) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    val notice = state == ComposerState.OFFLINE || state == ComposerState.LIMIT
    Column(modifier.fillMaxWidth().background(c.nav)) {
        PfDivider()
        Column(Modifier.padding(start = if (notice) d.space5 else d.space2, end = d.space5, top = d.space2, bottom = d.space2)) {
            when (state) {
                ComposerState.OFFLINE -> PfNotice(
                    offlineText, tone = NoticeTone.INFO, modifier = Modifier.testTag(PfTestTags.CHAT_OFFLINE),
                    action = onRetry?.let { retry ->
                        {
                            Box(Modifier.offset(x = -d.space4)) {
                                PfButton("Повторить", onClick = retry, variant = ButtonVariant.GHOST, modifier = Modifier.testTag(PfTestTags.CHAT_RETRY))
                            }
                        }
                    },
                )
                ComposerState.LIMIT -> PfNotice(limitText, tone = NoticeTone.LIMIT, modifier = Modifier.testTag(PfTestTags.CHAT_LIMIT))
                else -> {
                    val idle = state == ComposerState.IDLE
                    Row(horizontalArrangement = Arrangement.spacedBy(d.space2), verticalAlignment = Alignment.CenterVertically) {
                        PfIconButton(PfIcons.MIC, contentDescription = "Голосовой ввод", onClick = onVoice, enabled = idle, tint = c.text, modifier = Modifier.testTag(PfTestTags.CHAT_VOICE))
                        val shape = RoundedCornerShape(d.radiusFull)
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            enabled = idle,
                            maxLines = 4,
                            textStyle = PfTheme.type.lead.copy(color = if (idle) c.text else c.textMuted, fontSize = 16.sp),
                            cursorBrush = SolidColor(c.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { if (idle && sendEnabled) onSend() }),
                            modifier = Modifier
                                .weight(1f)
                                .background(if (idle) c.surface else c.bg, shape)
                                .border(1.dp, if (idle) c.borderStrong else c.border, shape)
                                .padding(horizontal = d.space4, vertical = 14.dp)
                                .testTag(PfTestTags.CHAT_INPUT),
                            decorationBox = { inner ->
                                Box {
                                    if (value.isEmpty()) {
                                        Text(placeholder, style = PfTheme.type.lead.copy(fontSize = 16.sp), color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    inner()
                                }
                            },
                        )
                        Box(
                            Modifier
                                .size(d.touch)
                                .background(if (idle && sendEnabled) c.accent else c.border, RoundedCornerShape(d.radiusFull))
                                .clickable(enabled = idle && sendEnabled, role = Role.Button, onClick = onSend)
                                .testTag(PfTestTags.CHAT_SEND),
                            contentAlignment = Alignment.Center,
                        ) {
                            PfIcon(PfIcons.SEND, contentDescription = "Отправить", tint = if (idle && sendEnabled) c.onAccent else c.textMuted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(d.space2))
            Text(
                "Помощник может ошибаться — сверяйте цифры с операциями",
                style = PfTheme.type.hint, color = c.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.windowInsetsPadding(PfInsets.navigationBars))
    }
}
