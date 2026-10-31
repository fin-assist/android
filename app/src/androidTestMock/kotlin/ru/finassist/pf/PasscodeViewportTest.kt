package ru.finassist.pf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.finassist.pf.core.designsystem.theme.LocalPfSystemBars
import ru.finassist.pf.core.designsystem.theme.PfSystemBars
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.applock.impl.ui.ConfirmPasscodeContent
import ru.finassist.pf.feature.applock.impl.ui.ConfirmUiState
import ru.finassist.pf.feature.applock.impl.ui.UnlockContent
import ru.finassist.pf.feature.applock.impl.ui.UnlockUiState

/** Maestro sees a clipped text node as visible; measured glyph and clipping bounds need the native layer. */
@RunWith(AndroidJUnit4::class)
class PasscodeViewportTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wrongCodeFitsUnlockAndDeletionOnSmallViewport() {
        val mode = mutableStateOf(0)
        val largeFont = mutableStateOf(false)
        var forgot = 0
        var digits = 0
        var deletes = 0
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density, if (largeFont.value) 1.5f else 1f),
                LocalPfSystemBars provides PfSystemBars(
                    statusBars = WindowInsets(top = 24.dp), navigationBars = WindowInsets(bottom = 24.dp),
                ),
            ) {
                PfTheme(darkTheme = false) {
                    Box(Modifier.requiredSize(360.dp, 640.dp).testTag("test.viewport")) {
                        if (mode.value == 2) ConfirmPasscodeContent(
                            ConfirmUiState(entered = "1111", error = "Неверный код"),
                            onBack = {}, onDigit = { digits++ }, onDelete = { deletes++ }, onBiometric = {},
                            onAskForgot = { forgot++ }, onForgot = {},
                        ) else UnlockContent(
                            UnlockUiState(error = if (mode.value == 0) "Неверный код. Осталось 3 попытки"
                                else "Неверный код. Осталась 1 попытка. После неё войти можно будет только по номеру телефона"),
                            onDigit = { digits++ }, onDelete = { deletes++ }, onBiometric = {},
                            onAskForgot = { forgot++ }, onForgot = {},
                        )
                    }
                }
            }
        }
        for (step in 0..2) {
            val deletion = step == 2
            compose.runOnIdle { mode.value = step }
            val error = compose.onNodeWithTag("ds.passcode.error")
            val layouts = mutableListOf<TextLayoutResult>()
            error.assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.size)
            val layout = layouts.single()
            assertFalse("mode=$step text=${layout.layoutInput.text} size=${layout.size} lines=${layout.lineCount} " +
                "widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} " +
                "lastBottom=${layout.getLineBottom(layout.lineCount - 1)}", layout.hasVisualOverflow)
            val viewport = compose.onNodeWithTag("test.viewport").getUnclippedBoundsInRoot()
            for (tag in listOf("ds.passcode.error", "ds.passcode.dots", "ds.numpad.0", "ds.numpad.9", "ds.numpad.delete",
                if (deletion) "applock.confirm.forgot" else "applock.unlock.forgot")) {
                val node = compose.onNodeWithTag(tag).assertIsDisplayed()
                val full = node.getUnclippedBoundsInRoot()
                val clipped = node.fetchSemanticsNode().boundsInRoot
                assertEquals("$tag top must not be clipped", full.top.value, clipped.top / density, 0.6f)
                assertEquals("$tag bottom must not be clipped", full.bottom.value, clipped.bottom / density, 0.6f)
                assertTrue("$tag must be above the bottom inset", full.bottom <= viewport.bottom - 24.dp)
            }
            compose.onNodeWithTag("ds.numpad.9").performClick()
            compose.onNodeWithTag("ds.numpad.delete").performClick()
            compose.onNodeWithTag(if (deletion) "applock.confirm.forgot" else "applock.unlock.forgot").performClick()
        }
        compose.runOnIdle { assertEquals(3, digits); assertEquals(3, deletes); assertEquals(3, forgot) }
        // Large text can scroll the introduction without moving the keys or recovery action.
        val keypadBefore = compose.onNodeWithTag("ds.numpad.0").getUnclippedBoundsInRoot()
        compose.runOnIdle { largeFont.value = true }
        compose.onNodeWithTag("ds.passcode.error").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ds.numpad.0").assertIsDisplayed()
        assertEquals(keypadBefore, compose.onNodeWithTag("ds.numpad.0").getUnclippedBoundsInRoot())
        compose.onNodeWithTag("applock.confirm.forgot").assertIsDisplayed()
    }
}
