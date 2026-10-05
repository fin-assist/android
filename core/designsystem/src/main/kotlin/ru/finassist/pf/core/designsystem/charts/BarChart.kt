package ru.finassist.pf.core.designsystem.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.finassist.pf.core.designsystem.motion.ChartIntroRegistry
import ru.finassist.pf.core.designsystem.motion.PfMotion
import ru.finassist.pf.core.designsystem.motion.rememberMotionEnabled
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * One bar of [BarChart]. [value] is null when there is no data for the period (empty slot, not zero).
 * [partialFill] 0–1 marks an unfinished period: bar drawn as a 2 dp outline, filled from the bottom
 * by that share (rounded to quarters, clamped 25–75 %, README «BarChart»).
 */
data class Bar(
    val label: String,
    val spokenName: String,
    val value: Double?,
    val display: String? = null,
    val partialFill: Float? = null,
    val partialNote: String? = null,
)

/**
 * Single-series bar chart (README «BarChart», «Движение»). Past bars `border-strong`, highlighted `accent`.
 * Intro: bars grow from the baseline left to right (500 ms each, 40 ms stagger), once per [introId] per process,
 * when at least half of the chart is visible. Value changes animate in 250 ms without re-growing.
 */
@Composable
fun BarChart(
    bars: List<Bar>,
    modifier: Modifier = Modifier,
    introId: String? = null,
    highlight: Int = bars.lastIndex,
    height: Dp = 120.dp,
    title: String? = null,
    showValues: Boolean = false,
    formatValue: (Double) -> String = { it.roundToInt().toString() },
) {
    val c = PfTheme.colors
    val motion = rememberMotionEnabled()
    val maxAbs = bars.mapNotNull { it.value }.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
    val hasNegative = bars.any { (it.value ?: 0.0) < 0 }

    // One progress per bar: 0 = baseline, 1 = full height. Values animate via a separate target list.
    val progress = remember(bars.size) { List(bars.size) { Animatable(0f) } }
    val targets = remember(bars.size) { List(bars.size) { Animatable(0f) } }
    var visibleEnough by remember { mutableStateOf(false) }
    val playIntro = remember(introId) { introId != null && ChartIntroRegistry.claim(introId) && motion }
    var introDone by remember(introId) { mutableStateOf(!playIntro) }

    LaunchedEffect(bars, visibleEnough, introDone) {
        val ratios = bars.map { b -> ((b.value ?: 0.0) / maxAbs).toFloat() }
        if (!introDone) {
            if (!visibleEnough) return@LaunchedEffect
            ratios.forEachIndexed { i, r -> targets[i].snapTo(r) }
            bars.indices.map { i ->
                launch {
                    delay((i * PfMotion.INTRO_STAGGER_MS).toLong())
                    progress[i].animateTo(1f, tween(PfMotion.INTRO_MS, easing = PfMotion.emphasizedDecelerate))
                }
            }.forEach { it.join() }
            introDone = true
        } else {
            ratios.forEachIndexed { i, r ->
                launch {
                    if (motion) targets[i].animateTo(r, tween(PfMotion.TRANSITION_MS, easing = PfMotion.standard)) else targets[i].snapTo(r)
                    progress[i].snapTo(1f)
                }
            }
        }
    }

    val description = buildString {
        if (title != null) append(title).append(": ")
        append(bars.joinToString("; ") { b ->
            val v = b.value?.let { b.display ?: formatValue(it) } ?: "нет данных"
            b.spokenName + (b.partialNote?.let { ", $it" } ?: "") + ", " + v
        })
    }
    val density = LocalDensity.current
    Column(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInWindow()
                val h = coords.size.height.toFloat()
                if (h > 0 && bounds.height >= h / 2f) visibleEnough = true
            }
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.fillMaxWidth().height(height + if (showValues) 16.dp else 0.dp)) {
            val n = bars.size
            if (n == 0) return@Canvas
            val gap = with(density) { 8.dp.toPx() }
            val barW = (size.width - gap * (n - 1)) / n
            val topPad = if (showValues) with(density) { 16.dp.toPx() } else 0f
            val chartH = size.height - topPad
            val baseline = if (hasNegative) topPad + chartH / 2f else topPad + chartH
            val scale = if (hasNegative) chartH / 2f else chartH
            val stroke = with(density) { 2.dp.toPx() }
            val radius = CornerRadius(with(density) { 4.dp.toPx() })
            bars.forEachIndexed { i, bar ->
                val x = i * (barW + gap)
                val color = if (i == highlight) c.accent else c.borderStrong
                if (bar.value == null) {
                    // Empty slot: thin baseline tick only.
                    drawRect(c.border, Offset(x, baseline - stroke / 2), Size(barW, stroke))
                    return@forEachIndexed
                }
                val ratio = targets[i].value * progress[i].value
                val h = abs(ratio) * scale
                val top = if (ratio >= 0) baseline - h else baseline
                val rectSize = Size(barW, max(h, stroke))
                val rectTop = if (ratio >= 0) top else baseline
                if (bar.partialFill != null) {
                    val fill = ((bar.partialFill * 4).roundToInt() / 4f).coerceIn(0.25f, 0.75f)
                    drawRoundRect(color, Offset(x + stroke / 2, rectTop + stroke / 2), Size(barW - stroke, max(rectSize.height - stroke, 0f)), radius, Stroke(stroke))
                    val fillH = rectSize.height * fill
                    drawRoundRect(color, Offset(x, rectTop + rectSize.height - fillH), Size(barW, fillH), radius)
                } else {
                    drawRoundRect(color, Offset(x, rectTop), rectSize, radius)
                }
            }
        }
        Spacer(Modifier.height(PfSpace.s1))
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { bar ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(bar.label, style = PfTheme.type.hint, color = c.textMuted, textAlign = TextAlign.Center, maxLines = 1)
                    if (bar.partialNote != null) Text(bar.partialNote, style = PfTheme.type.hint, color = c.textMuted, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}
