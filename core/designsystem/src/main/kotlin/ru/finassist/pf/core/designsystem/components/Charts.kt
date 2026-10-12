package ru.finassist.pf.core.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.ChartIntroRegistry
import ru.finassist.pf.core.designsystem.theme.PfMotion
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.designsystem.theme.rememberReducedMotion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One bar of a [PfBarChart]. [value] is null for a period without data (empty column). [partial] marks an
 * unfinished period: outlined bar filled from the bottom for [partialFill] of its height; [note] goes under the
 * label («по 25-е»). [display] is the ready value string for the label and TalkBack.
 */
data class BarPoint(
    val key: String,
    val label: String,
    val spokenLabel: String,
    val value: Float?,
    val display: String,
    val partial: Boolean = false,
    val partialFill: Float = 0.5f,
    val note: String? = null,
)

/**
 * Single-series bar chart (mockup `.pf-chart`): past periods `border-strong`, the highlighted one `accent` —
 * with its label and value in `accent 600`; [highlight] `-1` highlights nothing. Labels, values and notes are
 * `12/16` muted and never cut: a label wider than its column overflows it, centred. A period without data has
 * no mark at all. The intro grows bars from
 * the baseline left to right (500 ms each, 40 ms stagger, emphasized decelerate) once per process per
 * [chartId]; later value changes transition in 250 ms. With "Remove animations" bars are drawn at full height.
 *
 * @param chartId stable id for [ChartIntroRegistry] (`"analytics.expense.monthly"`).
 */
@Composable
fun PfBarChart(
    chartId: String,
    points: List<BarPoint>,
    modifier: Modifier = Modifier,
    highlight: Int = points.lastIndex,
    title: String? = null,
    height: Dp = 120.dp,
    showValues: Boolean = false,
) {
    val c = PfTheme.colors
    val reduced = rememberReducedMotion()
    val playIntro = remember(chartId) { !reduced && ChartIntroRegistry.claimIntro(chartId) }
    val maxAbs = max(points.maxOfOrNull { abs(it.value ?: 0f) } ?: 0f, 1f)

    // Per-bar progress 0..1: intro animates from 0, value changes animate the target height.
    val targets = points.map { (it.value ?: 0f) / maxAbs }
    val anims = remember(chartId, points.size) { points.map { Animatable(if (playIntro) 0f else (it.value ?: 0f) / maxAbs) } }
    // The intro runs on the first targets only; later changes (even through zero) are 250 ms transitions.
    val introPending = remember(chartId) { booleanArrayOf(playIntro) }
    LaunchedEffect(targets) {
        val intro = introPending[0]
        introPending[0] = false
        anims.forEachIndexed { i, anim ->
            if (i >= targets.size) return@forEachIndexed
            launch {
                if (intro && targets[i] != 0f) {
                    delay(i * PfMotion.INTRO_STAGGER_MS.toLong())
                    anim.animateTo(targets[i], tween(PfMotion.INTRO_DURATION_MS, easing = PfMotion.EmphasizedDecelerate))
                } else if (reduced) {
                    anim.snapTo(targets[i])
                } else {
                    anim.animateTo(targets[i], tween(PfMotion.CHANGE_DURATION_MS, easing = PfMotion.Standard))
                }
            }
        }
    }

    val description = buildString {
        append(title ?: "График")
        append(": ")
        append(points.joinToString("; ") { p -> listOfNotNull(p.spokenLabel, p.note, if (p.value == null) "нет данных" else p.display).joinToString(", ") })
    }

    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }) {
        val hasNegative = points.any { (it.value ?: 0f) < 0f }
        val baselineFraction = if (hasNegative) 0.5f else 1f
        // `.pf-chart-col`: a bottom-aligned column with 4 dp gaps — with `showValues` the value (16 dp) sits 4 dp
        // above its own bar, so the row is 20 dp taller than the tallest bar.
        val valueSpace = if (showValues) 20.dp else 0.dp
        Row(Modifier.fillMaxWidth().height(height + valueSpace), horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space2)) {
            points.forEachIndexed { i, p ->
                val progress = anims.getOrNull(i)?.value ?: 0f
                val color = if (i == highlight) c.accent else c.borderStrong
                // Bar geometry in fractions of the chart height: top edge and height of the bar.
                val barFraction = abs(progress) * (if (hasNegative) 0.5f else 1f)
                val topFraction = if (progress >= 0f) baselineFraction - barFraction else baselineFraction
                Box(Modifier.weight(1f).fillMaxWidth().height(height + valueSpace)) {
                    if (showValues) {
                        val labelAlpha = ((progress / (targets.getOrNull(i)?.takeIf { it != 0f } ?: 1f)) - 0.7f).coerceIn(0f, 0.3f) / 0.3f
                        val on = i == highlight
                        Text(
                            if (p.value == null) "" else p.display,
                            style = if (on) PfTheme.type.hint.copy(fontWeight = FontWeight.SemiBold) else PfTheme.type.hint,
                            color = (if (on) c.accent else c.textMuted).copy(alpha = if (reduced) 1f else labelAlpha),
                            maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                // The text's 16 dp line ends 4 dp above the bar: its top is at the bar's top minus 20 dp,
                                // which, with the 20 dp reserved above the chart, is the bar's top within the chart.
                                .offset { IntOffset(0, (height.toPx() * topFraction).roundToInt()) }
                                .wrapContentWidth(Alignment.CenterHorizontally, unbounded = true),
                        )
                    }
                    Canvas(Modifier.fillMaxWidth().height(height).align(Alignment.BottomCenter)) {
                        val barHeight = barFraction * size.height
                        val top = topFraction * size.height
                        val baselineY = size.height * baselineFraction
                        // `.pf-chart-bar { border-radius: var(--space-2) }`
                        val radius = CornerRadius(8.dp.toPx())
                        // Empty column: nothing is drawn, the gap itself says «no data».
                        if (p.value == null) return@Canvas
                        if (p.partial) {
                            val stroke = 2.dp.toPx()
                            drawRoundRect(color, topLeft = Offset(stroke / 2, top + stroke / 2), size = Size(size.width - stroke, max(barHeight - stroke, 0f)), cornerRadius = radius, style = Stroke(stroke))
                            val fill = p.partialFill.coerceIn(0.25f, 0.75f).let { (it * 4).roundToInt() / 4f }
                            val fillHeight = barHeight * fill
                            val fillTop = if (progress >= 0f) baselineY - fillHeight else baselineY
                            // `.pf-chart-bar-partial`: a gradient inside the rounded outline — the fill's top edge is
                            // flat, only the outline's corners are round.
                            val outline = Path().apply { addRoundRect(RoundRect(Rect(Offset(0f, top), Size(size.width, barHeight)), radius)) }
                            clipPath(outline) { drawRect(color, topLeft = Offset(0f, fillTop), size = Size(size.width, fillHeight)) }
                        } else {
                            drawRoundRect(color, topLeft = Offset(0f, top), size = Size(size.width, barHeight), cornerRadius = radius)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(PfTheme.dimens.space1))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space2)) {
            val anyNote = points.any { it.note != null }
            points.forEachIndexed { i, p ->
                val on = i == highlight
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PfTheme.dimens.space1)) {
                    Text(
                        p.label,
                        style = if (on) PfTheme.type.hint.copy(fontWeight = FontWeight.SemiBold) else PfTheme.type.hint,
                        color = if (on) c.accent else c.textMuted,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true),
                    )
                    // As in the mockup, every column keeps the note line when any column has a note.
                    if (anyNote) {
                        Text(
                            p.note ?: "",
                            style = PfTheme.type.hint, color = c.textMuted, maxLines = 1, softWrap = false,
                            modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Category row with a share bar (mockup `.pf-cat`): 36 dp icon tile, name, share and amount (`14/20`, amount
 * `600`), an 8 dp bar 4 dp under them, chevron; a link row is 48 dp tall with no extra padding. The bar grows from
 * 0 on the intro (rows staggered 40 ms) and transitions on value changes. [percent] is relative to the largest
 * category.
 */
@Composable
fun PfCategoryBar(
    chartId: String,
    index: Int,
    icon: String,
    label: String,
    amount: String,
    percent: Float,
    modifier: Modifier = Modifier,
    share: String? = null,
    spoken: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = PfTheme.colors
    val reduced = rememberReducedMotion()
    val playIntro = remember(chartId) { !reduced && ChartIntroRegistry.claimIntro("$chartId#$index") }
    val anim = remember(chartId, index) { Animatable(if (playIntro) 0f else percent) }
    LaunchedEffect(percent) {
        if (playIntro && anim.value == 0f) {
            delay(index * PfMotion.INTRO_STAGGER_MS.toLong())
            anim.animateTo(percent, tween(PfMotion.INTRO_DURATION_MS, easing = PfMotion.EmphasizedDecelerate))
        } else if (reduced) {
            anim.snapTo(percent)
        } else {
            anim.animateTo(percent, tween(PfMotion.CHANGE_DURATION_MS, easing = PfMotion.Standard))
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PfTheme.dimens.radiusMd))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = PfTheme.dimens.touch)
            .semantics(mergeDescendants = true) { contentDescription = spoken ?: listOfNotNull(label, amount, share?.let { "$it расходов" }).joinToString(", ") },
        horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PfIconTile(icon, small = true)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space2), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = PfTheme.type.body, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (share != null) Text(share, style = PfTheme.type.body.copy(fontFeatureSettings = "tnum"), color = c.textMuted, maxLines = 1)
                Text(amount, style = PfTheme.type.body.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = c.text, maxLines = 1)
            }
            Spacer(Modifier.height(PfTheme.dimens.space1))
            Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(PfTheme.dimens.radiusFull))) {
                Box(Modifier.fillMaxWidth().height(8.dp).background(c.border))
                Box(Modifier.fillMaxWidth(anim.value.coerceIn(0f, 1f)).height(8.dp).background(c.accent, RoundedCornerShape(PfTheme.dimens.radiusFull)))
            }
        }
        if (onClick != null) PfIcon(PfIcons.CHEVRON_RIGHT, contentDescription = null, size = 18.dp, tint = c.textFaint)
    }
}

/** Fade-in used by StatTile on its first appearance (300 ms); no counters. Returns the alpha to apply. */
@Composable
fun rememberIntroAlpha(chartId: String): Float {
    val reduced = rememberReducedMotion()
    val playIntro = remember(chartId) { !reduced && ChartIntroRegistry.claimIntro(chartId) }
    val alpha = remember(chartId) { Animatable(if (playIntro) 0f else 1f) }
    LaunchedEffect(chartId) {
        if (alpha.value < 1f) alpha.animateTo(1f, tween(PfMotion.FADE_DURATION_MS, easing = PfMotion.Standard))
    }
    return alpha.value
}
