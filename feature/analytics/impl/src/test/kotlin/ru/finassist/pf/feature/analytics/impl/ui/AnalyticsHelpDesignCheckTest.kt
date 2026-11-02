package ru.finassist.pf.feature.analytics.impl.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.blocks.BlockHelpSheet
import ru.finassist.pf.core.designsystem.components.BarPoint
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfPagedBarChart
import ru.finassist.pf.core.screenshot.DesignCheck

/** Proposed FIN-416 artboards; no approved new help/history artboard exists. Authored, not executed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AnalyticsHelpDesignCheckTest {
    @Test fun help() = help("AnalyticsHelp")
    @Test fun helpDark() = help("AnalyticsHelpDark", dark = true)
    @Test fun longHelp() = help("AnalyticsHelpLong", paragraphs = 18)
    private fun help(name: String, dark: Boolean = false, paragraphs: Int = 1) = DesignCheck.capture(name, dark = dark, popups = true) {
        BlockHelpSheet("Прогноз расходов на год", List(paragraphs) {
            "К расходам с начала года добавляем прогноз на оставшиеся дни по средним дневным расходам за последние 12 полных месяцев. Неполные месяцы в расчёт не входят."
        }.joinToString("\n\n"), {})
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
