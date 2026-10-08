package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfInsets
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Card: `surface` with a 1 dp `border`, `radius-xl`; `flush` removes the inner padding for list rows. */
@Composable
fun PfCard(
    modifier: Modifier = Modifier,
    flush: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(PfTheme.dimens.radiusXl)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(PfTheme.colors.surface, shape)
            .border(1.dp, PfTheme.colors.border, shape)
            .padding(if (flush) 0.dp else PfTheme.dimens.space4),
        content = content,
    )
}

/** Section heading inside a screen (h2): `caption` 600, `text-muted`. */
@Composable
fun PfSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        // Mockup `.pf-sectitle-text`: 13/20, semibold.
        style = PfTheme.type.caption.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        color = PfTheme.colors.textMuted,
        modifier = modifier.semantics { heading() },
    )
}

/** Header of a tab screen: `title-1`, no back button, optional actions on the right (search). */
@Composable
fun PfTabHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(PfInsets.statusBars)
            // Mockup `.pf-tabhead`: 8 dp under the status bar, a 48 dp row, the title centered in it (FIN-32).
            .padding(start = PfTheme.dimens.screenMargin, end = PfTheme.dimens.space2, top = PfTheme.dimens.space2)
            .defaultMinSize(minHeight = PfTheme.dimens.touch),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = PfTheme.type.title1, modifier = Modifier.weight(1f).semantics { heading() })
        actions()
    }
}

/** Header of a nested screen: back arrow (48), `title-2`, optional subtitle and a trailing action. */
@Composable
fun PfPageHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(PfInsets.statusBars)
            .padding(start = PfTheme.dimens.space3, end = PfTheme.dimens.space3, top = PfTheme.dimens.space2, bottom = PfTheme.dimens.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            PfIconButton(PfIcons.ARROW_LEFT, contentDescription = "Назад", onClick = onBack, modifier = Modifier.testTag(PfTestTags.BACK))
        } else {
            Spacer(Modifier.height(PfTheme.dimens.touch))
        }
        Column(Modifier.weight(1f).padding(start = PfTheme.dimens.space1)) {
            Text(title, style = PfTheme.type.title2, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = PfTheme.type.caption, color = PfTheme.colors.textMuted)
        }
        actions()
    }
}

/** [id] names the tab in its test tag (`ds.tab.<id>`). */
data class TabItem(val icon: String, val label: String, val id: String)

/**
 * Tabs of the main graph, in order: «Операции», «Аналитика», «Профиль». The app's `MainLayer` (which maps them
 * to routes by index) and the design-check harness draw the same bar from this list.
 */
val PfMainTabs: List<TabItem> = listOf(
    TabItem(PfIcons.LIST, "Операции", "operations"),
    TabItem(PfIcons.BAR_CHART, "Аналитика", "analytics"),
    TabItem(PfIcons.USER, "Профиль", "profile"),
)

/** Bottom tab bar: three sections, `nav` background, active tab `accent`, the rest `text-faint`. */
@Composable
fun PfTabBar(
    items: List<TabItem>,
    active: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PfTheme.colors
    Column(modifier = modifier.fillMaxWidth().background(c.nav)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == active
                val tint = if (selected) c.accent else c.textFaint
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // Full height: Row aligns children to the top by default, so a wrap-content item
                        // stuck to the top border (FIN-27). Also makes the whole 56 dp the touch target.
                        .fillMaxHeight()
                        .clickable(role = Role.Tab) { onSelect(index) }
                        .semantics { this.selected = selected }
                        .testTag(PfTestTags.tab(item.id)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PfIcon(item.icon, contentDescription = null, tint = tint)
                    Spacer(Modifier.height(PfTheme.dimens.space1))
                    Text(item.label, style = PfTheme.type.micro, color = tint)
                }
            }
        }
        Spacer(Modifier.windowInsetsPadding(PfInsets.navigationBars))
    }
}
