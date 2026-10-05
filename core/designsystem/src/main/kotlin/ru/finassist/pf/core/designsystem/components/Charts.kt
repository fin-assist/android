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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * Single-series bar chart: past periods `border-strong`, the highlighted one `accent`. The intro grows bars from
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
    LaunchedEffect(targets) {
        anims.forEachIndexed { i, anim ->
            if (i >= targets.size) return@forEachIndexed
            launch {
                if (playIntro && anim.value == 0f && targets[i] != 0f) {
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
        Row(Modifier.fillMaxWidth().height(height + if (showValues) 20.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space2)) {
            points.forEachIndexed { i, p ->
                val progress = anims.getOrNull(i)?.value ?: 0f
                val color = if (i == highlight) c.accent else c.borderStrong
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (showValues) {
                        val labelAlpha = ((progress / (targets.getOrNull(i)?.takeIf { it != 0f } ?: 1f)) - 0.7f).coerceIn(0f, 0.3f) / 0.3f
                        Text(
                            if (p.value == null) "" else p.display,
                            style = PfTheme.type.hint, color = c.textMuted.copy(alpha = if (reduced) 1f else labelAlpha),
                            maxLines = 1, textAlign = TextAlign.Center,
                        )
                    }
                    Canvas(Modifier.fillMaxWidth().height(height)) {
                        val baselineY = size.height * baselineFraction
                        val barHeight = abs(progress) * (if (hasNegative) size.height / 2 else size.height)
                        val top = if (progress >= 0f) baselineY - barHeight else baselineY
                        val radius = CornerRadius(6.dp.toPx())
                        if (p.value == null) {
                            // Empty column: a thin baseline tick only.
                            drawRoundRect(c.border, topLeft = Offset(0f, baselineY - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx()), cornerRadius = radius)
                            return@Canvas
                        }
                        if (p.partial) {
                            val stroke = 2.dp.toPx()
                            drawRoundRect(color, topLeft = Offset(stroke / 2, top + stroke / 2), size = Size(size.width - stroke, max(barHeight - stroke, 0f)), cornerRadius = radius, style = Stroke(stroke))
                            val fill = p.partialFill.coerceIn(0.25f, 0.75f).let { (it * 4).roundToInt() / 4f }
                            val fillHeight = barHeight * fill
                            drawRoundRect(color, topLeft = Offset(0f, baselineY - fillHeight), size = Size(size.width, fillHeight), cornerRadius = radius)
                        } else {
                            drawRoundRect(color, topLeft = Offset(0f, top), size = Size(size.width, barHeight), cornerRadius = radius)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(PfTheme.dimens.space1))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space2)) {
            points.forEachIndexed { i, p ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(p.label, style = PfTheme.type.hint, color = if (i == highlight) c.text else c.textMuted, maxLines = 1)
                    if (p.note != null) Text(p.note, style = PfTheme.type.micro, color = c.textMuted, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Category row with a share bar: icon tile, name, share text, amount, chevron; the bar grows from 0 on the
 * intro (rows staggered 40 ms) and transitions on value changes. [percent] is relative to the largest category.
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
            .padding(vertical = PfTheme.dimens.space3)
            .semantics(mergeDescendants = true) { contentDescription = spoken ?: listOfNotNull(label, amount, share?.let { "$it расходов" }).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PfIconTile(icon)
        Spacer(Modifier.width(PfTheme.dimens.space3))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = PfTheme.type.body, color = c.text, modifier = Modifier.weight(1f), maxLines = 1)
                if (share != null) {
                    Text(share, style = PfTheme.type.caption, color = c.textMuted)
                    Spacer(Modifier.width(PfTheme.dimens.space2))
                }
                Text(amount, style = PfTheme.type.bodyStrong, color = c.text, maxLines = 1)
            }
            Spacer(Modifier.height(PfTheme.dimens.space1))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(PfTheme.dimens.radiusFull))) {
                Box(Modifier.fillMaxWidth().height(6.dp).background(c.border))
                Box(Modifier.fillMaxWidth(anim.value.coerceIn(0f, 1f)).height(6.dp).background(c.accent, RoundedCornerShape(PfTheme.dimens.radiusFull)))
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(PfTheme.dimens.space2))
            PfIcon(PfIcons.CHEVRON_RIGHT, contentDescription = null, size = PfTheme.dimens.iconMd, tint = c.textFaint)
        }
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
