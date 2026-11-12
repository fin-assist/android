package ru.finassist.pf.core.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** A compact anchor leaves room for the card title; Compose expands the clickable touch target. */
@Composable
fun BlockHelpPopupButton(text: String, title: String, tag: String, initiallyOpen: Boolean = false) {
    var shown by remember(text) { mutableStateOf(initiallyOpen) }
    Box {
        Box(
            Modifier.size(24.dp).testTag("$tag.help").clickable(role = Role.Button) { shown = true },
            contentAlignment = Alignment.Center,
        ) {
            PfIcon(PfIcons.INFO, contentDescription = "Пояснение: $title", size = 20.dp, tint = PfTheme.colors.text)
        }
        if (shown) HelpPopup(text, "$tag.help.popup") { shown = false }
    }
}

@Composable
private fun HelpPopup(text: String, tag: String, onDismiss: () -> Unit) {
    val d = PfTheme.dimens
    val c = PfTheme.colors
    val fill = if (c.isDark) lerp(c.surface, c.text, 0.15f) else c.infoSoft
    val shape = RoundedCornerShape(d.radiusMd)
    val arrow = d.space2
    val margin = with(LocalDensity.current) { d.space3.roundToPx() }
    val config = LocalConfiguration.current
    var arrowX by remember { mutableStateOf(0f) }
    var aboveAnchor by remember { mutableStateOf(true) }
    val position = remember(margin) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val anchor = anchorBounds
                val window = windowSize
                val popup = popupContentSize
                val above = anchor.top - margin
                val below = window.height - anchor.bottom - margin
                val aboveFits = popup.height <= above
                aboveAnchor = aboveFits || above >= below
                val desiredX = if (layoutDirection == LayoutDirection.Ltr) {
                    anchor.right + margin - popup.width
                } else {
                    anchor.left - margin
                }
                val x = desiredX.coerceIn(margin, (window.width - popup.width - margin).coerceAtLeast(margin))
                val desiredY = if (aboveAnchor) anchor.top - popup.height else anchor.bottom
                val y = desiredY.coerceIn(margin, (window.height - popup.height - margin).coerceAtLeast(margin))
                arrowX = (anchor.center.x - x).toFloat()
                return IntOffset(x, y)
            }
        }
    }
    Popup(position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier
                .widthIn(max = minOf(280.dp, (config.screenWidthDp.dp - d.space3 * 2).coerceAtLeast(1.dp)))
                .drawWithContent {
                    drawContent()
                    val depth = arrow.toPx()
                    val x = arrowX.coerceIn(depth, (size.width - depth).coerceAtLeast(depth))
                    val base = if (aboveAnchor) size.height - depth - 1f else depth + 1f
                    val tip = if (aboveAnchor) size.height else 0f
                    drawPath(Path().apply {
                        moveTo(x - depth, base)
                        lineTo(x, tip)
                        lineTo(x + depth, base)
                        close()
                    }, fill)
                }
                .padding(vertical = arrow),
        ) {
            Box(
                Modifier.testTag(tag)
                    .heightIn(max = (config.screenHeightDp.dp / 3).coerceAtLeast(1.dp))
                    .clip(shape).background(fill).border(1.dp, c.borderStrong, shape)
                    .verticalScroll(rememberScrollState()).padding(horizontal = d.space3, vertical = d.space2),
            ) {
                Text(text, style = PfTheme.type.body, color = c.text)
            }
        }
    }
}
