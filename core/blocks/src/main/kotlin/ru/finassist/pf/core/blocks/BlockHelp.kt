package ru.finassist.pf.core.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.HelpTags
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfIconButton
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Auxiliary server text in a separately actionable, scrollable sheet. Main block content stays visible. */
@Composable
internal fun BlockHelpButton(text: String, title: String, tag: String) {
    var shown by remember(text) { mutableStateOf(false) }
    PfIconButton(PfIcons.INFO, "Пояснение: $title", onClick = { shown = true }, modifier = Modifier.testTag("$tag.help"))
    if (shown) BlockHelpSheet(title, text, { shown = false }, Modifier.testTag("$tag.help.sheet"))
}

/** The long-text sheet is shared by every block and tile version. */
@Composable
fun BlockHelpSheet(
    title: String,
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    PfBottomSheet(title, onDismiss, modifier = modifier, footer = footer) {
        Text(text, style = PfTheme.type.body, color = PfTheme.colors.text)
    }
}

@Composable
internal fun BlockHelpRow(text: String, title: String, tag: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { BlockHelpButton(text, title, tag) }
}

internal fun BlockContext.helpButton(
    block: Block,
    title: String = block.title ?: "Пояснение",
    localText: String? = null,
): (@Composable () -> Unit)? {
    if (!anchoredHelp) return null
    val suppliedText = block.helpText?.takeIf { it.isNotBlank() }
    val text = suppliedText ?: localText?.takeIf { it.isNotBlank() } ?: return null
    val automatic = suppliedText == null || canOnboard(block.helpTags, block.helpOrder)
    val key = block.id?.takeIf { automatic && it.isNotBlank() }?.let(::listOf)
    val showInSheet = suppliedText != null && HelpTags.BOTTOM in block.helpTags
    return { HelpPopupButton(text, title, tags.block(block.id ?: block.type.code), key = key, showInSheet = showInSheet) }
}

@Composable
internal fun WithBlockHelp(help: (@Composable () -> Unit)?, content: @Composable () -> Unit) {
    if (help == null) {
        content()
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space2)) {
            Column(Modifier.weight(1f)) { content() }
            help()
        }
    }
}

/** Block-level help belongs inside a single-card renderer, before its contents. */
@Composable
internal fun BlockHelpHeader(help: (@Composable () -> Unit)?) {
    if (help != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { help() }
}
