package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Category icon on an `accent-soft` tile (40 dp in lists, 36 dp small). */
@Composable
fun PfIconTile(icon: String, modifier: Modifier = Modifier, small: Boolean = false) {
    val size = if (small) 36.dp else PfTheme.dimens.tile
    Box(
        modifier = modifier
            .size(size)
            .background(PfTheme.colors.accentSoft, RoundedCornerShape(if (small) PfTheme.dimens.radiusSm else PfTheme.dimens.radiusMd)),
        contentAlignment = Alignment.Center,
    ) {
        PfIcon(icon, contentDescription = null, size = PfTheme.dimens.iconMd, tint = PfTheme.colors.accent)
    }
}

/** Decorative 1 dp divider (`border`). */
@Composable
fun PfDivider(modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(modifier.fillMaxWidth().height(1.dp).background(if (strong) PfTheme.colors.borderStrong else PfTheme.colors.border))
}

enum class RowTone { DEFAULT, ACCENT }

/**
 * Settings / list row inside `PfCard(flush = true)`: accent icon, title, optional description, value on the
 * right and a chevron — or a switch. Min height `row` (56).
 */
@Composable
fun PfListRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: String? = null,
    description: String? = null,
    value: String? = null,
    valueLabel: String? = null,
    valueAccent: Boolean = false,
    tone: RowTone = RowTone.DEFAULT,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null && tone == RowTone.DEFAULT,
    switchChecked: Boolean? = null,
    onSwitch: ((Boolean) -> Unit)? = null,
    divider: Boolean = true,
    /** Custom trailing control (e.g. a delete icon button); replaces the chevron. */
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = PfTheme.colors
    val isSwitch = switchChecked != null
    val clickable = when {
        isSwitch && onSwitch != null -> Modifier.clickable(role = Role.Switch) { onSwitch(!switchChecked!!) }
        onClick != null -> Modifier.clickable(role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(clickable)
                .defaultMinSize(minHeight = PfTheme.dimens.row)
                .padding(horizontal = PfTheme.dimens.space4, vertical = PfTheme.dimens.space3)
                .semantics(mergeDescendants = true) {
                    if (valueLabel != null) contentDescription = listOfNotNull(title, description, valueLabel).joinToString(", ")
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                PfIcon(icon, contentDescription = null, size = PfTheme.dimens.iconMd, tint = c.accent)
                Spacer(Modifier.width(PfTheme.dimens.space3))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = PfTheme.type.bodyStrong, color = if (tone == RowTone.ACCENT) c.accent else c.text)
                if (description != null) Text(description, style = PfTheme.type.caption, color = c.textMuted)
            }
            if (value != null) {
                Spacer(Modifier.width(PfTheme.dimens.space2))
                Text(value, style = PfTheme.type.caption, color = if (valueAccent) c.accent else c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (trailing != null) {
                trailing()
            } else if (isSwitch) {
                Spacer(Modifier.width(PfTheme.dimens.space3))
                PfSwitch(checked = switchChecked!!, onCheckedChange = null)
            } else if (chevron) {
                Spacer(Modifier.width(PfTheme.dimens.space1))
                PfIcon(PfIcons.CHEVRON_RIGHT, contentDescription = null, size = PfTheme.dimens.iconMd, tint = c.textFaint)
            }
        }
        if (divider) PfDivider(Modifier.padding(start = if (icon != null) 48.dp else PfTheme.dimens.space4))
    }
}

enum class ValueTone { DEFAULT, POSITIVE, MUTED }

/** «label — value» row: operation details, income rows, assistant rows. `total` draws a strong top line. */
@Composable
fun PfDataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    tone: ValueTone = ValueTone.DEFAULT,
    valueLabel: String? = null,
    total: Boolean = false,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
) {
    val c = PfTheme.colors
    val valueColor = when (tone) { ValueTone.POSITIVE -> c.positive; ValueTone.MUTED -> c.textMuted; else -> c.text }
    Column(modifier) {
        if (total) PfDivider(strong = true)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick).defaultMinSize(minHeight = PfTheme.dimens.touch) else Modifier)
                .padding(vertical = PfTheme.dimens.space2)
                .semantics(mergeDescendants = true) {
                    if (valueLabel != null) contentDescription = "$label, $valueLabel"
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = PfTheme.type.body, color = c.text)
                if (sublabel != null) Text(sublabel, style = PfTheme.type.hint, color = c.textMuted)
            }
            Text(
                value,
                style = if (total) PfTheme.type.bodyStrong.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) else PfTheme.type.bodyStrong,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (onClick != null) {
                Spacer(Modifier.width(PfTheme.dimens.space1))
                PfIcon(PfIcons.CHEVRON_RIGHT, contentDescription = null, size = PfTheme.dimens.iconMd, tint = c.textFaint)
            }
        }
        if (divider && !total) PfDivider()
    }
}

/** Day group of operations: `overline` date header in `text-faint` and the rows. */
@Composable
fun PfTransactionGroup(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Text(
            label.uppercase(),
            style = PfTheme.type.overline,
            color = PfTheme.colors.textFaint,
            modifier = Modifier.padding(top = PfTheme.dimens.space4, bottom = PfTheme.dimens.space2).semantics { heading() },
        )
        content()
    }
}

/**
 * Operation row: category tile, title, subtitle, formatted amount and a note under it. Income amounts are
 * `positive` (and always carry a «+»); expenses stay `text`.
 */
@Composable
fun PfTransactionRow(
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
            .clip(RoundedCornerShape(PfTheme.dimens.radiusMd))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .defaultMinSize(minHeight = 64.dp)
            .padding(vertical = PfTheme.dimens.space3)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PfIconTile(icon)
        Spacer(Modifier.width(PfTheme.dimens.space3))
        Column(Modifier.weight(1f)) {
            Text(title, style = PfTheme.type.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = PfTheme.type.caption, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(PfTheme.dimens.space3))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                amount,
                style = PfTheme.type.bodyStrong,
                color = when { income -> c.positive; muted -> c.textMuted; else -> c.text },
                maxLines = 1,
            )
            if (note != null) Text(note, style = PfTheme.type.micro, color = c.textMuted, maxLines = 1)
        }
    }
}

/** Single-choice row for pickers (category, theme, month). `selected` shows a check in `accent`. */
@Composable
fun PfOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    description: String? = null,
) {
    val c = PfTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .defaultMinSize(minHeight = PfTheme.dimens.row)
            .padding(horizontal = PfTheme.dimens.space4, vertical = PfTheme.dimens.space3)
            .semantics(mergeDescendants = true) { this.contentDescription = listOfNotNull(title, description).joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            PfIconTile(icon, small = true)
            Spacer(Modifier.width(PfTheme.dimens.space3))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = PfTheme.type.body, color = c.text)
            if (description != null) Text(description, style = PfTheme.type.hint, color = c.textMuted)
        }
        if (selected) PfIcon(PfIcons.CHECK, contentDescription = "выбрано", size = PfTheme.dimens.iconMd, tint = c.accent)
    }
}

/** Group header inside an option list: `overline` uppercase, `text-faint`. */
@Composable
fun PfOptionGroupTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = PfTheme.type.overline,
        color = PfTheme.colors.textFaint,
        modifier = modifier.padding(start = PfTheme.dimens.space4, top = PfTheme.dimens.space4, bottom = PfTheme.dimens.space1),
    )
}

/** Numbered steps (upload instructions): indigo number on `accent-soft`, text beside. */
@Composable
fun PfOrderedSteps(steps: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(PfTheme.dimens.space3)) {
        steps.forEachIndexed { i, step ->
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier.size(28.dp).background(PfTheme.colors.accentSoft, RoundedCornerShape(PfTheme.dimens.radiusFull)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", style = PfTheme.type.micro, color = PfTheme.colors.accent)
                }
                Spacer(Modifier.width(PfTheme.dimens.space3))
                Text(step, style = PfTheme.type.body, color = PfTheme.colors.text, modifier = Modifier.weight(1f).padding(top = 4.dp))
            }
        }
    }
}
