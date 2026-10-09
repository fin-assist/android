package ru.finassist.pf.core.designsystem.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Text field with an always-visible label, 52 dp, `border-strong` frame, `accent` in focus, error in `warning-text`. */
@Composable
fun PfTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    hint: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    singleLine: Boolean = true,
    enabled: Boolean = true,
    /** Tag of the editable field itself (not the label block), so UI tests can tap and type into it. */
    testTag: String? = null,
) {
    FieldFrame(label, value.isEmpty(), placeholder, hint, error, modifier, testTag) { interaction, fieldModifier, decoration ->
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            interactionSource = interaction,
            textStyle = PfTheme.type.lead.copy(color = PfTheme.colors.text, fontSize = 16.sp),
            cursorBrush = SolidColor(PfTheme.colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onImeAction() }),
            modifier = fieldModifier,
            decorationBox = decoration,
        )
    }
}

/**
 * [PfTextField] over [TextFieldValue] with a [visualTransformation] — for masked input (phone), where the
 * caller owns the cursor and the shown text differs from the stored one.
 */
@Composable
fun PfTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    hint: String? = null,
    error: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    enabled: Boolean = true,
    testTag: String? = null,
) {
    FieldFrame(label, value.text.isEmpty(), placeholder, hint, error, modifier, testTag) { interaction, fieldModifier, decoration ->
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            interactionSource = interaction,
            visualTransformation = visualTransformation,
            textStyle = PfTheme.type.lead.copy(color = PfTheme.colors.text, fontSize = 16.sp),
            cursorBrush = SolidColor(PfTheme.colors.accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onImeAction() }),
            modifier = fieldModifier,
            decorationBox = decoration,
        )
    }
}

/** Label, frame, placeholder and hint/error shared by both [PfTextField] overloads. */
@Composable
private fun FieldFrame(
    label: String,
    isEmpty: Boolean,
    placeholder: String?,
    hint: String?,
    error: String?,
    modifier: Modifier,
    testTag: String?,
    field: @Composable (MutableInteractionSource, Modifier, @Composable (@Composable () -> Unit) -> Unit) -> Unit,
) {
    val c = PfTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor = when {
        error != null -> c.warningText
        focused -> c.accent
        else -> c.borderStrong
    }
    Column(modifier) {
        Text(label, style = PfTheme.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.textMuted)
        Spacer(Modifier.height(PfTheme.dimens.space1))
        field(
            interaction,
            Modifier
                .fillMaxWidth()
                .height(PfTheme.dimens.field)
                .background(c.surface, RoundedCornerShape(PfTheme.dimens.radiusMd))
                .border(if (error != null || focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(PfTheme.dimens.radiusMd))
                .padding(horizontal = PfTheme.dimens.space4)
                .semantics { if (error != null) this.error(error) }
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        ) { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (isEmpty && placeholder != null) {
                    Text(placeholder, style = PfTheme.type.lead, color = c.textMuted, maxLines = 1)
                }
                inner()
            }
        }
        val below = error ?: hint
        if (below != null) {
            Spacer(Modifier.height(PfTheme.dimens.space1))
            Text(below, style = PfTheme.type.hint, color = if (error != null) c.warningText else c.textMuted)
        }
    }
}

/** Search field 48 dp, `radius-full`, search icon and a clear button. */
@Composable
fun PfSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Поиск",
    onSearch: () -> Unit = {},
    testTag: String? = null,
) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(PfTheme.dimens.radiusFull)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PfTheme.dimens.control)
            .background(c.surface, shape)
            .border(1.dp, c.borderStrong, shape)
            .padding(start = PfTheme.dimens.space4, end = PfTheme.dimens.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PfIcon(PfIcons.SEARCH, contentDescription = null, size = PfTheme.dimens.iconMd, tint = c.textMuted)
        Spacer(Modifier.width(PfTheme.dimens.space2))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = PfTheme.type.lead.copy(color = c.text, fontSize = 16.sp),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.weight(1f).semantics { contentDescription = placeholder }.then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = PfTheme.type.lead, color = c.textMuted, maxLines = 1)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            PfIconButton(PfIcons.X, contentDescription = "Очистить", onClick = { onValueChange("") }, tint = c.textMuted)
        }
    }
}

/**
 * Chip: filter (36 dp, `border-strong`; selected — `accent-soft` + `accent` frame), transition (40 dp, accent
 * text and chevron) or action («Сбросить»). The touch target is 48 dp regardless of the visible height.
 */
@Composable
fun PfChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    dropdown: Boolean = false,
    transition: Boolean = false,
    enabled: Boolean = true,
) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(PfTheme.dimens.radiusFull)
    val height = if (transition) 40.dp else 36.dp
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = PfTheme.dimens.touch)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (selected) toggleableState = ToggleableState.On },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(height)
                .background(if (selected) c.accentSoft else c.surface, shape)
                .border(1.dp, if (selected) c.accent else c.borderStrong, shape)
                .padding(horizontal = PfTheme.dimens.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = PfTheme.type.caption, color = if (transition || selected) c.accent else c.text, maxLines = 1)
            if (dropdown || transition) {
                Spacer(Modifier.width(PfTheme.dimens.space1))
                PfIcon(
                    if (transition) PfIcons.CHEVRON_RIGHT else PfIcons.CHEVRON_DOWN,
                    contentDescription = null,
                    size = 16.dp,
                    tint = if (transition || selected) c.accent else c.textMuted,
                )
            }
        }
    }
}

/** Horizontal scrolling row of chips with `space-2` gaps; the first chip starts at the screen margin. */
@Composable
fun PfChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** Switch: `accent` track when on, `border-strong` when off, `knob` handle. */
@Composable
fun PfSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = PfTheme.colors
    val track by animateColorAsState(if (checked) c.accent else c.borderStrong, label = "track")
    val offset by animateDpAsState(if (checked) 20.dp else 2.dp, label = "knob")
    Box(
        modifier = modifier
            .size(width = 44.dp, height = 26.dp)
            .background(track, RoundedCornerShape(PfTheme.dimens.radiusFull))
            .then(
                if (onCheckedChange != null) Modifier.clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) } else Modifier,
            )
            .semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off },
    ) {
        Box(
            Modifier
                .offset(x = offset, y = 2.dp)
                .size(22.dp)
                .background(c.knob, RoundedCornerShape(PfTheme.dimens.radiusFull)),
        )
    }
}

/** Checkbox with a label on the right; unchecked by default for consents (152-ФЗ). Error text goes below. */
@Composable
fun PfCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    val c = PfTheme.colors
    Column(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PfTheme.dimens.radiusSm))
                .clickable(role = Role.Checkbox) { onCheckedChange(!checked) }
                .defaultMinSize(minHeight = PfTheme.dimens.touch)
                .padding(vertical = PfTheme.dimens.space2)
                .semantics(mergeDescendants = true) {
                    toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                    if (error != null) this.error(error)
                },
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(22.dp)
                    .background(if (checked) c.accent else c.surface, RoundedCornerShape(6.dp))
                    .border(if (checked) 0.dp else 2.dp, if (error != null) c.warningText else c.borderStrong, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) PfIcon(PfIcons.CHECK, contentDescription = null, size = 14.dp, tint = c.onAccent)
            }
            Spacer(Modifier.width(PfTheme.dimens.space3))
            Text(label, style = PfTheme.type.body, color = c.text, modifier = Modifier.weight(1f))
        }
        if (error != null) Text(error, style = PfTheme.type.hint, color = c.warningText, modifier = Modifier.padding(start = 34.dp))
    }
}

/** Segmented control 48 dp: segments 40 dp with `radius-sm`, selected — `accent-soft` and `accent` text. */
@Composable
fun PfSegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PfTheme.colors
    val outer = RoundedCornerShape(PfTheme.dimens.radiusMd)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(PfTheme.dimens.control)
            .background(c.surface, outer)
            .border(1.dp, c.border, outer)
            .padding(4.dp),
    ) {
        options.forEachIndexed { i, option ->
            val isSelected = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(PfTheme.dimens.radiusSm))
                    .background(if (isSelected) c.accentSoft else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .semantics { this.contentDescription = option; if (isSelected) toggleableState = ToggleableState.On }
                    .testTag(PfTestTags.segment(i)),
                contentAlignment = Alignment.Center,
            ) {
                Text(option, style = PfTheme.type.bodyStrong, color = if (isSelected) c.accent else c.textMuted, maxLines = 1)
            }
        }
    }
}
