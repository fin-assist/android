package ru.finassist.pf.feature.analytics.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.blocks.BlockHelpPopupButton
import ru.finassist.pf.core.designsystem.components.BarPoint
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfInsightCard
import ru.finassist.pf.core.designsystem.components.PfPagedBarChart
import ru.finassist.pf.core.designsystem.components.PfStatTile
import ru.finassist.pf.core.screenshot.DesignCheck

/** FIN-416 history artboards and FIN-496 anchored help states. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnalyticsHelpDesignCheckTest {
    @Test fun help() = help("AnalyticsHelp")
    @Test fun helpDark() = help("AnalyticsHelpDark", dark = true)
    @Test fun longHelp() = help("AnalyticsHelpLong", paragraphs = 18, fontScale = 1.5f)

    private fun help(name: String, dark: Boolean = false, paragraphs: Int = 1, fontScale: Float = 1f) =
        DesignCheck.capture(name, dark = dark, popups = true) {
            val title = "Средние расходы в день"
            val text = List(paragraphs) { "По дням с данными не позже сегодня; пропуски не учитываются." }
                .joinToString("\n\n")
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PfStatTile(title, Modifier.weight(1f), value = "1 200 ₽", alignTrailingTop = true, trailing = {
                        BlockHelpPopupButton(text, title, "analytics.tile.daily_expense", initiallyOpen = true)
                    })
                    PfStatTile("Операции", Modifier.weight(1f), value = "12")
                }
            }
        }

    @Test fun insightHelpDark() = DesignCheck.capture("AnalyticsInsightHelpDark", dark = true, popups = true) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
            Column(Modifier.padding(20.dp)) {
                Spacer(Modifier.height(180.dp))
                PfInsightCard(
                    icon = "pill",
                    title = "Аптеки",
                    amount = "11 868 ₽",
                    description = "В 4,2 раза больше обычного — обычно около 2 840,67 ₽",
                    headingHelp = {
                        BlockHelpPopupButton(
                            "Обычные расходы рассчитаны за август–октябрь.",
                            "Аптеки",
                            "analytics.block.notable_spending",
                            initiallyOpen = true,
                        )
                    },
                )
            }
        }
    }

    @Test fun history() = history("AnalyticsHistoryPages")
    @Test fun historyDark() = history("AnalyticsHistoryPagesDark", dark = true)
    private fun history(name: String, dark: Boolean = false) = DesignCheck.capture(name, dark = dark, heightDp = 320) {
        Column(Modifier.padding(20.dp)) {
            PfCard {
                PfPagedBarChart("analytics.expense.monthly", (1..60).map { index ->
                    val month = java.time.YearMonth.of(2021, 1).plusMonths(index - 1L).toString()
                    BarPoint(month, month, month, if (index == 59) 0f else index / 60f, "1000 ₽", selectable = true)
                }, "2025-12", 96.dp, {}, {})
            }
        }
    }
}
