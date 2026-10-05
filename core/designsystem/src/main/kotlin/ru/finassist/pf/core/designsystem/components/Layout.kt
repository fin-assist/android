package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.theme.PfRadius
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Card: `surface`, 1 px `border`, radius xl, padding 16 (`flush` → no padding, rows draw their own dividers). */
@Composable
fun PfCard(modifier: Modifier = Modifier, flush: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(PfRadius.xl)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface, shape)
            .border1(c.border, shape)
            .then(if (flush) Modifier else Modifier.padding(PfSpace.s4)),
        content = content,
    )
}

/** Section heading (h2): overline-like label `text-muted`, 600. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = PfTheme.type.bodyStrong, color = PfTheme.colors.textMuted, modifier = Modifier.weight(1f).semantics { heading() })
        trailing?.invoke()
    }
}

/** Header of a tab screen: `title-1`, no back button, optional icon actions on the right. Adds the status-bar inset. */
@Composable
fun TabHeader(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = PfSpace.s5, end = PfSpace.s3, top = PfSpace.s4, bottom = PfSpace.s2)
            .semantics { paneTitle = title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = PfTheme.type.title1, color = PfTheme.colors.text, modifier = Modifier.weight(1f).semantics { heading() })
        actions()
    }
}

/** Header of a nested screen: back arrow, `title-2`, optional subtitle. Adds the status-bar inset. */
@Composable
fun PageHeader(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = PfSpace.s3, end = PfSpace.s3, top = PfSpace.s2, bottom = PfSpace.s2)
            .semantics { paneTitle = title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PfIconButton("arrow-left", contentDescription = "Назад", onClick = onBack)
        Column(Modifier.weight(1f).padding(horizontal = PfSpace.s1)) {
            Text(title, style = PfTheme.type.title2, color = PfTheme.colors.text, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = PfTheme.type.caption, color = PfTheme.colors.textMuted)
        }
        actions()
    }
}

data class TabItem(val icon: String, val label: String)

/** Bottom tab bar on `nav`, 80 dp including the gesture inset. Active item `accent`, others `text-faint`. */
@Composable
fun PfTabBar(items: List<TabItem>, active: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = PfTheme.colors
    Column(modifier.fillMaxWidth().background(c.nav)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).height(PfSize.row)) {
            items.forEachIndexed { i, item ->
                val tint = if (i == active) c.accent else c.textFaint
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clickable(role = Role.Tab) { onSelect(i) }
                        .semantics { selected = i == active },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PfIcon(item.icon, size = PfSize.iconLg, tint = tint)
                    Spacer(Modifier.height(PfSpace.s1))
                    Text(item.label, style = PfTheme.type.micro, color = tint)
                }
            }
        }
    }
}

/** Standard screen side padding (`space-5`). */
val ScreenPadding = PfSpace.s5
