package ru.finassist.pf.core.blocks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import ru.finassist.pf.core.designsystem.components.LocalSuppressPopups
import ru.finassist.pf.core.designsystem.components.pfTestRoot
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** The icon stays compact inside a full-size touch target. */
@Composable
fun BlockHelpPopupButton(text: String, title: String, tag: String, initiallyOpen: Boolean = false) {
    HelpPopupButton(text, title, tag, initiallyOpen, null, null)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HelpPopupButton(
    text: String,
    title: String,
    tag: String,
    initiallyOpen: Boolean = false,
    key: List<String>?,
    order: Int?,
) {
    val suppressed = LocalSuppressPopups.current
    val onboarding = LocalTooltipOnboarding.current
    val requester = remember { BringIntoViewRequester() }
    if (onboarding != null && key != null && order != null) {
        DisposableEffect(onboarding, key, order, requester) {
            val anchor = TooltipOnboarding.Anchor(order) { requester.bringIntoView() }
            onboarding.anchors[key] = anchor
            onDispose { if (onboarding.anchors[key] === anchor) onboarding.anchors.remove(key) }
        }
    }
    val automatic = key != null && onboarding?.current == key
    var shown by remember(text) { mutableStateOf(initiallyOpen && !suppressed) }
    if (suppressed) LaunchedEffect(Unit) { shown = false }
    Box(
        Modifier.size(PfTheme.dimens.touch).bringIntoViewRequester(requester).testTag("$tag.help").clickable(
            enabled = !suppressed,
            role = Role.Button,
            onClickLabel = "Открыть пояснение",
        ) { shown = true },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(Modifier.offset(y = PfTheme.dimens.space1)) {
            PfIcon(PfIcons.INFO, contentDescription = "Пояснение: $title", size = 20.dp, tint = PfTheme.colors.text)
            if (!suppressed && (shown || automatic)) HelpPopup(text, "$tag.help.popup") {
                shown = false
                if (key != null) onboarding?.dismiss(key)
            }
        }
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
                val placement = helpPopupPlacement(anchorBounds, windowSize, layoutDirection, popupContentSize, margin)
                aboveAnchor = placement.aboveAnchor
                arrowX = placement.arrowX.toFloat()
                return placement.offset
            }
        }
    }
    Popup(position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Box(
            Modifier
                .pfTestRoot()
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

internal data class HelpPopupPlacement(val offset: IntOffset, val aboveAnchor: Boolean, val arrowX: Int)

internal fun helpPopupPlacement(
    anchor: IntRect,
    window: IntSize,
    direction: LayoutDirection,
    popup: IntSize,
    margin: Int,
): HelpPopupPlacement {
    val above = anchor.top - margin
    val below = window.height - anchor.bottom - margin
    val aboveAnchor = popup.height <= above || above >= below
    val alignedX = when (direction) {
        LayoutDirection.Ltr -> anchor.right + margin - popup.width
        LayoutDirection.Rtl -> anchor.left - margin
    }
    val x = alignedX.coerceIn(margin, (window.width - popup.width - margin).coerceAtLeast(margin))
    val alignedY = if (aboveAnchor) anchor.top - popup.height else anchor.bottom
    val y = alignedY.coerceIn(margin, (window.height - popup.height - margin).coerceAtLeast(margin))
    return HelpPopupPlacement(IntOffset(x, y), aboveAnchor, anchor.center.x - x)
}
