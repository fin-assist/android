package ru.finassist.pf.core.designsystem.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.components.PfIcon
import ru.finassist.pf.core.designsystem.motion.ChartIntroRegistry
import ru.finassist.pf.core.designsystem.motion.PfMotion
import ru.finassist.pf.core.designsystem.motion.rememberMotionEnabled
import ru.finassist.pf.core.designsystem.theme.PfRadius
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

/**
 * Metric tile for the 2×2 grid. [lockedText] switches to the locked look: dashed 2 dp `border-strong` frame,
 * lock icon and text. Intro: fade-in 300 ms once per [introId] per process — no value counters.
 */
@Composable
fun StatTile(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    note: String? = null,
    valueLabel: String? = null,
    lockedText: String? = null,
    introId: String? = null,
) {
    val c = PfTheme.colors
    val motion = rememberMotionEnabled()
    val locked = lockedText != null
    val playIntro = remember(introId) { introId != null && !locked && ChartIntroRegistry.claim(introId) && motion }
    val alpha = remember { Animatable(if (playIntro) 0f else 1f) }
    LaunchedEffect(playIntro) { if (playIntro) alpha.animateTo(1f, tween(PfMotion.FADE_MS, easing = PfMotion.standard)) }
    val shape = RoundedCornerShape(PfRadius.xl)
    val description = if (locked) "$label: пока не считаем. $lockedText" else "$label: ${valueLabel ?: value}" + (note?.let { ", $it" } ?: "")
    Column(
        modifier
            .alpha(alpha.value)
            .fillMaxWidth()
            .then(if (locked) Modifier.drawBehind {
                drawRoundRect(c.borderStrong, cornerRadius = CornerRadius(18.dp.toPx()), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))))
            } else Modifier.background(c.surface, shape).border(1.dp, c.border, shape))
            .padding(PfSpace.s4)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Text(label, style = PfTheme.type.caption, color = c.textMuted)
        Spacer(Modifier.height(PfSpace.s1))
        if (locked) {
            Row(verticalAlignment = Alignment.Top) {
                PfIcon("lock", size = PfSize.iconSm, tint = c.textMuted, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.width(PfSpace.s1))
                Text(lockedText!!, style = PfTheme.type.caption, color = c.textMuted)
            }
        } else {
            Text(value ?: "—", style = PfTheme.type.amountMd, color = c.text)
            if (note != null) Text(note, style = PfTheme.type.hint, color = c.textMuted)
        }
    }
}
