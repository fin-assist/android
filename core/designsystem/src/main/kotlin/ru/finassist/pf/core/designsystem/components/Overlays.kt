package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.finassist.pf.core.designsystem.theme.PfRadius
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Bottom sheet (README «BottomSheet»): scrim, 24 dp top radius, handle, title + close. Back/scrim/swipe dismiss. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PfBottomSheet(title: String, onDismiss: () -> Unit, footer: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = PfTheme.colors
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = c.surface,
        contentColor = c.text,
        scrimColor = c.scrim,
        shape = RoundedCornerShape(topStart = PfRadius.xxl, topEnd = PfRadius.xxl),
        dragHandle = {
            Box(Modifier.padding(top = PfSpace.s3, bottom = PfSpace.s1).width(36.dp).height(4.dp).background(c.borderStrong, RoundedCornerShape(PfRadius.full)))
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = PfSpace.s5).windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = PfSpace.s4)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = PfTheme.type.title2, color = c.text, modifier = Modifier.weight(1f).semantics { heading() })
                PfIconButton("x", contentDescription = "Закрыть", onClick = onDismiss)
            }
            content()
            if (footer != null) {
                Spacer(Modifier.height(PfSpace.s4))
                footer()
            }
        }
    }
}

/** Confirmation dialog (README «Dialog»): title, description of what is lost, actions. Only for irreversible actions. */
@Composable
fun PfDialog(
    title: String,
    description: String,
    onDismiss: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String = "Отмена",
    danger: Boolean = true,
    busy: Boolean = false,
    busyText: String? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = PfTheme.colors
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(dismissOnClickOutside = !busy)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(c.surface, RoundedCornerShape(PfRadius.xxl))
                .padding(PfSpace.s5),
        ) {
            Text(title, style = PfTheme.type.title2, color = c.text, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(PfSpace.s2))
            Text(description, style = PfTheme.type.body, color = c.textMuted)
            if (extra != null) {
                Spacer(Modifier.height(PfSpace.s3))
                extra()
            }
            Spacer(Modifier.height(PfSpace.s5))
            Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                PfButton(confirmText, onClick = onConfirm, variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary, block = true, busy = busy, busyText = busyText)
                PfButton(dismissText, onClick = onDismiss, variant = ButtonVariant.Ghost, block = true, enabled = !busy)
            }
        }
    }
}

/** Non-modal hint after the first import (README «Coachmark»): info colours, «Понятно» / «Больше не показывать». */
@Composable
fun Coachmark(text: String, onClose: () -> Unit, onNever: () -> Unit, modifier: Modifier = Modifier, title: String? = null) {
    val c = PfTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.infoSoft, RoundedCornerShape(PfRadius.lg))
            .padding(PfSpace.s4),
    ) {
        if (title != null) Text(title, style = PfTheme.type.captionStrong, color = c.infoText)
        Text(text, style = PfTheme.type.caption, color = c.infoText)
        Spacer(Modifier.height(PfSpace.s2))
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
            PfButton("Понятно", onClick = onClose, variant = ButtonVariant.Ghost)
            PfButton("Больше не показывать", onClick = onNever, variant = ButtonVariant.Ghost)
        }
    }
}
