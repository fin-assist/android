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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
 * @param maxLines allow a long label to wrap at larger system font sizes; such buttons keep `control` as a
 * minimum height and grow to fit the label.
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
    maxLines: Int = 1,
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
        // Secondary and ghost buttons carry accent text (`.pf-btn-secondary`, `.pf-btn-ghost`).
        else -> c.accent
    }
    val label = if (busy) busyText ?: text else text
    Box(
        modifier = modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .then(if (maxLines > 1) Modifier.heightIn(min = PfTheme.dimens.control) else Modifier.height(PfTheme.dimens.control))
            .clip(shape)
            .background(background, shape)
            .then(if (variant == ButtonVariant.SECONDARY && enabled) Modifier.border(1.dp, c.border, shape) else Modifier)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .semantics { if (busy) contentDescription = label }
            .padding(horizontal = PfTheme.dimens.space4)
            .then(if (maxLines > 1) Modifier.padding(vertical = PfTheme.dimens.space2) else Modifier),
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
                // `.pf-btn` 15/20 600; `.pf-btn-ghost` 14.
                val style = if (variant == ButtonVariant.GHOST) PfTheme.type.body.copy(fontWeight = FontWeight.SemiBold) else PfTheme.type.bodyStrong
                Text(label, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
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
    iconSize: Dp = PfTheme.dimens.iconLg,
) {
    Box(
        modifier = modifier
            .size(PfTheme.dimens.touch)
            .clip(RoundedCornerShape(PfTheme.dimens.radiusMd))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PfIcon(icon, contentDescription = contentDescription, size = iconSize, tint = if (enabled) tint else PfTheme.colors.borderStrong)
    }
}

/**
 * Link — a transition, not an action. Accent, `600`, no underline.
 * - standalone (`.pf-link-standalone`): `14/20`, at least 48 dp tall, no side padding;
 * - [inline] (`.pf-link-inline`): continues a sentence in [inlineStyle] (the surrounding text size) and keeps its
 *   layout size; Compose widens the touch area to 48 dp around it.
 */
@Composable
fun PfLink(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
    inlineStyle: TextStyle = PfTheme.type.caption,
) {
    val c = PfTheme.colors
    val style = (if (inline) inlineStyle else PfTheme.type.body).copy(fontWeight = FontWeight.SemiBold)
    Box(
        modifier = modifier
            .then(if (inline) Modifier else Modifier.defaultMinSize(minHeight = PfTheme.dimens.touch))
            .clip(RoundedCornerShape(PfTheme.dimens.radiusSm))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = style, color = c.accent)
    }
}

/** A piece of [PfLinkedText]: plain text or an inline link (`Pf.Link inline`). */
sealed interface TextPart {
    val text: String

    data class Plain(override val text: String) : TextPart

    /** [tag] names the link for accessibility and tests; [onClick] runs on tap. */
    data class Link(override val text: String, val tag: String, val onClick: () -> Unit) : TextPart
}

/**
 * A paragraph with inline links that continue the text, as the mockups' `Pf.Link inline` inside a sentence:
 * links are accent and semibold, the rest uses [style] and [color]. Each link is a separate accessibility action.
 */
@Composable
fun PfLinkedText(
    parts: List<TextPart>,
    modifier: Modifier = Modifier,
    style: TextStyle = PfTheme.type.caption,
    color: Color = PfTheme.colors.textMuted,
    textAlign: TextAlign? = null,
) {
    val linkStyle = TextLinkStyles(SpanStyle(color = PfTheme.colors.accent, fontWeight = FontWeight.SemiBold))
    val text = buildAnnotatedString {
        parts.forEach { part ->
            when (part) {
                is TextPart.Plain -> append(part.text)
                is TextPart.Link -> withLink(LinkAnnotation.Clickable(part.tag, linkStyle) { part.onClick() }) { append(part.text) }
            }
        }
    }
    Text(text, modifier = modifier, style = style, color = color, textAlign = textAlign)
}
