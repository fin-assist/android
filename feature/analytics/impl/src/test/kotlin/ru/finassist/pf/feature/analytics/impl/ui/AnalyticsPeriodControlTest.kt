package ru.finassist.pf.feature.analytics.impl.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.designsystem.theme.PfTheme
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalyticsPeriodControlTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a wide period row shows all four full labels and their selection semantics`() = checkPeriods(390, 1f)
    @Test fun `a narrow large-text period row reveals selection and preserves manual scrolling`() = checkPeriods(320, 1.3f)

    private fun checkPeriods(widthDp: Int, fontScale: Float) {
        val data = ApiJson.decodeFromString(Analytics.serializer(), javaClass.getResource("/analytics/all_time.json")!!.readText())
        val shown = mutableStateOf(data)
        val types = listOf(PeriodTypeCode.MONTH, PeriodTypeCode.QUARTER, PeriodTypeCode.YEAR, PeriodTypeCode.ALL_TIME)
        val labels = listOf("Месяц", "Квартал", "Год", "За всё время")
        val tags = listOf("ds.segment.0", "ds.segment.1", "ds.segment.2", "analytics.period.all_time")
        val keys = listOf("2026-09", "2026-Q3", "2026", "all_time")
        val selected = mutableListOf<PeriodTypeCode>()
        val handlers = object : AnalyticsHandlers {
            override fun load(quiet: Boolean) = Unit
            override fun retryPeriodChange() = Unit
            override fun consumeSnackbar() = Unit
            override fun setPeriodType(type: PeriodTypeCode) {
                selected += type
                shown.value = shown.value.copy(params = shown.value.params!!.copy(period = type, date = PeriodKey(keys[types.indexOf(type)])))
            }
            override fun goTo(key: PeriodKey) = Unit
            override fun openSheet(sheet: AnalyticsSheet?) = Unit
            override fun nextHint(stop: Boolean) = Unit
            override fun setTransferMode(mode: TransferMode) = Unit
            override fun currentTransferMode() = TransferMode.WITH
            override fun toggleExpanded(id: String) = Unit
            override fun onAction(block: Block, action: BlockAction, navigate: (BlockAction) -> Unit) = Unit
            override fun onBlockShown(block: Block) = Unit
        }
        var density = 1f
        compose.setContent {
            PfTheme {
                density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    Box(Modifier.width(widthDp.dp)) {
                        AnalyticsContent(AnalyticsUiState(loading = false, data = shown.value, blocks = AnalyticsBlocks()),
                            AnalyticsActions({}, {}, {}, { _, _ -> }, null), handlers)
                    }
                }
            }
        }
        compose.waitForIdle()
        val control = compose.onNodeWithTag("analytics.period.types")
        val viewport = control.fetchSemanticsNode().boundsInRoot
        val axis = requireNotNull(control.fetchSemanticsNode().config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange))
        fun assertVisible(index: Int) {
            val bounds = compose.onNodeWithTag(tags[index]).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 48 * density, "selected ${labels[index]} must be fully visible: $bounds, viewport=$viewport")
            assertTrue(bounds.left >= viewport.left + 4 * density - 1f && bounds.right <= viewport.right - 4 * density + 1f)
        }
        assertEquals(ToggleableState.On, compose.onNodeWithTag(tags.last()).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
        assertVisible(types.lastIndex)
        if (widthDp == 390) {
            assertEquals(0f, axis.maxValue(), "all four labels fit a wide screen")
            tags.indices.forEach(::assertVisible)
        } else {
            assertTrue(axis.value() > 0f && axis.maxValue() > 0f, "initial all-time selection must be revealed after layout")
            control.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(-10_000f, 0f)) }
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            assertEquals(0f, axis.value(), "the current selection must not continually undo manual scrolling")
            assertTrue(selected.isEmpty(), "scrolling never chooses a period")
            compose.runOnIdle { shown.value = shown.value.copy(params = shown.value.params!!.copy(period = PeriodTypeCode.MONTH, date = PeriodKey("2026-09"))) }
            compose.waitForIdle()
            assertVisible(0)
            compose.runOnIdle { shown.value = shown.value.copy(params = shown.value.params!!.copy(period = PeriodTypeCode.ALL_TIME, date = PeriodKey.ALL_TIME)) }
            compose.waitForIdle()
            assertVisible(types.lastIndex)
            assertTrue(axis.value() > 0f, "an external selection change reveals the selected option")
            assertTrue(selected.isEmpty(), "automatic reveal never invokes a selection callback")
        }
        for ((selectedIndex, type) in types.withIndex()) {
            compose.onNodeWithTag(tags[selectedIndex]).performScrollTo().performClick()
            compose.waitForIdle()
            val nodes = tags.map { compose.onNodeWithTag(it).fetchSemanticsNode() }
            val top = nodes.first().positionInRoot.y
            nodes.forEachIndexed { index, node ->
                assertTrue(node.size.width >= 48 * density && node.size.height >= 48 * density, "$fontScale ${labels[index]}: ${node.size}")
                assertTrue(abs(node.positionInRoot.y - top) < 1f, "all periods must remain in one row")
                if (index > 0) assertTrue(node.positionInRoot.x >= nodes[index - 1].positionInRoot.x + nodes[index - 1].size.width)
                assertEquals(index == selectedIndex, node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On)
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNode(hasText(labels[index]) and hasAnyAncestor(hasTestTag(tags[index])), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertFalse(layouts.single().hasVisualOverflow, "$fontScale ${labels[index]} must never clip or ellipsize")
                assertEquals(1, layouts.single().lineCount, "labels stay full and single-line at the system font scale")
            }
            assertVisible(selectedIndex)
            assertEquals(type, selected.last())
        }
        assertEquals(types, selected)
    }
}
