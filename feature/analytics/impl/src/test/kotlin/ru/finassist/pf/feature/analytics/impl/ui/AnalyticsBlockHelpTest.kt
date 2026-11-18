package ru.finassist.pf.feature.analytics.impl.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import ru.finassist.pf.core.blocks.BlockContext
import ru.finassist.pf.core.blocks.BlockTags
import ru.finassist.pf.core.blocks.CatalogV1
import ru.finassist.pf.core.designsystem.theme.PfTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h844dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalyticsBlockHelpTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `catalog connects exactly one popup to supported blocks and fallbacks`() {
        val fixtures = ApiJson.decodeFromString(
            Analytics.serializer(), checkNotNull(javaClass.getResource("/analytics/september.json")).readText(),
        )
        val item = """{"title":"Аптеки","icon":"pill","value":100,"value_kind":"amount"}"""
        val tile = """{"id":"tile","title":"Расходы","value":100,"value_kind":"amount"}"""
        val examples = listOf(
            block("rows", "rows", """{"rows":[{"label":"Расходы","text":"1 000 ₽"}]}"""),
            block("plain", "ranked_list", """{"style":"plain","items":[$item]}"""),
            block("bars", "ranked_list", """{"items":[$item]}"""),
            block("empty", "ranked_list", """{"items":[]}""", status = "none"),
            block("tiles-v1", "tile_group", """{"tiles":[$tile]}"""),
            block("tiles-v2", "tile_group", """{"groups":[{"tiles":[$tile]}]}""", version = 2),
            block("locked-tiles", "tile_group", "{}", version = 2, status = "locked"),
            block("empty-tiles", "tile_group", "{}", version = 2, status = "none"),
            block("card", "insight_card", """{"title":"Аптеки","icon":"pill"}"""),
            fixtures.blocks.first { it.id == "monthly_chart" }.copy(helpText = HELP),
            block("broken-chart", "chart", "{}"),
            block("broken-card", "insight_card", "{}"),
            block("newer-card", "insight_card", """{"title":"Аптеки","icon":"pill"}""", version = 9),
        )
        val current = mutableStateOf(examples.first())
        val registry = CatalogV1.registry()
        compose.setContent {
            PfTheme {
                val block = current.value
                key(block.id) {
                    Box(Modifier.width(350.dp)) {
                        checkNotNull(registry.prepare(block)).draw(context(anchoredHelp = true))
                    }
                }
            }
        }

        for (block in examples) {
            compose.runOnIdle { current.value = block }
            when (block.id) {
                "card" -> compose.onNodeWithText("Аптеки").assertIsDisplayed()
                "broken-chart", "broken-card", "newer-card" -> compose.onNodeWithText(FALLBACK).assertIsDisplayed()
                "tiles-v1", "tiles-v2" -> compose.onNodeWithText(BLOCK_TITLE).assertDoesNotExist()
            }
            val tag = "analytics.block.${block.id}.help"
            compose.onAllNodesWithTag(tag).assertCountEquals(1)
            compose.onNodeWithTag(tag).performClick()
            compose.onNodeWithTag("$tag.popup").assertIsDisplayed()
            compose.onNodeWithText(HELP).assertIsDisplayed()
            compose.onNodeWithTag("$tag.sheet").assertDoesNotExist()
        }
    }

    @Test fun `catalog still opens a sheet in contexts without anchored help`() {
        val block = block("rows", "rows", """{"rows":[{"label":"Расходы","text":"1 000 ₽"}]}""")
        val prepared = checkNotNull(CatalogV1.registry().prepare(block))
        compose.setContent { PfTheme { prepared.draw(context(anchoredHelp = false)) } }

        compose.onAllNodesWithTag("analytics.block.rows.help").assertCountEquals(1)
        compose.onNodeWithTag("analytics.block.rows.help").performClick()
        compose.onNodeWithTag("analytics.block.rows.help.sheet").assertIsDisplayed()
        compose.onNodeWithTag("analytics.block.rows.help.popup").assertDoesNotExist()
    }

    private fun context(anchoredHelp: Boolean) =
        BlockContext("RUB", BlockTags("analytics"), onAction = { _, _ -> }, anchoredHelp = anchoredHelp)

    private fun block(id: String, type: String, data: String, version: Int = 1, status: String = "ready") =
        ApiJson.decodeFromString(
            Block.serializer(),
            """{"id":"$id","type":"$type","v":$version,"status":"$status","alt_text":"$FALLBACK",
                "title":"$BLOCK_TITLE","empty_text":"Данных пока нет","help_text":"$HELP","data":$data}""",
        )

    private companion object {
        const val HELP = "Пояснение из ответа сервера"
        const val BLOCK_TITLE = "Заголовок блока"
        const val FALLBACK = "Текстовая версия блока"
    }
}
