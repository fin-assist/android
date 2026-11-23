package ru.finassist.pf.feature.analytics.impl.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.blocks.BlockContext
import ru.finassist.pf.core.blocks.BlockHelpPopupButton
import ru.finassist.pf.core.blocks.BlockTags
import ru.finassist.pf.core.blocks.CatalogV1
import ru.finassist.pf.core.designsystem.components.LocalSuppressPopups
import ru.finassist.pf.core.designsystem.components.PfAskCard
import ru.finassist.pf.core.designsystem.components.PfInsightCard
import ru.finassist.pf.core.designsystem.theme.PfTheme
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalyticsHelpPopupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `open help is removed on lock and stays closed after unlock`() {
        val locked = mutableStateOf(false)
        compose.setContent {
            PfTheme {
                CompositionLocalProvider(LocalSuppressPopups provides locked.value) {
                    BlockHelpPopupButton(HELP, "Прогноз", TAG)
                }
            }
        }

        openHelp()
        compose.runOnIdle { locked.value = true }
        compose.onNodeWithText(HELP).assertDoesNotExist()
        compose.runOnIdle { locked.value = false }
        compose.onNodeWithText(HELP).assertDoesNotExist()
        openHelp()
    }

    @Test fun `help is reachable next to a large amount without opening the card`() {
        var openedCard = 0
        compose.setContent {
            PfTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.width(288.dp)) {
                        PfInsightCard(
                            icon = "pill",
                            title = "Аптеки",
                            amount = "123 456 789 ₽",
                            onClick = { openedCard++ },
                            headingHelp = { BlockHelpPopupButton(HELP, "Аптеки", TAG) },
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Аптеки").assertIsDisplayed()
        openHelp()
        assertEquals(0, openedCard)
    }

    @Test fun `assistant help fits beside the heading at a large font and leaves the card tappable`() {
        val title = "Спросите о своих финансах"
        var openedChat = 0
        compose.setContent {
            PfTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.width(288.dp)) {
                        PfAskCard(
                            onClick = { openedChat++ },
                            modifier = Modifier.testTag("ask.card"),
                            title = title,
                            help = { BlockHelpPopupButton(HELP, title, TAG) },
                        )
                    }
                }
            }
        }
        val card = compose.onNodeWithTag("ask.card").assertIsDisplayed()
        val bounds = card.getUnclippedBoundsInRoot()
        val titleBounds = compose.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val helpBounds = compose.onNodeWithTag("$TAG.help").getUnclippedBoundsInRoot()
        assertTrue(titleBounds.right <= helpBounds.left)
        assertTrue(helpBounds.right <= bounds.right)
        assertEquals(48.dp, helpBounds.right - helpBounds.left)
        card.performClick()
        openHelp()
        assertEquals(bounds, card.getUnclippedBoundsInRoot())
        assertEquals(1, openedChat)
    }

    @Test fun `category help keeps the list geometry and row action at large font`() {
        val block = ApiJson.decodeFromString<Block>(
            """{"id":"expense_categories","type":"ranked_list","title":"Расходы по категориям","data":{"items":[
                {"id":"groceries","title":"Супермаркеты","value":123456789,"value_kind":"amount",
                 "action":{"type":"open_operations","filter":{"category_id":"groceries"}}}]}}""",
        )
        val list = requireNotNull(CatalogV1.registry().prepare(block))
        var openedOperations = 0
        compose.setContent {
            PfTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    Box(Modifier.width(288.dp).testTag("ranked.list")) {
                        list.draw(BlockContext("RUB", BlockTags("analytics"), { _, _ -> openedOperations++ },
                            rankedListHelp = mapOf("expense_categories" to HELP), anchoredHelp = true))
                    }
                }
            }
        }
        val bounds = compose.onNodeWithTag("ranked.list").getUnclippedBoundsInRoot()
        val row = compose.onNodeWithTag("analytics.expense_categories.item").assertIsDisplayed()
        val help = compose.onNodeWithTag("analytics.block.expense_categories.help").assertIsDisplayed()
        assertTrue(help.getUnclippedBoundsInRoot().bottom <= row.getUnclippedBoundsInRoot().top)
        row.performClick()
        help.performClick()
        compose.onNodeWithText(HELP).assertIsDisplayed()
        assertEquals(bounds, compose.onNodeWithTag("ranked.list").getUnclippedBoundsInRoot())
        assertEquals(1, openedOperations)
    }

    @Test fun `long help scrolls inside its popup`() {
        val longHelp = List(24) { HELP }.joinToString("\n\n")
        compose.setContent { PfTheme { BlockHelpPopupButton(longHelp, "Прогноз", TAG) } }

        compose.onNodeWithTag("$TAG.help").performClick()
        val scroll = compose.onNode(hasScrollAction()).assertIsDisplayed()
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.maxValue() > 0f)
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 240f) }
        compose.waitForIdle()
        assertTrue(range.value() > 0f)
    }

    private fun openHelp() {
        compose.onNodeWithTag("$TAG.help").assertIsDisplayed().performClick()
        compose.onNodeWithText(HELP).assertIsDisplayed()
    }

    private companion object {
        const val TAG = "analytics.test"
        const val HELP = "Обычные расходы рассчитаны за август–октябрь."
    }
}
