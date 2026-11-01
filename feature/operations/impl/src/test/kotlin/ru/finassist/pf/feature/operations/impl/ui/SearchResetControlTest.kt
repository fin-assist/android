package ru.finassist.pf.feature.operations.impl.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.finassist.pf.core.api.model.OperationKindFilter
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.designsystem.theme.PfTheme
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchResetControlTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `reset stays below filters at the left edge when their row scrolls at large font`() {
        var resets = 0
        var margin = 0f
        val actions = SearchActions({}, {}, {}, {}, { resets++ }, {}, {}, { _, _, _ -> }, {}, { _, _ -> })
        compose.setContent {
            PfTheme {
                val density = LocalDensity.current.density
                margin = with(LocalDensity.current) { PfTheme.dimens.space5.toPx() }
                CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
                    SearchContent(
                        SearchUiState(filter = OperationsFilter(q = "Магазин", kind = OperationKindFilter.EXPENSE),
                            chips = SearchChips(period = true, category = true, amount = true, kind = true)),
                        ZoneOffset.ofHours(3), LocalDate.of(2026, 10, 8), {}, {}, actions,
                    )
                }
            }
        }
        val reset = compose.onNodeWithTag(OperationsTags.CHIP_RESET)
        reset.assertIsDisplayed()
        val before = reset.fetchSemanticsNode().boundsInRoot
        val period = compose.onNodeWithTag(OperationsTags.CHIP_PERIOD).fetchSemanticsNode().boundsInRoot
        assertEquals(margin, before.left, 1f)
        assertTrue(before.top >= period.bottom)
        compose.onNodeWithTag(OperationsTags.CHIP_KIND).performScrollTo()
        reset.assertIsDisplayed()
        assertEquals(before, reset.fetchSemanticsNode().boundsInRoot)
        reset.performClick()
        assertEquals(1, resets)
        compose.onNodeWithTag(OperationsTags.CHIP_PERIOD).performScrollTo()
        reset.assertIsDisplayed()
        assertEquals(before, reset.fetchSemanticsNode().boundsInRoot)
    }
}
