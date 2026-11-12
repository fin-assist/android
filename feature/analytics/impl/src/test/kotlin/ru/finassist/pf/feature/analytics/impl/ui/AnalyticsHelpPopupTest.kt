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
import androidx.compose.ui.test.assertIsDisplayed
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
import ru.finassist.pf.core.blocks.BlockHelpPopupButton
import ru.finassist.pf.core.designsystem.components.LocalSuppressPopups
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
