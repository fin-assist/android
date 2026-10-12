package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme

/**
 * Dashed frame: 2 dp with 8/6 dp dashes for locked tiles and cards; the file drop uses a 1 dp line with 3/2 dp
 * dashes — what the mockups render for `1.5px dashed` (the browser rounds the stroke down and keeps the dashes
 * about twice the width).
 */
internal fun Modifier.dashedBorder(
    color: Color,
    radius: Dp,
    width: Dp = 2.dp,
    dash: Dp = 8.dp,
    gap: Dp = 6.dp,
): Modifier = drawBehind {
    val stroke = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx()))),
    )
}

/**
 * Metric tile for the 2×2 grid on Analytics (mockup `.pf-stat`): label, `amount-md` value, note; as tall as its
 * content. [locked] shows a dashed frame, a lock icon and [lockedText] instead of a value. One TalkBack node per
 * tile.
 */
@Composable
fun PfStatTile(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    note: String? = null,
    valueLabel: String? = null,
    locked: Boolean = false,
    lockedText: String? = null,
    chartId: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(PfTheme.dimens.radiusXl)
    val alpha = if (chartId != null && !locked) rememberIntroAlpha(chartId) else 1f
    val spoken = if (locked) "$label: пока не считаем. ${lockedText ?: ""}" else listOfNotNull(label, valueLabel ?: value, note).joinToString(", ")
    Column(
        modifier
            .alpha(alpha)
            .clip(shape)
            .background(c.surface, shape)
            .then(if (locked) Modifier.dashedBorder(c.borderStrong, PfTheme.dimens.radiusXl) else Modifier.border(1.dp, c.border, shape))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            // The frame sits outside the padding, as a CSS border does (2 px when locked).
            .padding(if (locked) 2.dp else 1.dp)
            .padding(PfTheme.dimens.space3)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Text(label, style = PfTheme.type.caption, color = c.textMuted)
        if (locked) {
            // `.pf-stat-lock`: 8 dp below the label, a 16 dp lock centred on the text, 8 dp gap.
            Spacer(Modifier.height(PfTheme.dimens.space2))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space2)) {
                PfIcon(PfIcons.LOCK, contentDescription = null, size = 16.dp, tint = c.textMuted)
                Text(lockedText ?: "Пока не считаем", style = PfTheme.type.caption, color = c.textMuted)
            }
        } else {
            Spacer(Modifier.height(PfTheme.dimens.space1))
            PfAmountText(value ?: "—", style = PfTheme.type.amountMd, color = c.text)
            if (note != null) {
                Spacer(Modifier.height(PfTheme.dimens.space1))
                Text(note, style = PfTheme.type.hint, color = c.textMuted)
            }
        }
    }
}

/**
 * One-line amount that never wraps (FIN-26): when the text does not fit, the font shrinks step by step down to
 * [minFontSize] and only then the end is clipped. Amounts already keep a no-break space before `₽`
 * (`Money.format`). Hand-rolled because `BasicText(autoSize = …)` needs Compose foundation 1.8.
 */
@Composable
fun PfAmountText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minFontSize: TextUnit = 14.sp,
) {
    var fontSize by remember(text, style) { mutableStateOf(style.fontSize) }
    var fitted by remember(text, style) { mutableStateOf(false) }
    Text(
        text,
        style = style.copy(fontSize = fontSize, lineHeight = style.lineHeight),
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && fontSize > minFontSize) {
                fontSize = maxOf(minFontSize.value, fontSize.value * 0.9f).sp
            } else {
                fitted = true
            }
        },
        // Hidden until the size settles, so a too-wide first frame is never drawn.
        modifier = modifier.drawWithContent { if (fitted) drawContent() },
    )
}

data class SummaryItem(val label: String, val value: String, val positive: Boolean = false)

/** Month summary card on top of Operations: caption title, hero amount, a row of items and a footer. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun PfMonthSummary(
    title: String,
    amountLabel: String,
    amount: String,
    items: List<SummaryItem>,
    modifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = PfTheme.colors
    PfCard(modifier) {
        Text(title, style = PfTheme.type.caption, color = c.textMuted, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(PfTheme.dimens.space3))
        Text(amountLabel, style = PfTheme.type.caption, color = c.textMuted)
        PfAmountText(amount, style = PfTheme.type.amountHero, color = c.text)
        Spacer(Modifier.height(PfTheme.dimens.space3))
        // Wraps like the mockup's flex-wrap row: a long label («Доходы минус расходы · 46% дохода») moves down.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space6),
            verticalArrangement = Arrangement.spacedBy(PfTheme.dimens.space2),
        ) {
            items.forEach { item ->
                Column {
                    Text(item.label, style = PfTheme.type.caption, color = c.textMuted)
                    Text(item.value, style = PfTheme.type.bodyStrong, color = if (item.positive) c.positive else c.text, maxLines = 1, softWrap = false)
                }
            }
        }
        if (footer != null) {
            Spacer(Modifier.height(PfTheme.dimens.space3))
            PfDivider()
            footer()
        }
    }
}

enum class InsightState { READY, TENTATIVE, LOCKED, EMPTY }

/**
 * Insight card (mockup `.pf-insight`): icon tile at the top left, and a column beside it: title, amount and
 * chevron in one top-aligned row, then the «предварительно» tag, the description over the whole column width,
 * [content] and the secondary [action] — 4 dp apart. `locked` — transparent with a dashed frame and a 16 dp lock
 * centred on the lock text; `empty` — a muted title and no link.
 */
@Composable
fun PfInsightCard(
    icon: String,
    title: String,
    modifier: Modifier = Modifier,
    amount: String? = null,
    description: String? = null,
    state: InsightState = InsightState.READY,
    lockedText: String? = null,
    chartId: String? = null,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    val shape = RoundedCornerShape(d.radiusXl)
    val locked = state == InsightState.LOCKED
    val empty = state == InsightState.EMPTY
    val clickable = onClick != null && !locked && !empty
    val alpha = if (chartId != null && !locked && !empty) rememberIntroAlpha(chartId) else 1f
    Row(
        modifier
            .alpha(alpha)
            .fillMaxWidth()
            .clip(shape)
            .then(if (locked) Modifier.dashedBorder(c.borderStrong, d.radiusXl) else Modifier.background(c.surface, shape).border(1.dp, c.border, shape))
            .then(if (clickable) Modifier.clickable(role = Role.Button, onClick = onClick!!) else Modifier)
            // The frame sits outside the padding, as a CSS border does (2 px when locked).
            .padding(if (locked) 2.dp else 1.dp)
            .padding(d.space4),
        horizontalArrangement = Arrangement.spacedBy(d.space3),
        verticalAlignment = Alignment.Top,
    ) {
        PfIconTile(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(d.space1)) {
            Row(horizontalArrangement = Arrangement.spacedBy(d.space2), verticalAlignment = Alignment.Top) {
                Text(
                    title,
                    style = PfTheme.type.bodyStrong,
                    color = if (empty) c.textMuted else c.text,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (amount != null && !locked && !empty) {
                    Text(amount, style = PfTheme.type.bodyStrong, color = c.text, maxLines = 1, softWrap = false)
                }
                if (clickable) PfIcon(PfIcons.CHEVRON_RIGHT, contentDescription = null, size = 18.dp, tint = c.textFaint, modifier = Modifier.padding(top = 1.dp))
            }
            if (state == InsightState.TENTATIVE) PfBadge("предварительно")
            if (locked) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.space2)) {
                    PfIcon(PfIcons.LOCK, contentDescription = null, size = 16.dp, tint = c.textMuted)
                    Text(lockedText ?: "Пока не считаем", style = PfTheme.type.caption, color = c.textMuted)
                }
            } else if (description != null) {
                Text(description, style = PfTheme.type.caption, color = c.textMuted)
            }
            content?.invoke(this)
            // `.pf-insight-action { margin-left: -16px }`: a ghost button or link lines up with the text.
            if (action != null) Box(Modifier.offset(x = -d.space4)) { action() }
        }
    }
}

/**
 * Entry card to the assistant, quiet tone: `message` icon on `accent-soft`, title, remaining-questions line.
 * [exhausted] keeps the card and shows when questions reset plus the «История ответов» action.
 */
@Composable
fun PfAskCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    remaining: Int = 5,
    total: Int = 5,
    exhausted: Boolean = false,
    resetText: String = "Новые — в 00:00 по Москве",
    title: String = "Спросите о своих финансах",
) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(PfTheme.dimens.radiusXl)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface, shape)
            .border(1.dp, c.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            // `.pf-ask-quiet`: `min-height: 72px` with `box-sizing: border-box` — the 1 px frame outside the padding.
            .defaultMinSize(minHeight = 72.dp)
            .padding(1.dp)
            .padding(PfTheme.dimens.space4)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PfIconTile(PfIcons.MESSAGE)
        Spacer(Modifier.width(PfTheme.dimens.space3))
        Column(Modifier.weight(1f)) {
            Text(if (exhausted) "Вопросы на сегодня закончились" else title, style = PfTheme.type.bodyStrong, color = c.text)
            val subtitle = when {
                exhausted -> resetText
                remaining == total -> "$total вопросов в день"
                else -> "Осталось $remaining из $total на сегодня"
            }
            Text(subtitle, style = PfTheme.type.caption, color = c.textMuted)
            if (exhausted) {
                // `.pf-ask-action`: 14/20 600 accent, 4 dp above.
                Spacer(Modifier.height(PfTheme.dimens.space1))
                Text("История ответов", style = PfTheme.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.accent)
            }
        }
        // `.pf-ask { gap: var(--space-3) }`, 22 dp chevron.
        Spacer(Modifier.width(PfTheme.dimens.space3))
        PfIcon(PfIcons.CHEVRON_RIGHT, contentDescription = null, size = 22.dp, tint = c.textFaint)
    }
}

/** Period navigation on Analytics: «‹ Сентябрь 2026 ›»; the label opens a picker. */
@Composable
fun PfPeriodNav(
    label: String,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    unitName: String = "месяц",
) {
    val c = PfTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PfIconButton(PfIcons.CHEVRON_LEFT, contentDescription = "Предыдущий $unitName", onClick = { onPrev?.invoke() }, enabled = onPrev != null, modifier = Modifier.testTag(PfTestTags.PERIOD_PREV))
        Box(
            Modifier
                .weight(1f)
                .height(PfTheme.dimens.touch)
                .clip(RoundedCornerShape(PfTheme.dimens.radiusMd))
                .clickable(role = Role.Button, onClick = onPick)
                .testTag(PfTestTags.PERIOD_PICK),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // `.pf-period-label`: 15/20 600, a 16 dp chevron 4 dp after it.
                Text(label, style = PfTheme.type.bodyStrong, color = c.text, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.width(PfTheme.dimens.space1))
                PfIcon(PfIcons.CHEVRON_DOWN, contentDescription = null, size = 16.dp, tint = c.textFaint)
            }
        }
        PfIconButton(PfIcons.CHEVRON_RIGHT, contentDescription = "Следующий $unitName", onClick = { onNext?.invoke() }, enabled = onNext != null, modifier = Modifier.testTag(PfTestTags.PERIOD_NEXT))
    }
}

/** Progress of a file upload (mockup `.pf-drop-progress`): 8 dp `border` track, rounded `accent` fill. */
@Composable
fun PfProgressBar(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(PfTheme.dimens.radiusFull)).background(PfTheme.colors.border)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(8.dp).background(PfTheme.colors.accent, RoundedCornerShape(PfTheme.dimens.radiusFull)))
    }
}

/**
 * File picker zone (mockup `.pf-drop`), on `surface`, items 8 dp apart, 24/16 dp padding.
 * - idle: 1.5 dp dashed `border-strong` frame, upload icon in an `accent-soft` tile, accent title, `hint`;
 * - busy ([busyFileName]): solid `border` frame, a 26 dp file icon in `text`, «Разбираем операции…», the file name
 *   (`hint`) and an 8 dp progress bar.
 */
@Composable
fun PfFileDrop(
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Выбрать файл OFX",
    hint: String = "Файл OFX до 10 МБ",
    busyFileName: String? = null,
    progress: Float? = null,
) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    val shape = RoundedCornerShape(d.radiusXl)
    val busy = busyFileName != null
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface, shape)
            .then(
                if (busy) Modifier.border(1.dp, c.border, shape)
                else Modifier.dashedBorder(c.borderStrong, d.radiusXl, width = 1.dp, dash = 3.dp, gap = 2.dp),
            )
            .then(if (!busy) Modifier.clickable(role = Role.Button, onClick = onPick) else Modifier)
            // `.pf-drop`: `border: 1.5px dashed` outside `padding: 24px 16px`; the frame rounds to 1 dp of ink, the
            // 1.5 dp of space it takes stays.
            .padding(1.5.dp)
            .padding(horizontal = d.space4, vertical = d.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(d.space2),
    ) {
        if (busy) {
            PfIcon(PfIcons.FILE_TEXT, contentDescription = null, size = 26.dp, tint = c.text)
            Text("Разбираем операции…", style = PfTheme.type.bodyStrong, color = c.text, textAlign = TextAlign.Center)
            Text(busyFileName!!, style = PfTheme.type.hint, color = c.textMuted, maxLines = 1, textAlign = TextAlign.Center)
            PfProgressBar(progress ?: 0f)
        } else {
            Box(Modifier.size(d.tile).background(c.accentSoft, RoundedCornerShape(d.radiusMd)), contentAlignment = Alignment.Center) {
                PfIcon(PfIcons.UPLOAD, contentDescription = null, size = d.iconMd, tint = c.accent)
            }
            Text(title, style = PfTheme.type.bodyStrong, color = c.accent, textAlign = TextAlign.Center)
            Text(hint, style = PfTheme.type.hint, color = c.textMuted, textAlign = TextAlign.Center)
        }
    }
}

/**
 * 4-digit passcode dots (mockup `.pf-pass`): empty — 2 dp `border-strong` ring, filled — `accent`. With an
 * [error] every ring turns `warning-text` and filled dots fill with it, so a wrong code stays visible as four
 * warning dots; the error (`13/20 600`) is centred 16 dp below.
 */
@Composable
fun PfPasscodeDots(filled: Int, modifier: Modifier = Modifier, length: Int = 4, label: String = "Код-пароль", error: String? = null) {
    val c = PfTheme.colors
    val invalid = error != null
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space4),
            modifier = Modifier.semantics { contentDescription = "$label: введено $filled из $length цифр" }.testTag(PfTestTags.PASSCODE_DOTS),
        ) {
            repeat(length) { i ->
                val isFilled = i < filled
                val ring = when {
                    invalid -> c.warningText
                    isFilled -> c.accent
                    else -> c.borderStrong
                }
                Box(
                    Modifier
                        .size(16.dp)
                        .background(if (isFilled) ring else Color.Transparent, RoundedCornerShape(PfTheme.dimens.radiusFull))
                        .border(2.dp, ring, RoundedCornerShape(PfTheme.dimens.radiusFull)),
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(PfTheme.dimens.space4))
            Text(
                error, style = PfTheme.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.warningText,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }.testTag(PfTestTags.PASSCODE_ERROR),
            )
        }
    }
}

enum class BiometricKind { NONE, FINGERPRINT, FACE }

/** 3×4 numeric keypad for the passcode: 72 dp round keys, regular 26 sp digits, biometric key bottom-left. */
@Composable
fun PfNumPad(
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    biometric: BiometricKind = BiometricKind.NONE,
    onBiometric: () -> Unit = {},
) {
    val c = PfTheme.colors
    val digitStyle = PfTheme.type.title1.copy(fontWeight = FontWeight.Normal)

    @Composable
    fun Key(content: @Composable () -> Unit, label: String, tag: String?, onClick: (() -> Unit)?) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(PfTheme.dimens.radiusFull))
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label } else Modifier)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier),
            contentAlignment = Alignment.Center,
        ) { content() }
    }

    Column(modifier.semantics { contentDescription = "Цифровая клавиатура" }, verticalArrangement = Arrangement.spacedBy(PfTheme.dimens.space4), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9)).forEach { rowDigits ->
            Row(horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space8)) {
                rowDigits.forEach { d -> Key({ Text("$d", style = digitStyle, color = c.text) }, "$d", PfTestTags.numpad(d)) { onDigit(d) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space8)) {
            when (biometric) {
                BiometricKind.NONE -> Key({}, "", null, null)
                BiometricKind.FINGERPRINT -> Key({ PfIcon(PfIcons.FINGERPRINT, contentDescription = null, size = 28.dp, tint = c.accent) }, "Войти по биометрии", PfTestTags.NUMPAD_BIOMETRIC, onBiometric)
                BiometricKind.FACE -> Key({ PfIcon(PfIcons.SCAN_FACE, contentDescription = null, size = 28.dp, tint = c.accent) }, "Войти по биометрии", PfTestTags.NUMPAD_BIOMETRIC, onBiometric)
            }
            Key({ Text("0", style = digitStyle, color = c.text) }, "0", PfTestTags.numpad(0)) { onDigit(0) }
            Key({ PfIcon(PfIcons.DELETE, contentDescription = null, size = 28.dp, tint = c.text) }, "Стереть", PfTestTags.NUMPAD_DELETE, onDelete)
        }
    }
}
