package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Single-choice list (radio group semantics). */
@Composable
fun OptionList(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = label }, content = content)
}

/** Group heading inside an option list («Системные», «Расходы»). */
@Composable
fun OptionGroup(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Text(title.uppercase(), style = PfTheme.type.overline, color = PfTheme.colors.textFaint, modifier = Modifier.padding(top = PfSpace.s3, bottom = PfSpace.s1))
        content()
    }
}

/** Option row: optional icon tile, title, description, check mark when selected. Min height 56. */
@Composable
fun OptionRow(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: String? = null, description: String? = null, divider: Boolean = true) {
    val c = PfTheme.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = PfSize.row)
                .clickable(role = Role.RadioButton, onClick = onClick)
                .semantics(mergeDescendants = true) { this.selected = selected }
                .padding(vertical = PfSpace.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                IconTile(icon, small = true)
                Spacer(Modifier.width(PfSpace.s3))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = PfTheme.type.body, color = c.text)
                if (description != null) Text(description, style = PfTheme.type.hint, color = c.textMuted)
            }
            if (selected) PfIcon("check", size = PfSize.iconMd, tint = c.accent)
        }
        if (divider) Divider()
    }
}
