package ru.finassist.pf.core.designsystem.charts

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.finassist.pf.core.designsystem.components.IconTile
import ru.finassist.pf.core.designsystem.motion.ChartIntroRegistry
import ru.finassist.pf.core.designsystem.motion.PfMotion
import ru.finassist.pf.core.designsystem.motion.rememberMotionEnabled
import ru.finassist.pf.core.designsystem.theme.PfRadius
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.designsystem.components.PfIcon

/**
 * Category row with a share bar (README «CategoryBar»). `percent` 0–100 is relative to the largest category;
 * `share` is the textual share of all expenses. Intro: bar grows from 0 once per [introId] per process,
 * rows staggered by [introIndex] × 40 ms; later changes transition in 250 ms.
 */
@Composable
fun CategoryBar(
    icon: String,
    label: String,
    amount: String,
    percent: Int,
    modifier: Modifier = Modifier,
    share: String? = null,
    note: String? = null,
    introId: String? = null,
    introIndex: Int = 0,
    spoken: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = PfTheme.colors
    val motion = rememberMotionEnabled()
    val target = (percent.coerceIn(0, 100) / 100f)
    val progress = remember { Animatable(0f) }
    var visible by remember { mutableStateOf(false) }
    val playIntro = remember(introId) { introId != null && ChartIntroRegistry.claim(introId) && motion }
    var introDone by remember(introId) { mutableStateOf(!playIntro) }
    LaunchedEffect(target, visible, introDone) {
        if (!introDone) {
            if (!visible) return@LaunchedEffect
            delay((introIndex * PfMotion.INTRO_STAGGER_MS).toLong())
            progress.animateTo(target, tween(PfMotion.INTRO_MS, easing = PfMotion.emphasizedDecelerate))
            introDone = true
        } else if (motion) progress.animateTo(target, tween(PfMotion.TRANSITION_MS, easing = PfMotion.standard)) else progress.snapTo(target)
    }
    val description = spoken ?: "$label, $amount" + (share?.let { ", $it расходов" } ?: "")
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = PfSize.touch)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = PfSpace.s2)
            .onGloballyPositioned { if (it.boundsInWindow().height > 0) visible = true }
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Spacer(Modifier.width(PfSpace.s3))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = PfTheme.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (share != null) {
                    Text(share, style = PfTheme.type.caption, color = c.textMuted)
                    Spacer(Modifier.width(PfSpace.s2))
                }
                Text(amount, style = PfTheme.type.bodyStrong, color = c.text, maxLines = 1)
            }
            if (note != null) Text(note, style = PfTheme.type.hint, color = c.textMuted, maxLines = 1)
            Spacer(Modifier.height(PfSpace.s1))
            Box(Modifier.fillMaxWidth().height(8.dp).background(c.border, RoundedCornerShape(PfRadius.full))) {
                if (percent > 0) Box(Modifier.fillMaxWidth(progress.value.coerceIn(0f, 1f)).height(8.dp).background(c.accent, RoundedCornerShape(PfRadius.full)))
            }
        }
        if (onClick != null) {
            Spacer(Modifier.width(PfSpace.s2))
            PfIcon("chevron-right", size = PfSize.iconMd, tint = c.textFaint)
        }
    }
}
