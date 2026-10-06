package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.theme.PfTheme

enum class ButtonVariant { PRIMARY, SECONDARY, GHOST, DANGER }

/**
 * Button of height `control` (48). `primary` — at most one per screen; `secondary` — other actions; `ghost` —
 * text action under the main button; `danger` — only in a confirmation dialog.
 *
 * @param busy the action is running: the label is replaced by [busyText], taps are ignored, the button is not
 * disabled so TalkBack focus stays on it; no spinner (DS: it cannot be stopped by "Remove animations").
 */
@Composable
fun PfButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.SECONDARY,
    icon: String? = null,
    block: Boolean = false,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String? = null,
) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(PfTheme.dimens.radiusMd)
    val background = when {
        !enabled -> c.border
        variant == ButtonVariant.PRIMARY -> c.accent
        variant == ButtonVariant.DANGER -> c.warningText
        variant == ButtonVariant.SECONDARY -> c.surface
        else -> Color.Transparent
    }
    val content = when {
        !enabled -> c.textMuted
        variant == ButtonVariant.PRIMARY -> c.onAccent
        variant == ButtonVariant.DANGER -> c.onWarning
        busy -> c.textMuted
        variant == ButtonVariant.GHOST -> c.accent
        else -> c.text
    }
    val label = if (busy) busyText ?: text else text
    Box(
        modifier = modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .height(PfTheme.dimens.control)
            .clip(shape)
            .background(background, shape)
            .then(if (variant == ButtonVariant.SECONDARY && enabled) Modifier.border(1.dp, c.borderStrong, shape) else Modifier)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .semantics { if (busy) contentDescription = label }
            .padding(horizontal = PfTheme.dimens.space5),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.alpha(if (busy && (variant == ButtonVariant.PRIMARY || variant == ButtonVariant.DANGER)) 0.85f else 1f),
            ) {
                if (icon != null && !busy) {
                    PfIcon(icon, contentDescription = null, size = PfTheme.dimens.iconMd)
                    Spacer(Modifier.width(PfTheme.dimens.space2))
                }
                Text(label, style = PfTheme.type.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Icon-only button with a 48 dp touch target (back, close, search in headers). */
@Composable
fun PfIconButton(
    icon: String,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = PfTheme.colors.text,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(PfTheme.dimens.touch)
            .clip(RoundedCornerShape(PfTheme.dimens.radiusMd))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PfIcon(icon, contentDescription = contentDescription, tint = if (enabled) tint else PfTheme.colors.borderStrong)
    }
}

/**
 * Link — a transition, not an action. Accent, semibold, no underline; the touch target is at least 48 dp tall
 * (inline links widen their hit area without changing their look).
 */
@Composable
fun PfLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
) {
    val c = PfTheme.colors
    Box(
        modifier = modifier
            .then(if (inline) Modifier else Modifier.defaultMinSize(minHeight = PfTheme.dimens.touch))
            .clip(RoundedCornerShape(PfTheme.dimens.radiusSm))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (inline) 0.dp else PfTheme.dimens.space1),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PfTheme.type.bodyStrong, color = c.accent)
    }
}
