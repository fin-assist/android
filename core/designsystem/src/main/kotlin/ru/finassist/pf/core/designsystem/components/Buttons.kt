package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.theme.PfRadius
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

enum class ButtonVariant { Primary, Secondary, Ghost, Danger }

/**
 * Button (README: «Button»). `busy` swaps the label for [busyText], keeps focus and ignores taps — no spinner.
 */
@Composable
fun PfButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Secondary,
    icon: String? = null,
    block: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String? = null,
) {
    val c = PfTheme.colors
    val bg = when {
        !enabled -> c.border
        variant == ButtonVariant.Primary -> c.accent
        variant == ButtonVariant.Danger -> c.warningText
        variant == ButtonVariant.Secondary -> c.surface
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> c.textMuted
        variant == ButtonVariant.Primary -> c.onAccent
        variant == ButtonVariant.Danger -> c.onWarning
        variant == ButtonVariant.Secondary -> if (busy) c.textMuted else c.accent
        else -> if (busy) c.textMuted else c.accent
    }
    val shape = RoundedCornerShape(PfRadius.md)
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .height(PfSize.control)
            .clip(shape)
            .background(bg, shape)
            .then(if (variant == ButtonVariant.Secondary && enabled) Modifier.border1(c.border, shape) else Modifier)
            .clickable(enabled = enabled && !busy, interactionSource = interaction, indication = ripple(), role = Role.Button) { onClick() }
            .semantics { if (busy) disabled() }
            .padding(horizontal = PfSpace.s5),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null && !busy) {
            PfIcon(icon, size = PfSize.iconMd, tint = fg)
            Box(Modifier.size(PfSpace.s2))
        }
        Text(
            text = if (busy) (busyText ?: text) else text,
            style = PfTheme.type.bodyStrong,
            color = fg,
            maxLines = 1,
            modifier = Modifier.alpha(if (busy && (variant == ButtonVariant.Primary || variant == ButtonVariant.Danger)) 0.85f else 1f),
        )
    }
}

/** Icon-only button 48×48 (header actions, «Назад», «Закрыть»). */
@Composable
fun PfIconButton(icon: String, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = PfTheme.colors.text) {
    Box(
        modifier = modifier
            .size(PfSize.touch)
            .clip(RoundedCornerShape(PfRadius.md))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PfIcon(icon, size = PfSize.iconLg, tint = tint, contentDescription = contentDescription)
    }
}

/**
 * Link (README «Кнопки и ссылки»): accent, 600, no underline. Standalone links keep a 48 dp tap height;
 * inline links extend the invisible tap area instead of changing their look.
 */
@Composable
fun PfLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, inline: Boolean = false) {
    Box(
        modifier = modifier
            .then(if (inline) Modifier.defaultMinSize(minHeight = 20.dp) else Modifier.heightIn(min = PfSize.touch))
            .clickable(role = Role.Button, indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = PfTheme.type.bodyStrong, color = PfTheme.colors.accent)
    }
}

internal fun Modifier.border1(color: Color, shape: Shape, width: Dp = 1.dp): Modifier = this.border(width, color, shape)
