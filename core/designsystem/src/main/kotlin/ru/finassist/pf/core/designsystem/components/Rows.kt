package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** 1 px divider in `border`. */
@Composable
fun Divider(modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(modifier.fillMaxWidth().height(1.dp).background(if (strong) PfTheme.colors.borderStrong else PfTheme.colors.border))
}

/**
 * Settings/list row inside a flush card: accent icon, title, optional description, value, chevron or switch.
 * Min height 56. `onClick` with `hasPopup` marks a picker row for TalkBack.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    description: String? = null,
    value: String? = null,
    valueLabel: String? = null,
    valueAccent: Boolean = false,
    accentTitle: Boolean = false,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = onClick != null && !accentTitle,
    trailing: (@Composable () -> Unit)? = null,
    divider: Boolean = true,
) {
    val c = PfTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PfSize.row)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(horizontal = PfSpace.s4, vertical = PfSpace.s3)
                .then(if (valueLabel != null) Modifier.semantics(mergeDescendants = true) { contentDescription = "$title, $valueLabel" } else Modifier.semantics(mergeDescendants = true) {}),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                PfIcon(icon, size = PfSize.iconMd, tint = c.accent)
                Spacer(Modifier.width(PfSpace.s3))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = PfTheme.type.bodyStrong, color = if (accentTitle) c.accent else c.text)
                if (description != null) Text(description, style = PfTheme.type.caption, color = c.textMuted)
            }
            if (value != null) {
                Spacer(Modifier.width(PfSpace.s2))
                Text(value, style = PfTheme.type.caption, color = if (valueAccent) c.accent else c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) {
                Spacer(Modifier.width(PfSpace.s2))
                trailing()
            } else if (showChevron) {
                Spacer(Modifier.width(PfSpace.s1))
                PfIcon("chevron-right", size = PfSize.iconMd, tint = c.textFaint)
            }
        }
        if (divider) Divider(Modifier.padding(start = if (icon != null) PfSpace.s4 + PfSize.iconMd + PfSpace.s3 else PfSpace.s4))
    }
}

enum class ValueTone { Default, Positive, Muted }

/** «Подпись — значение» row (README «DataRow»): details, income rows, assistant breakdowns. */
@Composable
fun DataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    tone: ValueTone = ValueTone.Default,
    total: Boolean = false,
    valueLabel: String? = null,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
) {
    val c = PfTheme.colors
    Column(modifier.fillMaxWidth()) {
        if (total) Divider(strong = true)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.heightIn(min = PfSize.touch).clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(vertical = PfSpace.s2)
                .semantics(mergeDescendants = true) { if (valueLabel != null) contentDescription = "$label, $valueLabel" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = PfTheme.type.body, color = c.text)
                if (sublabel != null) Text(sublabel, style = PfTheme.type.hint, color = c.textMuted)
            }
            Text(
                value,
                style = if (total) PfTheme.type.bodyStrong.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else PfTheme.type.bodyStrong,
                color = when (tone) { ValueTone.Positive -> c.positive; ValueTone.Muted -> c.textMuted; else -> c.text },
                maxLines = 1,
            )
            if (onClick != null) {
                Spacer(Modifier.width(PfSpace.s1))
                PfIcon("chevron-right", size = PfSize.iconMd, tint = c.textFaint)
            }
        }
        if (divider && !total) Divider()
    }
}

/** Date group of transactions: overline date in `text-faint`, then rows. */
@Composable
fun TransactionGroup(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = PfTheme.type.overline, color = PfTheme.colors.textFaint, modifier = Modifier.padding(top = PfSpace.s3, bottom = PfSpace.s1))
        content()
    }
}

/** Operation row: category tile, title, subtitle, amount (pre-formatted) and a note under it. */
@Composable
fun TransactionRow(
    icon: String,
    title: String,
    subtitle: String,
    amount: String,
    modifier: Modifier = Modifier,
    income: Boolean = false,
    muted: Boolean = false,
    note: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = PfTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = PfSize.row)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = PfSpace.s2)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Spacer(Modifier.width(PfSpace.s3))
        Column(Modifier.weight(1f)) {
            Text(title, style = PfTheme.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = PfTheme.type.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(PfSpace.s2))
        Column(horizontalAlignment = Alignment.End) {
            Text(amount, style = PfTheme.type.bodyStrong, color = when { income -> c.positive; muted -> c.textMuted; else -> c.text }, maxLines = 1)
            if (note != null) Text(note, style = PfTheme.type.micro, color = c.textMuted, maxLines = 1)
        }
    }
}

data class SummaryItem(val label: String, val value: String, val tone: ValueTone = ValueTone.Default)

/** Month summary card on «Операции»: title, hero amount, secondary items, footer. */
@Composable
fun MonthSummary(title: String, amountLabel: String, amount: String, items: List<SummaryItem>, modifier: Modifier = Modifier, footer: (@Composable () -> Unit)? = null) {
    val c = PfTheme.colors
    PfCard(modifier) {
        Text(title, style = PfTheme.type.caption, color = c.textMuted)
        Spacer(Modifier.height(PfSpace.s1))
        Text(amountLabel, style = PfTheme.type.caption, color = c.textMuted)
        Text(amount, style = PfTheme.type.amountHero, color = c.text, maxLines = 1)
        Spacer(Modifier.height(PfSpace.s3))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PfSpace.s4)) {
            items.forEach { item ->
                Column(Modifier.weight(1f)) {
                    Text(item.label, style = PfTheme.type.hint, color = c.textMuted)
                    Text(item.value, style = PfTheme.type.bodyStrong, color = when (item.tone) { ValueTone.Positive -> c.positive; ValueTone.Muted -> c.textMuted; else -> c.text }, maxLines = 1)
                }
            }
        }
        if (footer != null) {
            Spacer(Modifier.height(PfSpace.s3))
            Divider()
            footer()
        }
    }
}
