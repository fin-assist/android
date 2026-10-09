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
 * Notice: `info` explains what the app did, `warning` asks to check something, `positive` confirms an action,
 * `limit` — the question limit is exhausted (clock icon, info colours). `alert` announces immediately.
 */
@Composable
fun PfNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.INFO,
    title: String? = null,
    alert: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    val c = PfTheme.colors
    val (bg, fg, icon) = when (tone) {
        NoticeTone.INFO -> Triple(c.infoSoft, c.infoText, PfIcons.INFO)
        NoticeTone.WARNING -> Triple(c.warningSoft, c.warningText, PfIcons.ALERT)
        NoticeTone.POSITIVE -> Triple(c.positiveSoft, c.positive, PfIcons.CHECK_CIRCLE)
        NoticeTone.LIMIT -> Triple(c.infoSoft, c.infoText, PfIcons.CLOCK)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(PfTheme.dimens.radiusLg))
            .padding(PfTheme.dimens.space4)
            .semantics(mergeDescendants = true) { liveRegion = if (alert) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        PfIcon(icon, contentDescription = null, size = PfTheme.dimens.iconMd, tint = fg, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(PfTheme.dimens.space3))
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = PfTheme.type.bodyStrong, color = fg)
            Text(text, style = PfTheme.type.caption, color = fg)
            if (action != null) {
                Spacer(Modifier.height(PfTheme.dimens.space2))
                action()
            }
        }
    }
}

/** Empty state of a main screen: large icon on `accent-soft`, title, one or two sentences, actions below. */
@Composable
fun PfEmptyState(
    icon: String,
    title: String,
    text: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(PfTheme.dimens.space8), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(PfTheme.dimens.status).background(PfTheme.colors.accentSoft, RoundedCornerShape(PfTheme.dimens.radiusXl)),
            contentAlignment = Alignment.Center,
        ) {
            PfIcon(icon, contentDescription = null, size = 28.dp, tint = PfTheme.colors.accent)
        }
        Spacer(Modifier.height(PfTheme.dimens.space4))
        Text(title, style = PfTheme.type.title2, color = PfTheme.colors.text, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        if (text != null) {
            Spacer(Modifier.height(PfTheme.dimens.space2))
            Text(text, style = PfTheme.type.body, color = PfTheme.colors.textMuted, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(PfTheme.dimens.space6))
        actions()
    }
}

enum class HeroTone { POSITIVE, WARNING, INFO }

/** Large status icon (64) with title and subtitle: import result, import error. */
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
            PfIcon(icon ?: defaultIcon, contentDescription = null, size = 32.dp, tint = fg)
        }
        if (title != null) {
            Spacer(Modifier.height(PfTheme.dimens.space4))
            Text(title, style = PfTheme.type.title1, color = c.text, textAlign = textAlign, modifier = Modifier.semantics { heading() })
        }
        if (subtitle != null) {
            Spacer(Modifier.height(PfTheme.dimens.space2))
            Text(subtitle, style = PfTheme.type.lead, color = c.textMuted, textAlign = textAlign)
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

/** «Осталось N из 5» with five segments: remaining `accent`, used `border-strong`. Announced as a status. */
@Composable
fun PfLimitMeter(used: Int, total: Int = 5, modifier: Modifier = Modifier) {
    val c = PfTheme.colors
    val remaining = (total - used).coerceAtLeast(0)
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Text(
            if (remaining == total) "$total вопросов в день" else "Осталось $remaining из $total",
            style = PfTheme.type.hint, color = c.textMuted,
        )
        Spacer(Modifier.height(PfTheme.dimens.space1))
        Row(horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space1)) {
            repeat(total) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(if (i < remaining) c.accent else c.borderStrong, RoundedCornerShape(PfTheme.dimens.radiusFull)),
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
            Spacer(Modifier.height(PfTheme.dimens.space5))
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

/** Bottom sheet with a handle, `title-2` and a close button; content scrolls inside. */
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
            Box(Modifier.padding(top = PfTheme.dimens.space3).size(width = 36.dp, height = 4.dp).background(c.borderStrong, RoundedCornerShape(PfTheme.dimens.radiusFull)))
        },
        modifier = modifier.pfTestRoot(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = PfTheme.dimens.space5, end = PfTheme.dimens.space2),
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

/** Non-modal hint anchored near an element: `info-soft`, «Понятно» and «Больше не показывать». */
@Composable
fun PfCoachmark(
    text: String,
    onClose: () -> Unit,
    onNever: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
) {
    val c = PfTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.infoSoft, RoundedCornerShape(PfTheme.dimens.radiusLg))
            .padding(PfTheme.dimens.space4),
    ) {
        if (title != null) Text(title, style = PfTheme.type.bodyStrong, color = c.infoText)
        Text(text, style = PfTheme.type.caption, color = c.infoText)
        Spacer(Modifier.height(PfTheme.dimens.space2))
        Row {
            PfButton("Понятно", onClick = onClose, variant = ButtonVariant.GHOST)
            PfButton("Больше не показывать", onClick = onNever, variant = ButtonVariant.GHOST)
        }
    }
}
