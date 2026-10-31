package ru.finassist.pf.feature.analytics.impl.ui

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
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
import ru.finassist.pf.core.designsystem.components.BarPoint
import ru.finassist.pf.core.designsystem.components.PfBarChart
import ru.finassist.pf.core.designsystem.theme.PfTheme
import java.time.Duration
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
        RuntimeEnvironment.setQualifiers("w320dp-h640dp-$densityQualifier")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        val activity = controller.setup().get()
        try {
            activity.setContent {
                PfTheme {
                    PfBarChart("test.chart", livePoints(), Modifier.width(320.dp), highlight = selected(),
                        onPointClick = clicks?.let { list -> { key -> list += key } })
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
