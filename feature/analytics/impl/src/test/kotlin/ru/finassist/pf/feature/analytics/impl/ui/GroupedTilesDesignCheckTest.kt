package ru.finassist.pf.feature.analytics.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.blocks.BlockContext
import ru.finassist.pf.core.blocks.BlockTags
import ru.finassist.pf.core.blocks.CatalogV1
import ru.finassist.pf.core.screenshot.DesignCheck

/** Proposed FIN-403 artboards: the existing tile component with server-authored groups, not approved Figma frames. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupedTilesDesignCheckTest {
    private fun capture(name: String, block: Block = GroupedTilesFixtures.block(), dark: Boolean = false, narrow: Boolean = false) {
        val prepared = requireNotNull(CatalogV1.registry().prepare(block))
        DesignCheck.capture(name, dark = dark, heightDp = 1100) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.then(if (narrow) Modifier.width(320.dp) else Modifier.fillMaxWidth()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    prepared.draw(BlockContext("RUB", BlockTags("groups"), onAction = { _, _ -> }))
                }
            }
        }
    }

    @Test fun groupedSummary() = capture("AnalyticsGroupedSummary")
    @Test fun groupedSummaryDark() = capture("AnalyticsGroupedSummaryDark", dark = true)
    @Test fun groupedSummaryNarrowLongAmounts() = capture("AnalyticsGroupedSummaryNarrow", GroupedTilesFixtures.block(large = true), narrow = true)
    @Test fun groupedSummaryLockedForecast() = capture("AnalyticsGroupedSummaryLocked", GroupedTilesFixtures.block(locked = true), narrow = true)
    @Test fun groupedSummaryNoData() = capture("AnalyticsGroupedSummaryNoData", GroupedTilesFixtures.block().copy(
        status = BlockStatus.NONE, emptyText = "За этот период данных нет"))
    @Test fun groupedSummaryLegacy() = capture("AnalyticsGroupedSummaryLegacy", GroupedTilesFixtures.legacy())
    @Test fun groupedSummaryUnknownVersion() = capture("AnalyticsGroupedSummaryUnknown", GroupedTilesFixtures.block().copy(
        v = 99, altText = "Сервер прислал новую версию сводки. Расходы за период 84 320 ₽, доходы 156 900 ₽."))
}
