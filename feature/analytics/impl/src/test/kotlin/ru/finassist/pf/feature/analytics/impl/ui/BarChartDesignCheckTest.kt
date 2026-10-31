package ru.finassist.pf.feature.analytics.impl.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.designsystem.components.BarPoint
import ru.finassist.pf.core.designsystem.components.PfBarChart
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.screenshot.DesignCheck
import ru.finassist.pf.core.common.time.RussianDates
import java.time.Month

/** Proposed component artboards for FIN-398; an approved interactive-chart Figma artboard does not exist yet. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BarChartDesignCheckTest {
    @Test fun selectableMonths() = capture("AnalyticsSelectableMonths")
    @Test fun selectableMonthsDark() = capture("AnalyticsSelectableMonthsDark", dark = true)

    private fun capture(name: String, dark: Boolean = false) = DesignCheck.capture(name, dark = dark, heightDp = 320) {
        Column(Modifier.padding(20.dp)) {
            PfCard {
                PfBarChart(
                    chartId = "analytics.months", highlight = 11,
                    points = (1..12).map { month ->
                        BarPoint(
                            key = "2026-${month.toString().padStart(2, '0')}", label = "${RussianDates.monthShort(Month.of(month))}\n2026",
                            spokenLabel = "Месяц $month 2026", value = if (month == 10) 0f else month / 12f,
                            display = "${month * 1000} ₽", selectable = true,
                        )
                    },
                    onPointClick = {},
                )
            }
        }
    }
}
