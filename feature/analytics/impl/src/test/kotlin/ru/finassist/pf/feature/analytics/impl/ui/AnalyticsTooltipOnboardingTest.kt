package ru.finassist.pf.feature.analytics.impl.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.blocks.BlockContext
import ru.finassist.pf.core.blocks.BlockTags
import ru.finassist.pf.core.blocks.CatalogV1
import ru.finassist.pf.core.designsystem.components.LocalSuppressPopups
import ru.finassist.pf.core.designsystem.theme.PfTheme
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalyticsTooltipOnboardingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun waitsForUnlockAndHintsThenShowsTheBlockBeforeItsTile() {
        val locked = mutableStateOf(true)
        val state = mutableStateOf(
            AnalyticsUiState(blocks = AnalyticsBlocks(), hint = AnalyticsHint.CATEGORIES, owner = RequestOwner("user", "login")),
        )
        val registry = CatalogV1.registry()
        val blocks = registry.prepareAll(listOf(
            decode("""{"id":"group","type":"tile_group","help_text":"Group","help_tags":["onboarding"],"help_order":2,
                "data":{"tiles":[{"id":"legacy","title":"Legacy","help_text":"Legacy help","help_tags":["onboarding"],"help_order":2}]}}"""),
            decode("""{"id":"v2","type":"tile_group","v":2,"data":{"groups":[{"tiles":[
                {"id":"","title":"Missing ID","help_text":"Manual","help_tags":["onboarding"],"help_order":1},
                {"id":"current","title":"Current","help_text":"Current help","help_tags":["onboarding"],"help_order":2}]}]}}"""),
            decode("""{"id":"last","type":"text","help_text":"Last help","help_tags":["onboarding"],"help_order":6,
                "data":{"text":"Last"}}"""),
        ))
        assertEquals(
            listOf(listOf("group"), listOf("group", "legacy"), listOf("v2"), listOf("v2", "current"), listOf("last")),
            tooltipResponseOrder(blocks),
        )
        var claims = 0
        compose.setContent {
            PfTheme {
                CompositionLocalProvider(LocalSuppressPopups provides locked.value) {
                    AnalyticsTooltipOnboarding(state.value, blocks, claim = { ++claims == 1 }) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            blocks.forEach { it.draw(context(anchored = true)) }
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(0, claims)
            locked.value = false
        }
        compose.runOnIdle {
            assertEquals(0, claims)
            state.value = state.value.copy(hint = null, sheet = AnalyticsSheet.PERIODS)
        }
        compose.runOnIdle {
            assertEquals(0, claims)
            state.value = state.value.copy(sheet = null)
        }
        assertPopup("block.group")
        compose.onNodeWithTag("analytics.tile.legacy.help.popup").assertDoesNotExist()
        compose.onNodeWithTag("analytics.tile.current.help.popup").assertDoesNotExist()
        compose.runOnIdle { locked.value = true }
        compose.onNodeWithTag("analytics.block.group.help.popup").assertDoesNotExist()
        compose.runOnIdle { locked.value = false }
        compose.onNodeWithTag("analytics.block.group.help.popup").assertDoesNotExist()
        compose.onNodeWithTag("analytics.tile.legacy.help.popup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, claims) }
    }

    @Test
    fun aResponseWithoutRenderedAnchorsDoesNotClaimTheSequence() {
        val block = decode("""{"id":"block","type":"text","help_text":"Manual","help_tags":["onboarding"],"help_order":1,
            "data":{"text":"Visible"}}""")
        val blocks = CatalogV1.registry().prepareAll(listOf(block))
        var claims = 0
        compose.setContent {
            PfTheme {
                val state = AnalyticsUiState(blocks = AnalyticsBlocks(), owner = RequestOwner("user", "login"))
                AnalyticsTooltipOnboarding(state, blocks, claim = { ++claims == 1 }) {
                    blocks.forEach { it.draw(context(anchored = false)) }
                }
            }
        }
        compose.runOnIdle { assertEquals(0, claims) }
        compose.onNodeWithTag("analytics.block.block.help").assertIsDisplayed()
    }

    private fun assertPopup(tag: String) {
        compose.onNodeWithTag("analytics.$tag.help.popup").assertIsDisplayed()
    }

    private fun context(anchored: Boolean) = BlockContext("RUB", BlockTags("analytics"), { _, _ -> }, anchoredHelp = anchored)

    private fun decode(json: String): Block = ApiJson.decodeFromString(json)
}
