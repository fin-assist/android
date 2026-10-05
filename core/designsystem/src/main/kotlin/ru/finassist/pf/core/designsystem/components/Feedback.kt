package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.theme.PfRadius
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

enum class NoticeTone { Info, Warning, Positive, Limit }

/** Notice (README «Notice»): info / warning / positive / limit; `alert` announces immediately. */
@Composable
fun Notice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.Info,
    title: String? = null,
    alert: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val c = PfTheme.colors
    val (bg, fg, icon) = when (tone) {
        NoticeTone.Info -> Triple(c.infoSoft, c.infoText, "info")
        NoticeTone.Warning -> Triple(c.warningSoft, c.warningText, "alert-triangle")
        NoticeTone.Positive -> Triple(c.positiveSoft, c.positive, "check-circle")
        NoticeTone.Limit -> Triple(c.infoSoft, c.infoText, "clock")
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(PfRadius.lg))
            .padding(PfSpace.s4)
            .semantics { liveRegion = if (alert) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        PfIcon(icon, size = PfSize.iconMd, tint = fg, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(PfSpace.s3))
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = PfTheme.type.captionStrong, color = fg)
            Text(text, style = PfTheme.type.caption, color = fg)
            if (action != null) {
                Spacer(Modifier.height(PfSpace.s1))
                action()
            }
        }
    }
}

/** Empty state: large status icon, title, explanatory text; actions are placed by the screen below it. */
@Composable
fun EmptyState(icon: String, title: String, text: String? = null, modifier: Modifier = Modifier) {
    val c = PfTheme.colors
    Column(modifier.fillMaxWidth().padding(vertical = PfSpace.s8), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(PfSize.status).background(c.accentSoft, RoundedCornerShape(PfRadius.xl)), contentAlignment = Alignment.Center) {
            PfIcon(icon, size = 28.dp, tint = c.accent)
        }
        Spacer(Modifier.height(PfSpace.s4))
        Text(title, style = PfTheme.type.title2, color = c.text, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(PfSpace.s2))
            Text(text, style = PfTheme.type.body, color = c.textMuted, textAlign = TextAlign.Center)
        }
    }
}

enum class StatusTone { Positive, Warning, Info }

/** Large status icon (64) for import result / error screens and other outcomes. */
@Composable
fun StatusHero(icon: String, tone: StatusTone, title: String, text: String? = null, modifier: Modifier = Modifier) {
    val c = PfTheme.colors
    val (bg, fg) = when (tone) {
        StatusTone.Positive -> c.positiveSoft to c.positive
        StatusTone.Warning -> c.warningSoft to c.warningText
        StatusTone.Info -> c.infoSoft to c.infoText
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(PfSize.status).background(bg, RoundedCornerShape(PfRadius.xl)), contentAlignment = Alignment.Center) {
            PfIcon(icon, size = 32.dp, tint = fg)
        }
        Spacer(Modifier.height(PfSpace.s4))
        Text(title, style = PfTheme.type.title1, color = c.text, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(PfSpace.s2))
            Text(text, style = PfTheme.type.lead, color = c.textMuted, textAlign = TextAlign.Center)
        }
    }
}

/** Small badge («есть чек», «предварительно»): micro text on a soft background or an outline. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, positive: Boolean = false, icon: String? = null) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(PfRadius.full)
    Row(
        modifier = modifier
            .then(if (positive) Modifier.background(c.positiveSoft, shape) else Modifier.border1(c.borderStrong, shape))
            .padding(horizontal = PfSpace.s2, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PfSpace.s1),
    ) {
        if (icon != null) PfIcon(icon, size = PfSize.iconSm, tint = if (positive) c.positive else c.textMuted)
        Text(text, style = PfTheme.type.micro, color = if (positive) c.positive else c.textMuted)
    }
}

/** «Осталось N из 5» with five ticks. */
@Composable
fun LimitMeter(used: Int, total: Int = 5, modifier: Modifier = Modifier) {
    val c = PfTheme.colors
    val remaining = (total - used).coerceAtLeast(0)
    Row(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        Text(if (used == 0) "$total вопросов в день" else "Осталось $remaining из $total", style = PfTheme.type.caption, color = c.textMuted, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpace.s1)) {
            repeat(total) { i ->
                Box(Modifier.width(16.dp).height(4.dp).background(if (i < remaining) c.accent else c.borderStrong, RoundedCornerShape(PfRadius.full)))
            }
        }
    }
}

/** «Найдено 9 операций» — count only, no sums; the caller formats the plural. */
@Composable
fun SearchSummary(label: String, modifier: Modifier = Modifier) {
    Text(label, style = PfTheme.type.caption, color = PfTheme.colors.textMuted, modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite })
}
