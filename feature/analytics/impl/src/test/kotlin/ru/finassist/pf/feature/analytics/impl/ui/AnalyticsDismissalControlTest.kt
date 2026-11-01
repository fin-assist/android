package ru.finassist.pf.feature.analytics.impl.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.InsightCardData
import ru.finassist.pf.core.api.model.InsightItem
import ru.finassist.pf.core.blocks.BlockContext
import ru.finassist.pf.core.blocks.BlockTags
import ru.finassist.pf.core.blocks.InsightCardRenderer
import ru.finassist.pf.core.designsystem.theme.PfTheme
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalyticsDismissalControlTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `a pending dismissal keeps its row visible but disables the mutation button`() {
        val pending = mutableStateOf(emptySet<String>())
        var calls = 0
        val block = Block.of(BlockType.INSIGHT_CARD, InsightCardData.serializer(), InsightCardData("repeat", "Подписки",
            items = listOf(InsightItem("Спортзал", button = BlockAction(ActionType.DISMISS_REGULAR_PAYMENT, paymentId = "one", label = "Не подписка")))))
        val draw = requireNotNull(InsightCardRenderer.prepare(block))
        compose.setContent {
            PfTheme {
                draw(BlockContext("RUB", BlockTags("analytics"), onAction = { _, _ -> calls++; pending.value = setOf("one") },
                    pendingDismissals = pending.value))
            }
        }
        compose.onNodeWithContentDescription("Не подписка — Спортзал").performClick()
        compose.onNodeWithText("Обновляем…").assertIsNotEnabled()
        compose.onNodeWithText("Спортзал").assertExists()
        assertEquals(1, calls)
        compose.runOnIdle { pending.value = emptySet() }
        compose.onNodeWithContentDescription("Не подписка — Спортзал").assertExists()
    }
}
