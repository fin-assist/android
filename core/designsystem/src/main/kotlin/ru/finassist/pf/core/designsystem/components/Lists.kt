package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
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
 * Settings / list row inside `PfCard(flush = true)` (mockup `.pf-lrow`): accent icon, title (`15/20` regular,
 * accent rows `600`), optional description (`hint`), value on the right (at most half the row, wraps) and a
 * chevron — or a switch. Min height `row` (56), 8 dp vertical padding, 12 dp between parts. The divider runs
 * under the whole row inside the card's 16 dp side padding; the last row of a card passes `divider = false`.
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
    val d = PfTheme.dimens
    val isSwitch = switchChecked != null
    val clickable = when {
        isSwitch && onSwitch != null -> Modifier.clickable(role = Role.Switch) { onSwitch(!switchChecked!!) }
        onClick != null -> Modifier.clickable(role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // `.pf-lrow-value { max-width: 50% }` of the row's content box (the card pads it by 16 on each side).
            val valueMaxWidth = (maxWidth - d.space4 * 2) / 2
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(clickable)
                    .defaultMinSize(minHeight = d.row)
                    .padding(horizontal = d.space4, vertical = d.space2)
                    .semantics(mergeDescendants = true) {
                        if (valueLabel != null) contentDescription = listOfNotNull(title, description, valueLabel).joinToString(", ")
                    },
                horizontalArrangement = Arrangement.spacedBy(d.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) PfIcon(icon, contentDescription = null, size = d.iconMd, tint = c.accent)
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = if (tone == RowTone.ACCENT) PfTheme.type.bodyStrong else PfTheme.type.lead,
                        color = if (tone == RowTone.ACCENT) c.accent else c.text,
                    )
                    if (description != null) Text(description, style = PfTheme.type.hint, color = c.textMuted)
                }
                if (value != null) {
                    Text(
                        value,
                        style = if (valueAccent) PfTheme.type.body.copy(fontWeight = FontWeight.SemiBold) else PfTheme.type.caption,
                        color = if (valueAccent) c.accent else c.textMuted,
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(max = valueMaxWidth),
                    )
                }
                when {
                    trailing != null -> trailing()
                    isSwitch -> PfSwitch(checked = switchChecked!!, onCheckedChange = null)
                    chevron -> PfIcon(PfIcons.CHEVRON_RIGHT, contentDescription = null, size = d.iconMd, tint = c.textFaint)
                }
            }
        }
        if (divider) PfDivider(Modifier.padding(horizontal = d.space4))
    }
}

enum class ValueTone { DEFAULT, POSITIVE, MUTED }

/**
 * «label — value» row (mockup `.pf-drow`): operation details, income rows, assistant rows. Label `body`, value
 * `14/20 600`, 12 dp apart, both aligned to the top. A text value takes at most 60 % of the row and wraps, so
 * the label is always readable; an [amount] value never wraps and takes what it needs. A clickable row is 48 dp
 * tall and centred. `total` draws a strong top line and a bold value.
 */
@Composable
fun PfDataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    tone: ValueTone = ValueTone.DEFAULT,
    valueLabel: String? = null,
    total: Boolean = false,
    amount: Boolean = false,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    val valueColor = when (tone) { ValueTone.POSITIVE -> c.positive; ValueTone.MUTED -> c.textMuted; else -> c.text }
    val valueStyle = PfTheme.type.body.copy(fontWeight = if (total) FontWeight.Bold else FontWeight.SemiBold, fontFeatureSettings = "tnum")
    Column(modifier) {
        if (total) PfDivider(strong = true)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val valueMaxWidth = maxWidth * 0.6f
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick).defaultMinSize(minHeight = d.touch) else Modifier)
                    .padding(vertical = d.space2)
                    .semantics(mergeDescendants = true) {
                        if (valueLabel != null) contentDescription = "$label, $valueLabel"
                    },
                horizontalArrangement = Arrangement.spacedBy(d.space3),
                verticalAlignment = if (onClick != null) Alignment.CenterVertically else Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = if (total) PfTheme.type.body.copy(fontWeight = FontWeight.Bold) else PfTheme.type.body, color = c.text)
                    if (sublabel != null) Text(sublabel, style = PfTheme.type.hint, color = c.textMuted)
                }
                if (amount) {
                    Text(value, style = valueStyle, color = valueColor, maxLines = 1, softWrap = false, textAlign = TextAlign.End)
                } else {
                    Text(value, style = valueStyle, color = valueColor, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = valueMaxWidth))
                }
                if (onClick != null) PfIcon(PfIcons.CHEVRON_RIGHT, contentDescription = null, size = d.iconMd, tint = c.textFaint)
            }
        }
        if (divider && !total) PfDivider()
    }
}

/** Day group of operations: `overline` date header in `text-faint` and the rows. */
@Composable
fun PfTransactionGroup(label: String, modifier: Modifier = Modifier, first: Boolean = false, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Text(
            label.uppercase(),
            style = PfTheme.type.overline,
            color = PfTheme.colors.textFaint,
            // `.pf-tgroup:first-child .pf-tgroup-title { padding-top: 0 }`
            modifier = Modifier.padding(top = if (first) 0.dp else PfTheme.dimens.space4).semantics { heading() },
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
    divider: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        TransactionRowBody(icon, title, subtitle, amount, income, muted, note, onClick)
        if (divider) PfDivider()
    }
}

@Composable
private fun TransactionRowBody(
    icon: String,
    title: String,
    subtitle: String,
    amount: String,
    income: Boolean,
    muted: Boolean,
    note: String?,
    onClick: (() -> Unit)?,
) {
    val c = PfTheme.colors
    Row(
        modifier = Modifier
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

/**
 * Single-choice row for pickers (mockup `.pf-opt`): optional icon tile, title `body` (`600` when selected),
 * description `caption`, a check in a 24 dp box. No side padding — the row sits on the sheet's 20 dp margin.
 */
@Composable
fun PfOptionRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: String? = null,
    description: String? = null,
    /** `.pf-opt` has a bottom border except the last row of its group: the caller says which one is last. */
    divider: Boolean = false,
) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.RadioButton, onClick = onClick)
                .defaultMinSize(minHeight = d.row)
                .padding(vertical = d.space2)
                .semantics(mergeDescendants = true) {
                    this.contentDescription = listOfNotNull(title, description).joinToString(", ")
                    this.selected = selected
                },
            horizontalArrangement = Arrangement.spacedBy(d.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) PfIconTile(icon, small = true)
            Column(Modifier.weight(1f)) {
                Text(title, style = if (selected) PfTheme.type.body.copy(fontWeight = FontWeight.SemiBold) else PfTheme.type.body, color = c.text)
                if (description != null) Text(description, style = PfTheme.type.caption, color = c.textMuted)
            }
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (selected) PfIcon(PfIcons.CHECK, contentDescription = null, size = d.iconMd, tint = c.accent)
            }
        }
        if (divider) PfDivider()
    }
}

/**
 * Group header inside an option list (mockup `.pf-optgroup-title`): `overline` uppercase, `text-faint`, 16 dp
 * above (8 dp for the [first] group) and 4 dp below. No side padding, like the rows.
 */
@Composable
fun PfOptionGroupTitle(text: String, modifier: Modifier = Modifier, first: Boolean = false) {
    Text(
        text.uppercase(),
        style = PfTheme.type.overline,
        color = PfTheme.colors.textFaint,
        modifier = modifier
            .padding(top = if (first) PfTheme.dimens.space2 else PfTheme.dimens.space4, bottom = PfTheme.dimens.space1)
            .semantics { heading() },
    )
}

/** Numbered steps (upload instructions): indigo number on `accent-soft`, text beside. */
@Composable
fun PfOrderedSteps(steps: List<String>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PfTheme.dimens.space3)) {
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
