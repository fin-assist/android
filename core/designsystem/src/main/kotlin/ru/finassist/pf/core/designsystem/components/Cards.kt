package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.theme.PfRadius
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

enum class InsightState { Ready, Tentative, Locked, Empty }

/** Insight card on «Аналитика» (README «InsightCard»). */
@Composable
fun InsightCard(
    icon: String,
    title: String,
    modifier: Modifier = Modifier,
    amount: String? = null,
    description: String? = null,
    state: InsightState = InsightState.Ready,
    lockedText: String? = null,
    onClick: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(PfRadius.xl)
    val locked = state == InsightState.Locked
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (locked) Modifier.drawBehind {
                    drawRoundRect(c.borderStrong, cornerRadius = CornerRadius(18.dp.toPx()), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))))
                } else Modifier.background(c.surface, shape).border1(c.border, shape),
            )
            .then(if (onClick != null && !locked && state != InsightState.Empty) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(PfSpace.s4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            IconTile(icon)
            Spacer(Modifier.width(PfSpace.s3))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = PfTheme.type.bodyStrong, color = if (state == InsightState.Empty) c.textMuted else c.text, modifier = Modifier.weight(1f, fill = false))
                    if (state == InsightState.Tentative) {
                        Spacer(Modifier.width(PfSpace.s2))
                        Badge("предварительно")
                    }
                }
                if (locked) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PfIcon("lock", size = PfSize.iconSm, tint = c.textMuted)
                        Spacer(Modifier.width(PfSpace.s1))
                        Text(lockedText.orEmpty(), style = PfTheme.type.caption, color = c.textMuted)
                    }
                } else if (description != null) {
                    Text(description, style = PfTheme.type.caption, color = c.textMuted)
                }
            }
            if (!locked && amount != null) {
                Spacer(Modifier.width(PfSpace.s2))
                Text(amount, style = PfTheme.type.bodyStrong, color = c.text, maxLines = 1)
            }
            if (onClick != null && !locked && state != InsightState.Empty) {
                Spacer(Modifier.width(PfSpace.s1))
                PfIcon("chevron-right", size = PfSize.iconMd, tint = c.textFaint)
            }
        }
        if (content != null && !locked) {
            Spacer(Modifier.height(PfSpace.s3))
            content()
        }
        if (action != null && !locked) {
            Spacer(Modifier.height(PfSpace.s2))
            action()
        }
    }
}

/** Entry to the assistant: quiet card, stays visible when the limit is exhausted. */
@Composable
fun AskCard(
    remaining: Int,
    total: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    exhausted: Boolean = false,
    resetText: String = "Новые — в 00:00 по Москве",
    title: String = "Спросите о своих финансах",
) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(PfRadius.xl)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(c.surface, shape)
            .border1(c.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(PfSpace.s4)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile("message")
        Spacer(Modifier.width(PfSpace.s3))
        Column(Modifier.weight(1f)) {
            Text(if (exhausted) "Вопросы на сегодня закончились" else title, style = PfTheme.type.bodyStrong, color = c.text)
            Text(
                when {
                    exhausted -> resetText
                    remaining >= total -> "$total вопросов в день"
                    else -> "Осталось $remaining из $total на сегодня"
                },
                style = PfTheme.type.caption, color = c.textMuted,
            )
            if (exhausted) Text("История ответов", style = PfTheme.type.captionStrong, color = c.accent)
        }
        Spacer(Modifier.width(PfSpace.s1))
        PfIcon("chevron-right", size = PfSize.iconMd, tint = c.textFaint)
    }
}

/** «‹ Сентябрь 2026 ›» period navigation; the label opens a picker. */
@Composable
fun PeriodNav(label: String, onPrev: (() -> Unit)?, onNext: (() -> Unit)?, onPick: () -> Unit, unitName: String = "месяц", modifier: Modifier = Modifier) {
    val c = PfTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PfIconButton("chevron-left", contentDescription = "Предыдущий $unitName", onClick = { onPrev?.invoke() }, tint = if (onPrev != null) c.text else c.borderStrong)
        Box(Modifier.weight(1f).heightIn(min = PfSize.touch).clickable(role = Role.Button, onClick = onPick), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = PfTheme.type.title2, color = c.text)
                Spacer(Modifier.width(PfSpace.s1))
                PfIcon("chevron-down", size = PfSize.iconMd, tint = c.textMuted)
            }
        }
        PfIconButton("chevron-right", contentDescription = "Следующий $unitName", onClick = { onNext?.invoke() }, tint = if (onNext != null) c.text else c.borderStrong)
    }
}

/** Numbered instruction steps. */
@Composable
fun OrderedSteps(steps: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    val c = PfTheme.colors
    Column(modifier.fillMaxWidth()) {
        steps.forEachIndexed { i, step ->
            Row(Modifier.padding(vertical = PfSpace.s2), verticalAlignment = Alignment.Top) {
                Box(Modifier.width(28.dp).height(28.dp).background(c.accentSoft, RoundedCornerShape(PfRadius.full)), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", style = PfTheme.type.captionStrong, color = c.accent)
                }
                Spacer(Modifier.width(PfSpace.s3))
                Column(Modifier.weight(1f).padding(top = 4.dp)) { step() }
            }
        }
    }
}
