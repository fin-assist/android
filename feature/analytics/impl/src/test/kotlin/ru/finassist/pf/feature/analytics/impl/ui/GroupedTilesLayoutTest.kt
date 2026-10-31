package ru.finassist.pf.feature.analytics.impl.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.TileGroupDataV2
import ru.finassist.pf.core.api.model.TileGroupSection
import ru.finassist.pf.core.api.model.ValueKind
import ru.finassist.pf.core.blocks.BlockContext
import ru.finassist.pf.core.blocks.BlockFormat
import ru.finassist.pf.core.blocks.BlockTags
import ru.finassist.pf.core.blocks.CatalogV1
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.designsystem.components.PfAmountText
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h1200dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupedTilesLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun show(block: Block, fontScale: Float = 1f, onAction: (String) -> Unit = {}) {
        val prepared = requireNotNull(CatalogV1.registry().prepare(block))
        compose.setContent {
            PfTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    Column(Modifier.width(320.dp).padding(20.dp)) {
                        prepared.draw(BlockContext("RUB", BlockTags("groups"), onAction = { _, action -> onAction(action.type.code) }))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @Test fun `larger system text retains the signed result and separated additional heading`() {
        val groups = GroupedTilesFixtures.groups().mapIndexed { index, group ->
            if (index == 0) group.copy(tiles = group.tiles.map { tile ->
                if (tile.id == "difference") tile.copy(value = -7_258_000.0) else tile
            }) else group
        }
        show(Block.of(BlockType.TILE_GROUP, TileGroupDataV2.serializer(), TileGroupDataV2(groups), v = 2), fontScale = 1.3f)
        val text = requireNotNull(BlockFormat.value(-7_258_000.0, ValueKind.AMOUNT))
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
        compose.onNodeWithText("Дополнительные показатели").assertIsDisplayed()
        val result = compose.onNodeWithTag("groups.tile.difference").fetchSemanticsNode().boundsInRoot
        val average = compose.onNodeWithTag("groups.tile.average").fetchSemanticsNode().boundsInRoot
        assertTrue(average.top > result.bottom)
    }

    @Test fun `facts share a row then the result spans the row above additional metrics`() {
        show(GroupedTilesFixtures.block())
        val cost = compose.onNodeWithTag("groups.tile.cost").fetchSemanticsNode().boundsInRoot
        val earned = compose.onNodeWithTag("groups.tile.earned").fetchSemanticsNode().boundsInRoot
        val result = compose.onNodeWithTag("groups.tile.difference").fetchSemanticsNode().boundsInRoot
        val average = compose.onNodeWithTag("groups.tile.average").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(cost.top - earned.top) < 1f)
        assertTrue(cost.right < earned.left)
        assertTrue(result.top >= cost.bottom)
        // Interactive tiles expose their whole touch area; a static tile exposes its padded content.
        assertTrue(result.width > cost.width * 1.5f, "the unpaired result must span both columns")
        assertTrue(abs(result.center.x - (cost.left + earned.right) / 2) < 1f,
            "cost=$cost, earned=$earned, result=$result")
        assertTrue(average.top > result.bottom)
        compose.onNodeWithText("Дополнительные показатели").assertIsDisplayed()
    }

    @Test fun `long signed amounts fit a narrow section without losing the numeric text`() {
        show(GroupedTilesFixtures.block(large = true))
        for ((id, amount, plus) in listOf(Triple("cost", 987_654_321_000.0, false), Triple("earned", 123_456_789_000.0, true),
            Triple("difference", -864_197_532_000.0, false))) {
            val layouts = mutableListOf<TextLayoutResult>()
            val text = requireNotNull(BlockFormat.value(amount, ValueKind.AMOUNT, showPlus = plus))
            compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse(layouts.single().hasVisualOverflow, "Amount clipped: $text")
            val spoken = compose.onNodeWithTag("groups.tile.$id").fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()
            assertTrue(text in spoken, "The full signed amount must remain available to accessibility")
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun `narrow amount fitting paints digits after each value change settles`() {
        val text = androidx.compose.runtime.mutableStateOf("84\u00A0320\u00A0₽")
        var contentView: View? = null
        compose.setContent {
            PfTheme {
                val view = LocalView.current
                androidx.compose.runtime.SideEffect { contentView = view }
                Box(Modifier.width(106.dp).background(Color.White)) {
                    PfAmountText(text.value, PfTheme.type.amountMd, Color.Black, Modifier.testTag("fitting"))
                }
            }
        }
        for (value in listOf("84\u00A0320\u00A0₽", "9\u00A0876\u00A0543\u00A0210\u00A0₽", "−8\u00A0641\u00A0975\u00A0320\u00A0₽", "0\u00A0₽")) {
            compose.runOnIdle { text.value = value }
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            repeat(16) {
                compose.mainClock.advanceTimeBy(16)
                compose.runOnIdle { (contentView as ViewRootForTest).measureAndLayoutForTest() }
            }
            val darkPixels = compose.runOnIdle {
                val view = requireNotNull(contentView)
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                view.draw(Canvas(bitmap))
                val count = (0 until bitmap.height).sumOf { y ->
                    (0 until bitmap.width).count { x -> android.graphics.Color.red(bitmap.getPixel(x, y)) < 128 }
                }
                bitmap.recycle()
                count
            }
            assertTrue(darkPixels > 10, "Amount remained blank after fitting: $value")
        }
    }

    @Test fun `one additional metric fills its row when the producer omits a forecast`() {
        val groups = GroupedTilesFixtures.groups().mapIndexed { index, group ->
            if (index == 1) group.copy(tiles = group.tiles.take(1)) else group
        }
        show(Block.of(BlockType.TILE_GROUP, TileGroupDataV2.serializer(), TileGroupDataV2(groups), v = 2))
        val result = compose.onNodeWithTag("groups.tile.difference").fetchSemanticsNode().boundsInRoot
        val average = compose.onNodeWithTag("groups.tile.average").fetchSemanticsNode().boundsInRoot
        assertTrue(abs(result.width - average.width) < 1f)
        compose.onNodeWithTag("groups.tile.estimate").assertDoesNotExist()
    }

    @Test fun `a hidden empty section has no title and locked tiles cannot run actions`() {
        val groups = listOf(TileGroupSection(emptyList(), "Не показывать")) + GroupedTilesFixtures.groups(locked = true)
        val block = Block.of(BlockType.TILE_GROUP, TileGroupDataV2.serializer(), TileGroupDataV2(groups), v = 2)
        val actions = mutableListOf<String>()
        show(block) { actions += it }
        compose.onNodeWithText("Не показывать").assertDoesNotExist()
        compose.onNodeWithTag("groups.tile.estimate").assertHasNoClickAction()
        compose.onNodeWithTag("groups.tile.cost").performClick()
        assertEquals(listOf("open_operations"), actions)
        compose.onNodeWithText("0\u00A0₽", useUnmergedTree = true).assertIsDisplayed()
    }
}
