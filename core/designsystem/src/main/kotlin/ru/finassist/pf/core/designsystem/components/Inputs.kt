package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.theme.PfRadius
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Text field with an always-visible label; `error` replaces the hint, border `warning-text`, announced. */
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
    onImeAction: (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
) {
    val c = PfTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(PfRadius.md)
    val borderColor = when { error != null -> c.warningText; focused -> c.accent; else -> c.borderStrong }
    Column(modifier.fillMaxWidth()) {
        Text(label, style = PfTheme.type.captionStrong, color = c.textMuted)
        Spacer(Modifier.height(PfSpace.s1))
        Box(
            Modifier
                .fillMaxWidth()
                .height(PfSize.field)
                .background(c.surface, shape)
                .border1(borderColor, shape, if (error != null || focused) 2.dp else 1.dp)
                .padding(horizontal = PfSpace.s4),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = PfTheme.type.input.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = { onImeAction?.invoke() }),
                visualTransformation = visualTransformation,
                modifier = Modifier.fillMaxWidth().semantics { if (error != null) error(error) },
            )
            if (value.isEmpty() && placeholder != null) Text(placeholder, style = PfTheme.type.input, color = c.textMuted)
        }
        val under = error ?: hint
        if (under != null) {
            Spacer(Modifier.height(PfSpace.s1))
            Text(
                under,
                style = PfTheme.type.hint,
                color = if (error != null) c.warningText else c.textMuted,
                modifier = Modifier.semantics { if (error != null) liveRegion = LiveRegionMode.Assertive },
            )
        }
    }
}

/** Search field 48 with magnifier and clear button; `radius-full`. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onSearch: (() -> Unit)? = null, autoFocus: Boolean = false) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(PfRadius.full)
    val focusRequester = remember { FocusRequester() }
    Row(
        modifier
            .fillMaxWidth()
            .height(PfSize.control)
            .background(c.surface, shape)
            .border1(c.borderStrong, shape)
            .padding(horizontal = PfSpace.s3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PfIcon("search", size = PfSize.iconMd, tint = c.textMuted)
        Spacer(Modifier.width(PfSpace.s2))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = PfTheme.type.input.copy(color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch?.invoke() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            if (value.isEmpty()) Text(placeholder, style = PfTheme.type.input, color = c.textMuted, maxLines = 1)
        }
        if (value.isNotEmpty()) {
            PfIconButton("x", contentDescription = "Очистить", onClick = { onValueChange("") }, tint = c.textMuted, modifier = Modifier.offset(x = PfSpace.s2))
        }
    }
    if (autoFocus) LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** Filter chip (36) or link chip (40); tap target 48 through padding. */
@Composable
fun PfChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    dropdown: Boolean = false,
    link: Boolean = false,
    action: Boolean = false,
) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(PfRadius.full)
    Box(
        modifier = modifier
            .heightIn(min = PfSize.touch)
            .clickable(role = Role.Button, indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick)
            .semantics { if (!link && !action) this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(if (link) 40.dp else 36.dp)
                .clip(shape)
                .background(if (selected) c.accentSoft else c.surface, shape)
                .border1(if (selected) c.accent else c.borderStrong, shape)
                .padding(horizontal = PfSpace.s3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = PfTheme.type.caption, color = if (link || selected) c.accent else c.text, maxLines = 1)
            if (dropdown || link) {
                Spacer(Modifier.width(PfSpace.s1))
                PfIcon(if (link) "chevron-right" else "chevron-down", size = 16.dp, tint = if (link || selected) c.accent else c.textMuted)
            }
        }
    }
}

/** Horizontal row of chips with 8 dp gaps; scrolls when it overflows. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(PfSpace.s2), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

/** Segmented control 48: selected segment `accent-soft` + `accent`. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val c = PfTheme.colors
    val outer = RoundedCornerShape(PfRadius.md)
    val inner = RoundedCornerShape(PfRadius.sm)
    Row(
        modifier
            .fillMaxWidth()
            .height(PfSize.control)
            .background(c.surface, outer)
            .border1(c.border, outer)
            .padding(4.dp)
            .semantics { if (label != null) contentDescription = label },
    ) {
        options.forEachIndexed { i, option ->
            val isSelected = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(inner)
                    .background(if (isSelected) c.accentSoft else c.surface, inner)
                    .clickable(role = Role.RadioButton) { onSelect(i) }
                    .semantics { this.selected = isSelected },
                contentAlignment = Alignment.Center,
            ) {
                Text(option, style = PfTheme.type.bodyStrong, color = if (isSelected) c.accent else c.textMuted)
            }
        }
    }
}

/** Switch: track `accent` / `border-strong`, knob `knob`. */
@Composable
fun PfSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val c = PfTheme.colors
    Box(
        modifier
            .width(44.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(if (checked) c.accent else c.borderStrong)
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .semantics { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(Modifier.size(20.dp).background(c.knob, CircleShape))
    }
}

/** Checkbox with a label; the whole row toggles. */
@Composable
fun PfCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier, error: Boolean = false) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(6.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = PfSize.touch)
            .clickable(role = Role.Checkbox) { onCheckedChange(!checked) }
            .semantics(mergeDescendants = true) { toggleableState = if (checked) ToggleableState.On else ToggleableState.Off },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .background(if (checked) c.accent else c.surface, shape)
                .border1(if (error) c.warningText else if (checked) c.accent else c.borderStrong, shape, 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) PfIcon("check", size = 16.dp, tint = c.onAccent)
        }
        Spacer(Modifier.width(PfSpace.s3))
        Text(label, style = PfTheme.type.body, color = c.text)
    }
}
