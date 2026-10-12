package ru.finassist.pf.core.designsystem.components

import ru.finassist.pf.core.designsystem.theme.PfInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme

/**
 * `true` while the app is covered by the unlock screen: dialogs and sheets are separate windows that would
 * draw above it, so they are not shown until the app is unlocked (their own open/closed state is kept).
 */
val LocalSuppressPopups = compositionLocalOf { false }

enum class NoticeTone { INFO, WARNING, POSITIVE, LIMIT }

/**
 * Notice (mockup `.pf-notice`): `info` explains what the app did, `warning` asks to check something, `positive`
 * confirms an action, `limit` — the question limit is exhausted (clock icon, info colours). 12/16 dp padding,
 * 18 dp icon 8 dp from the text, `13/20` text, title `14/20 700` 4 dp above it; [action] follows the text
 * directly (a standalone link brings its own 48 dp). [icon] overrides the tone's icon. `alert` announces
 * immediately.
 */
@Composable
fun PfNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.INFO,
    title: String? = null,
    alert: Boolean = false,
    icon: String? = null,
    /** Text with inline links (`Pf.Link inline` inside the notice); replaces [text] when given. */
    parts: List<TextPart>? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    val (bg, fg, toneIcon) = when (tone) {
        NoticeTone.INFO -> Triple(c.infoSoft, c.infoText, PfIcons.INFO)
        NoticeTone.WARNING -> Triple(c.warningSoft, c.warningText, PfIcons.ALERT)
        NoticeTone.POSITIVE -> Triple(c.positiveSoft, c.positive, PfIcons.CHECK_CIRCLE)
        NoticeTone.LIMIT -> Triple(c.infoSoft, c.infoText, PfIcons.CLOCK)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(d.radiusLg))
            .padding(horizontal = d.space4, vertical = d.space3)
            .semantics(mergeDescendants = true) { liveRegion = if (alert) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(d.space2),
        verticalAlignment = Alignment.Top,
    ) {
        PfIcon(icon ?: toneIcon, contentDescription = null, size = 18.dp, tint = fg, modifier = Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f)) {
            if (title != null) {
                Text(title, style = PfTheme.type.body.copy(fontWeight = FontWeight.Bold), color = fg)
                Spacer(Modifier.height(d.space1))
            }
            if (parts != null) PfLinkedText(parts, style = PfTheme.type.caption, color = fg) else Text(text, style = PfTheme.type.caption, color = fg)
            if (action != null) action()
        }
    }
}

/**
 * Empty state (mockup `.pf-empty`): 72 dp icon tile (`radius-2xl`, 34 dp icon) on `accent-soft`, 20 dp to a
 * `20/24 700` title, `15/20` muted text, actions 24 dp below stretched to the full width, 8 dp apart; 32 dp
 * padding around. The screen decides where the block sits (the mockups centre it in the free space).
 */
@Composable
fun PfEmptyState(
    icon: String,
    title: String,
    text: String? = null,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(PfTheme.dimens.space8),
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    // Centred vertically when the caller gives it the height (`Modifier.weight(1f)`): mockups centre the block in
    // the free area of the screen.
    Column(
        modifier.fillMaxWidth().padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(72.dp).background(c.accentSoft, RoundedCornerShape(d.radius2xl)),
            contentAlignment = Alignment.Center,
        ) {
            PfIcon(icon, contentDescription = null, size = 34.dp, tint = c.accent)
        }
        Spacer(Modifier.height(d.space5))
        Text(
            title,
            style = PfTheme.type.amountMd.copy(fontFeatureSettings = null),
            color = c.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        if (text != null) {
            Spacer(Modifier.height(d.space2))
            Text(text, style = PfTheme.type.lead, color = c.textMuted, textAlign = TextAlign.Center)
        }
        if (actions != null) {
            Spacer(Modifier.height(d.space6))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(d.space2), content = actions)
        }
    }
}

enum class HeroTone { POSITIVE, WARNING, INFO }

/**
 * Status icon (mockup `.pf-hero`: 64 dp, `radius-xl`, 30 dp icon) with an optional `title-1` 20 dp below and a
 * muted `caption` subtitle 4 dp under it: import result, import error.
 */
@Composable
fun PfStatusHero(
    modifier: Modifier = Modifier,
    tone: HeroTone = HeroTone.POSITIVE,
    icon: String? = null,
    title: String? = null,
    subtitle: String? = null,
    centered: Boolean = true,
) {
    val c = PfTheme.colors
    val (bg, fg, defaultIcon) = when (tone) {
        HeroTone.POSITIVE -> Triple(c.positiveSoft, c.positive, PfIcons.CHECK_CIRCLE)
        HeroTone.WARNING -> Triple(c.warningSoft, c.warningText, PfIcons.ALERT)
        HeroTone.INFO -> Triple(c.accentSoft, c.accent, PfIcons.INFO)
    }
    val align = if (centered) Alignment.CenterHorizontally else Alignment.Start
    val textAlign = if (centered) TextAlign.Center else TextAlign.Start
    Column(modifier.fillMaxWidth(), horizontalAlignment = align) {
        Box(Modifier.size(PfTheme.dimens.status).background(bg, RoundedCornerShape(PfTheme.dimens.radiusXl)), contentAlignment = Alignment.Center) {
            PfIcon(icon ?: defaultIcon, contentDescription = null, size = 30.dp, tint = fg)
        }
        if (title != null) {
            Spacer(Modifier.height(PfTheme.dimens.space5))
            Text(title, style = PfTheme.type.title1, color = c.text, textAlign = textAlign, modifier = Modifier.semantics { heading() })
        }
        if (subtitle != null) {
            Spacer(Modifier.height(PfTheme.dimens.space1))
            Text(subtitle, style = PfTheme.type.caption, color = c.textMuted, textAlign = textAlign)
        }
    }
}

/** Small status badge («предварительно», «есть чек»): `micro`, `radius-full`. */
@Composable
fun PfBadge(text: String, modifier: Modifier = Modifier, positive: Boolean = false, icon: String? = null) {
    val c = PfTheme.colors
    val fg = if (positive) c.positive else c.textMuted
    Row(
        modifier = modifier
            .background(if (positive) c.positiveSoft else Color.Transparent, RoundedCornerShape(PfTheme.dimens.radiusFull))
            .border(1.dp, if (positive) Color.Transparent else c.borderStrong, RoundedCornerShape(PfTheme.dimens.radiusFull))
            .padding(horizontal = PfTheme.dimens.space2, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            PfIcon(icon, contentDescription = null, size = PfTheme.dimens.iconSm, tint = fg)
            Spacer(Modifier.width(PfTheme.dimens.space1))
        }
        Text(text, style = PfTheme.type.micro, color = fg)
    }
}

/**
 * Questions left (mockup `.pf-limit`): «Осталось **3 из 5** вопросов на сегодня» on the left (`13/20`, the count
 * in `text`), five 16×8 dots on the right — used `border-strong`, left `accent`. Announced as a status.
 */
@Composable
fun PfLimitMeter(used: Int, modifier: Modifier = Modifier, total: Int = 5) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    val left = (total - used).coerceIn(0, total)
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(d.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            buildAnnotatedString {
                append("Осталось ")
                withStyle(SpanStyle(color = c.text, fontWeight = FontWeight.Bold)) { append("$left из $total") }
                append(" вопросов на сегодня")
            },
            style = PfTheme.type.caption,
            color = c.textMuted,
            modifier = Modifier.weight(1f),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(d.space1),
            modifier = Modifier.semantics { contentDescription = "Использовано $used из $total" },
        ) {
            repeat(total) { i ->
                Box(
                    Modifier
                        .size(width = 16.dp, height = 8.dp)
                        .background(if (i >= used) c.accent else c.borderStrong, RoundedCornerShape(d.radiusFull)),
                )
            }
        }
    }
}

/**
 * Confirmation dialog for an irreversible action: title (question), description (what is lost), extra content,
 * a `danger` button and a `ghost` «Отмена». System back closes it like «Отмена».
 */
@Composable
fun PfDialog(
    title: String,
    description: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = true,
    busy: Boolean = false,
    busyText: String? = null,
    cancelText: String = "Отмена",
    content: @Composable ColumnScope.() -> Unit = {},
) {
    if (LocalSuppressPopups.current) return
    val c = PfTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = !busy)) {
        Column(
            modifier
                .pfTestRoot()
                .testTag(PfTestTags.DIALOG)
                .fillMaxWidth()
                .background(c.surface, RoundedCornerShape(PfTheme.dimens.radius2xl))
                .padding(PfTheme.dimens.space5),
        ) {
            Text(title, style = PfTheme.type.title2, color = c.text, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(PfTheme.dimens.space2))
            Text(description, style = PfTheme.type.body, color = c.textMuted)
            content()
            // `.pf-dialog-actions { margin-top: var(--space-4); gap: var(--space-2) }`
            Spacer(Modifier.height(PfTheme.dimens.space4))
            PfButton(
                text = confirmText, onClick = onConfirm, block = true, busy = busy, busyText = busyText,
                variant = if (danger) ButtonVariant.DANGER else ButtonVariant.PRIMARY,
                modifier = Modifier.testTag(PfTestTags.DIALOG_CONFIRM),
            )
            Spacer(Modifier.height(PfTheme.dimens.space2))
            PfButton(
                text = cancelText, onClick = onDismiss, block = true, variant = ButtonVariant.GHOST, enabled = !busy,
                modifier = Modifier.testTag(PfTestTags.DIALOG_CANCEL),
            )
        }
    }
}

/**
 * Bottom sheet (mockup `.pf-sheet`): handle, 8 dp below it a `title-2` header with a close button, content and
 * footer on the sheet's 20 dp side margin — rows inside (`PfOptionRow`) add no padding of their own. Content
 * scrolls between the header and the footer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PfBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalSuppressPopups.current) return
    val c = PfTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.surface,
        scrimColor = c.scrim,
        shape = RoundedCornerShape(topStart = PfTheme.dimens.radius2xl, topEnd = PfTheme.dimens.radius2xl),
        dragHandle = {
            Box(Modifier.padding(top = PfTheme.dimens.space2).size(width = 36.dp, height = 4.dp).background(c.borderStrong, RoundedCornerShape(PfTheme.dimens.radiusFull)))
        },
        modifier = modifier.pfTestRoot(),
    ) {
        Row(
            // `.pf-sheet-head { margin-top: var(--space-2) }`; the close button sits 12 dp into the right margin.
            Modifier.fillMaxWidth().padding(start = PfTheme.dimens.space5, end = PfTheme.dimens.space2, top = PfTheme.dimens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = PfTheme.type.title2, color = c.text, modifier = Modifier.weight(1f).semantics { heading() })
            PfIconButton(PfIcons.X, contentDescription = "Закрыть", onClick = onDismiss, tint = c.textMuted, modifier = Modifier.testTag(PfTestTags.SHEET_CLOSE))
        }
        // Content scrolls between the header and the footer; the footer stays reachable on long lists.
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PfTheme.dimens.space5)
                .padding(bottom = PfTheme.dimens.space4),
        ) { content() }
        if (footer != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = PfTheme.dimens.space5)) { footer() }
        }
        Spacer(Modifier.windowInsetsPadding(PfInsets.navigationBars))
        Spacer(Modifier.height(PfTheme.dimens.space4))
    }
}

/** Bottom message about an action's result — text only. Light: `text` on `bg`; dark: `surface` with a `border-strong` outline. */
@Composable
fun PfSnackbar(text: String, modifier: Modifier = Modifier) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(PfTheme.dimens.radiusMd)
    Box(
        modifier
            .fillMaxWidth()
            .background(if (c.isDark) c.surface else c.text, shape)
            .then(if (c.isDark) Modifier.border(1.dp, c.borderStrong, shape) else Modifier)
            .padding(horizontal = PfTheme.dimens.space4, vertical = PfTheme.dimens.space3)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(PfTestTags.SNACKBAR),
    ) {
        Text(text, style = PfTheme.type.body, color = if (c.isDark) c.text else c.bg)
    }
}

/**
 * Non-modal hint anchored to an element (mockup `.pf-coach`): `info-soft`, a 16 dp arrow pointing [up] (or
 * down) at [arrowOffset] from the left, optional `15/20 700` title, `14/20` text and two ghost buttons —
 * «Понятно» and «Больше не показывать» — whose labels line up with the text. Padding 16 / 16 / 4.
 */
@Composable
fun PfCoachmark(
    text: String,
    onClose: () -> Unit,
    onNever: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    up: Boolean = true,
    arrowOffset: Dp = 24.dp,
) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    val shape = RoundedCornerShape(d.radiusLg)
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    // The arrow: a 16 dp square turned 45°, half of it outside the card.
                    val side = 16.dp.toPx()
                    val cx = arrowOffset.toPx()
                    val cy = if (up) 0f else size.height
                    rotate(45f, pivot = Offset(cx, cy)) {
                        drawRect(c.infoSoft, topLeft = Offset(cx - side / 2, cy - side / 2), size = Size(side, side))
                    }
                }
                .background(c.infoSoft, shape)
                .padding(start = d.space4, end = d.space4, top = d.space4, bottom = d.space1),
        ) {
            if (title != null) {
                Text(title, style = PfTheme.type.bodyStrong.copy(fontWeight = FontWeight.Bold), color = c.infoText)
                Spacer(Modifier.height(d.space1))
            }
            Text(text, style = PfTheme.type.body, color = c.infoText)
            Spacer(Modifier.height(d.space1))
            // `.pf-coach-actions { margin-left: -16px }`: the ghost buttons' own padding puts the labels on the text edge.
            Row(Modifier.offset(x = -d.space4)) {
                PfButton("Понятно", onClick = onClose, variant = ButtonVariant.GHOST)
                PfButton("Больше не показывать", onClick = onNever, variant = ButtonVariant.GHOST)
            }
        }
    }
}
