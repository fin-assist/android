package ru.finassist.pf.feature.analytics.impl.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChartData
import ru.finassist.pf.core.api.model.ChartKind
import ru.finassist.pf.core.api.model.ChartPoint
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.ValueKind
import ru.finassist.pf.core.blocks.BlockContext
import ru.finassist.pf.core.blocks.BlockTags
import ru.finassist.pf.core.blocks.CatalogV1
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.designsystem.components.BarPoint
import ru.finassist.pf.core.designsystem.components.PfBarChart
import ru.finassist.pf.core.designsystem.theme.PfTheme
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Compose wiring of the shared chart, exercised in the JVM without a device or a UI workflow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BarChartComponentTest {
    @Test fun `zero and tiny columns include the whole bar and label in their selection target`() {
        val clicks = mutableListOf<String>()
        val points = listOf(point("2025-12", 0f), point("2026-01", 0.001f), point("2026-02", null, selectable = false))
        render(points, clicks = clicks) { root, density, settle ->
            val zero = root.tagged("test.chart.point.2025-12")
            val tiny = root.tagged("test.chart.point.2026-01")
            listOf(zero, tiny).forEach { node ->
                assertTrue(node.boundsInRoot.height >= 140 * density, "The touch target includes the 120dp bar and the two-line label")
                assertTrue(node.boundsInRoot.width >= 48 * density, "The touch target must not shrink with the amount")
                assertTrue(assertNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            }
            assertEquals(listOf("2025-12", "2026-01"), clicks)
            assertFalse(root.descendants().any { it.config.getOrNull(SemanticsProperties.TestTag) == "test.chart.point.2026-02" })
            assertTrue(assertNotNull(zero.config.getOrNull(SemanticsProperties.ContentDescription)).any { "2025" in it })
        }
    }

    @Test fun `a chart without a callback stays a noninteractive summary`() {
        render(listOf(point("2026-01", 1f)), clicks = null) { root, _, _ ->
            assertFalse(root.descendants().any { it.config.getOrNull(SemanticsActions.OnClick) != null })
        }
    }

    @Test fun `wide charts keep 48dp columns and reveal the newly selected month`() {
        val selected = mutableIntStateOf(11)
        val points = (1..12).map { point("2026-${it.toString().padStart(2, '0')}", 1f) }
        render(points, selected = { selected.intValue }, clicks = mutableListOf()) { root, density, settle ->
            val december = root.tagged("test.chart.point.2026-12")
            assertEquals(true, december.config.getOrNull(SemanticsProperties.Selected))
            assertTrue(december.boundsInRoot.width >= 48 * density, "December bounds ${december.boundsInRoot}, density $density, scroll ${root.scrollDescription()}, points ${root.pointBounds()}")
            assertTrue(december.boundsInRoot.left >= 0f && december.boundsInRoot.right <= 320 * density + 1f)
            selected.intValue = 0
            settle()
            val january = root.tagged("test.chart.point.2026-01")
            assertEquals(true, january.config.getOrNull(SemanticsProperties.Selected))
            assertTrue(january.boundsInRoot.left >= 0f && january.boundsInRoot.right <= 320 * density + 1f)
        }
    }

    @Test fun `growing and shrinking the series reveals the selected point after layout`() {
        fun months(count: Int) = (1..count).map { point("2026-${it.toString().padStart(2, '0')}", 1f) }
        val points = mutableStateOf(months(6))
        val selected = mutableIntStateOf(5)
        render(points.value, selected = { selected.intValue }, livePoints = { points.value }, clicks = mutableListOf()) { root, density, settle ->
            points.value = months(12)
            selected.intValue = 11
            settle()
            val december = root.tagged("test.chart.point.2026-12")
            assertEquals(true, december.config.getOrNull(SemanticsProperties.Selected))
            assertTrue(december.boundsInRoot.width >= 48 * density, "December bounds ${december.boundsInRoot}, density $density, scroll ${root.scrollDescription()}, points ${root.pointBounds()}")
            assertTrue(december.boundsInRoot.left >= 0f && december.boundsInRoot.right <= 320 * density + 1f)
            points.value = months(6)
            selected.intValue = 5
            settle()
            val june = root.tagged("test.chart.point.2026-06")
            assertTrue(june.boundsInRoot.width >= 48 * density, "June bounds ${june.boundsInRoot}, density $density")
            assertTrue(june.boundsInRoot.left >= 0f && june.boundsInRoot.right <= 320 * density + 1f)
        }
    }

    @Test fun `fractional density preserves the minimum target width for every column`() {
        val points = (1..12).map { point("2026-${it.toString().padStart(2, '0')}", 1f) }
        render(points, selected = { -1 }, densityQualifier = "213dpi", clicks = mutableListOf()) { root, density, _ ->
            val minimumPixels = (48 * density).roundToInt()
            points.forEach { point ->
                val node = root.tagged("test.chart.point.${point.key}")
                assertTrue(node.size.width >= minimumPixels, "${point.key}: ${node.size.width}px, required $minimumPixels at density $density")
            }
        }
    }

    @Test fun `real chart renderer dispatches a covered zero point only from Analytics`() {
        val from = OffsetDateTime.parse("2026-09-01T00:00:00+03:00")
        val action = BlockAction(ActionType.SELECT_ANALYTICS_PERIOD, period = PeriodTypeCode.MONTH, date = PeriodKey("2026-09"))
        val data = ChartData(ChartKind.BAR, ValueKind.AMOUNT, listOf(ChartPoint(DateRange(from, from.plusMonths(1)),
            value = 0.0, coverage = Coverage.COMPLETE, action = action)))
        val block = Block.of(BlockType.CHART, ChartData.serializer(), data).copy(id = "monthly_chart")
        val registry = CatalogV1.registry()
        val selected = mutableListOf<Pair<Block, BlockAction>>()
        val select: (Block, BlockAction) -> Unit = { source, target -> selected += source to target }
        renderContent(check = { root, _, _ ->
            val target = root.tagged("analytics.chart.monthly_chart.point.2026-09")
            assertTrue(assertNotNull(target.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            assertEquals(listOf(block to action), selected)
            assertEquals(1, root.descendants().count { it.config.getOrNull(SemanticsActions.OnClick) != null },
                "assistant and balance charts must not expose the selection action")
        }) {
            Column {
                assertNotNull(registry.prepare(block)).draw(BlockContext("RUB", BlockTags("analytics"),
                    onAction = { _, _ -> error("selection uses its narrow capability") }, onSelectAnalyticsPeriod = select))
                assertNotNull(registry.prepare(block)).draw(BlockContext("RUB", BlockTags("assistant"), onAction = { _, _ -> error("inert") }))
                assertNotNull(registry.prepare(block.copy(id = "balance"))).draw(BlockContext("RUB", BlockTags("analytics.balance"),
                    onAction = { _, _ -> error("inert") }, onSelectAnalyticsPeriod = select))
            }
        }
    }

    @Test fun `Analytics screen keeps vertical scroll when a chart selection replaces its response`() {
        val raw = ApiJson.decodeFromString(Analytics.serializer(), javaClass.getResource("/analytics/september.json")!!.readText())
        val august = OffsetDateTime.parse("2026-08-01T00:00:00+03:00")
        val months = (0..1).map { offset ->
            val start = august.plusMonths(offset.toLong())
            ChartPoint(DateRange(start, start.plusMonths(1)), value = 0.0, coverage = Coverage.COMPLETE,
                action = BlockAction(ActionType.SELECT_ANALYTICS_PERIOD, period = PeriodTypeCode.MONTH,
                    date = PeriodKey(start.toString().take(7))))
        }
        fun chart(highlight: Int) = Block.of(BlockType.CHART, ChartData.serializer(),
            ChartData(ChartKind.BAR, ValueKind.AMOUNT, months, highlightIndex = highlight)).copy(id = "monthly_chart")
        val before = (1..20).map { Block.text("Before $it").copy(id = "before_$it") }
        val after = (1..20).map { Block.text("After $it").copy(id = "after_$it") }
        val initial = raw.copy(blocks = before + chart(1) + after)
        val updated = raw.copy(params = raw.params!!.copy(date = PeriodKey("2026-08")), blocks = before + chart(0) + after,
            period = raw.period!!.copy(range = DateRange(august, august.plusMonths(1)), isCurrent = false,
                coverage = Coverage.COMPLETE, dataFrom = august, dataTo = august.plusMonths(1).minusNanos(1), gaps = emptyList()))
        val state = mutableStateOf(AnalyticsUiState(loading = false, data = initial, blocks = AnalyticsBlocks()))
        val handlers = object : AnalyticsHandlers {
            override fun load(quiet: Boolean) = Unit
            override fun retryPeriodChange() = Unit
            override fun consumeSnackbar() = Unit
            override fun setPeriodType(type: PeriodTypeCode) = Unit
            override fun goTo(key: PeriodKey) = Unit
            override fun openSheet(sheet: AnalyticsSheet?) = Unit
            override fun nextHint(stop: Boolean) = Unit
            override fun setTransferMode(mode: TransferMode) = Unit
            override fun currentTransferMode() = TransferMode.WITH
            override fun toggleExpanded(id: String) = Unit
            override fun onBlockShown(block: Block) = Unit
            override fun onAction(block: Block, action: BlockAction, navigate: (BlockAction) -> Unit) {
                assertEquals("monthly_chart", block.id)
                assertEquals(PeriodKey("2026-08"), action.date)
                state.value = state.value.copy(data = updated, response = state.value.response + 1)
            }
        }
        val actions = AnalyticsActions(openSearch = {}, openChat = {}, openUpload = {}, openUnreadLines = { _, _ -> }, back = null)
        renderContent(check = { root, _, settle ->
            fun scrolling() = assertNotNull(root.descendants().firstOrNull {
                it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null
            })
            val target = root.tagged("analytics.expense.monthly.point.2026-08")
            val delta = (target.positionInRoot.y - 200f).coerceAtLeast(0f)
            assertTrue(assertNotNull(scrolling().config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, delta))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            settle()
            val previous = assertNotNull(scrolling().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)).value()
            assertTrue(previous > 0f, "the screen must actually be scrolled before the selection")
            val visible = root.tagged("analytics.expense.monthly.point.2026-08")
            assertTrue(visible.boundsInRoot.height > 0f)
            assertTrue(assertNotNull(visible.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            settle()
            val current = assertNotNull(scrolling().config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)).value()
            assertEquals(previous, current, "a new period response must retain vertical scroll")
            assertEquals(PeriodKey("2026-08"), state.value.data?.params?.date)
            assertTrue(root.tagged("analytics.expense.monthly.point.2026-08").boundsInRoot.height > 0f)
        }) {
            AnalyticsContent(state.value, actions, handlers)
        }
    }

    private fun point(key: String, value: Float?, selectable: Boolean = true) = BarPoint(
        key = key, label = key.replace('-', '\n'), spokenLabel = "Месяц $key", value = value,
        display = value?.toString() ?: "нет данных", selectable = selectable,
    )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        points: List<BarPoint>,
        selected: () -> Int = { 0 },
        livePoints: () -> List<BarPoint> = { points },
        densityQualifier: String = "mdpi",
        clicks: MutableList<String>?,
        check: (SemanticsNode, Float, () -> Unit) -> Unit,
    ) {
        renderContent(densityQualifier, check) {
            PfBarChart("test.chart", livePoints(), Modifier.width(320.dp), highlight = selected(),
                onPointClick = clicks?.let { list -> { key -> list += key } })
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun renderContent(
        densityQualifier: String = "mdpi",
        check: (SemanticsNode, Float, () -> Unit) -> Unit,
        content: @Composable () -> Unit,
    ) {
        RuntimeEnvironment.setQualifiers("w320dp-h640dp-$densityQualifier")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        val activity = controller.setup().get()
        try {
            activity.setContent {
                PfTheme {
                    content()
                }
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            val viewRoot = assertNotNull(findRoot(activity.window.decorView))
            val settle = {
                repeat(3) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                    viewRoot.measureAndLayoutForTest()
                }
            }
            settle()
            val root = viewRoot.semanticsOwner.unmergedRootSemanticsNode
            check(root, activity.resources.displayMetrics.density, settle)
        } finally {
            controller.pause().stop().destroy()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        }
    }

    private fun SemanticsNode.scrollDescription() = descendants().mapNotNull { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) }.map { it.value() to it.maxValue() }

    private fun SemanticsNode.pointBounds() = descendants().mapNotNull { node -> node.config.getOrNull(SemanticsProperties.TestTag)?.let { it to node.boundsInRoot } }

    private fun SemanticsNode.tagged(tag: String): SemanticsNode =
        assertNotNull(descendants().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }, tag)

    private fun SemanticsNode.descendants(): List<SemanticsNode> = listOf(this) + children.flatMap { it.descendants() }

    private fun findRoot(view: View): ViewRootForTest? = when (view) {
        is ViewRootForTest -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findRoot(view.getChildAt(it)) }
        else -> null
    }
}
