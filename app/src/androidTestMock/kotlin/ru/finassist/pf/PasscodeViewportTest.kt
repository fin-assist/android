package ru.finassist.pf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
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
import ru.finassist.pf.feature.applock.impl.ui.ChangePasscodeContent
import ru.finassist.pf.feature.applock.impl.ui.EnterStep
import ru.finassist.pf.feature.applock.impl.ui.PasscodeEntryUiState
import ru.finassist.pf.feature.applock.impl.ui.PasscodeSetupContent
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
        val fontScale = mutableStateOf(1f)
        val height = mutableStateOf(640)
        val systemInset = mutableStateOf(24)
        val showError = mutableStateOf(false)
        var forgot = 0
        var digits = 0
        var deletes = 0
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale.value),
                LocalPfSystemBars provides PfSystemBars(
                    statusBars = WindowInsets(top = systemInset.value.dp), navigationBars = WindowInsets(bottom = systemInset.value.dp),
                ),
            ) {
                PfTheme(darkTheme = false) {
                    Box(Modifier.requiredSize(360.dp, height.value.dp).testTag("test.viewport")) {
                        if (mode.value == 2) ConfirmPasscodeContent(
                            ConfirmUiState(entered = "1111", error = if (showError.value) WRONG else null),
                            onBack = {}, onDigit = { digits++ }, onDelete = { deletes++ }, onBiometric = {},
                            onAskForgot = { forgot++ }, onForgot = {},
                        ) else UnlockContent(
                            UnlockUiState(error = if (!showError.value) null else if (mode.value == 0) ORDINARY else LAST),
                            onDigit = { digits++ }, onDelete = { deletes++ }, onBiometric = {},
                            onAskForgot = { forgot++ }, onForgot = {},
                        )
                    }
                }
            }
        }
        var checked = 0
        var checkedActions = 0
        for (inset in listOf(24, 48)) {
            for (viewportHeight in listOf(640, 720, 760)) {
                for (scale in listOf(1f, 1.5f, 2f)) {
                    for (screen in 0..2) {
                        // Measure the error-free state first: the error then changes the scroll range after layout.
                        compose.runOnIdle {
                            systemInset.value = inset
                            height.value = viewportHeight
                            fontScale.value = scale
                            mode.value = screen
                            showError.value = false
                        }
                        val keypadBefore = compose.onNodeWithTag("ds.numpad.0").getUnclippedBoundsInRoot()
                        compose.runOnIdle { showError.value = true }
                        val context = "height=$viewportHeight font=$scale mode=$screen insets=$inset"
                        assertFullyVisible(screen, density, context, inset)
                        assertEquals("$context keypad must not move when the error appears", keypadBefore,
                            compose.onNodeWithTag("ds.numpad.0").getUnclippedBoundsInRoot())
                        if (viewportHeight == 640 && ((scale == 1f && inset == 24) || (scale == 2f && inset == 48))) {
                            compose.onNodeWithTag("ds.numpad.9").performClick()
                            compose.onNodeWithTag("ds.numpad.delete").performClick()
                            compose.onNodeWithTag(footerTag(screen)).performClick()
                            checkedActions++
                        }
                        checked++
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(54, checked)
            assertEquals(6, checkedActions)
            assertEquals(checkedActions, digits); assertEquals(checkedActions, deletes); assertEquals(checkedActions, forgot)
        }

        // Keep exactly the same long error while changing font/viewport: an effect keyed only by error misses this.
        compose.runOnIdle { mode.value = 1; height.value = 760; fontScale.value = 1f }
        assertFullyVisible(1, density, "before resizing the same error", systemInset.value)
        for ((viewportHeight, scale) in listOf(720 to 2f, 640 to 2f, 760 to 1.5f)) {
            compose.runOnIdle { height.value = viewportHeight; fontScale.value = scale }
            assertFullyVisible(1, density, "same error height=$viewportHeight font=$scale", systemInset.value)
        }
    }

    @Test fun errorFreePasscodeFamilyShowsDotsAtLargeFont() {
        val mode = mutableStateOf(0)
        val entered = mutableStateOf("")
        var density = 1f
        var backs = 0
        var forgot = 0
        compose.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density, 2f),
                LocalPfSystemBars provides PfSystemBars(
                    statusBars = WindowInsets(top = 48.dp), navigationBars = WindowInsets(bottom = 48.dp),
                ),
            ) {
                PfTheme(darkTheme = false) {
                    Box(Modifier.requiredSize(360.dp, 640.dp).testTag("test.viewport")) {
                        val onDigit: (Int) -> Unit = { entered.value += it.toString() }
                        val onDelete: () -> Unit = { entered.value = entered.value.dropLast(1) }
                        when (mode.value) {
                            0, 1 -> PasscodeSetupContent(
                                PasscodeEntryUiState(if (mode.value == 0) EnterStep.NEW else EnterStep.REPEAT, entered.value),
                                onDigit, onDelete, onEnableBiometric = {}, onSkipBiometric = {},
                            )
                            2, 3, 4 -> ChangePasscodeContent(
                                PasscodeEntryUiState(listOf(EnterStep.OLD, EnterStep.NEW, EnterStep.REPEAT)[mode.value - 2], entered.value),
                                onBack = { backs++ }, onDigit, onDelete,
                            )
                            5 -> UnlockContent(
                                UnlockUiState(entered = entered.value), onDigit, onDelete, onBiometric = {},
                                onAskForgot = { forgot++ }, onForgot = {},
                            )
                            else -> ConfirmPasscodeContent(
                                ConfirmUiState(entered = entered.value), onBack = { backs++ }, onDigit, onDelete,
                                onBiometric = {}, onAskForgot = { forgot++ }, onForgot = {},
                            )
                        }
                    }
                }
            }
        }
        for (screen in 0..6) {
            compose.runOnIdle { mode.value = screen; entered.value = "" }
            val extras = buildList {
                if (screen in 2..4 || screen == 6) add("ds.back")
                if (screen == 5) add("applock.unlock.forgot")
                if (screen == 6) add("applock.confirm.forgot")
            }
            val context = "error-free family mode=$screen height640/font2/insets48"
            assertGeometry(density, context, 48, "ds.passcode.dots", extras)
            compose.onNodeWithTag("ds.numpad.9").performClick()
            compose.onNodeWithTag("ds.passcode.dots").assertContentDescriptionEquals("Код-пароль: введено 1 из 4 цифр")
            assertGeometry(density, "$context first digit", 48, "ds.passcode.dots", extras)
            compose.onNodeWithTag("ds.numpad.delete").performClick()
            compose.onNodeWithTag("ds.passcode.dots").assertContentDescriptionEquals("Код-пароль: введено 0 из 4 цифр")
            if ("ds.back" in extras) compose.onNodeWithTag("ds.back").performClick()
            if (screen >= 5) compose.onNodeWithTag(footerTag(if (screen == 5) 0 else 2)).performClick()
        }
        compose.runOnIdle { assertEquals(4, backs); assertEquals(2, forgot) }

        // Only after the automatic checks: the user's scroll up must survive a digit-only recomposition.
        val introduction = compose.onNode(hasScrollAction())
        val range = introduction.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.runOnIdle { assertTrue("The boundary must actually overflow", range.value() > 0f) }
        introduction.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -10000f) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0f, range.value(), 0.01f); entered.value = "1" }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Do not override a user's scroll without a new range", 0f, range.value(), 0.01f) }
    }

    private fun assertFullyVisible(mode: Int, density: Float, context: String, inset: Int) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("ds.passcode.error").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.size)
        val layout = layouts.single()
        assertFalse("$context text=${layout.layoutInput.text} size=${layout.size} lines=${layout.lineCount} " +
            "widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight}", layout.hasVisualOverflow)
        assertGeometry(density, context, inset, "ds.passcode.error", listOf("ds.passcode.error", footerTag(mode)))
    }

    private fun assertGeometry(density: Float, context: String, inset: Int, lastBlock: String, extraTags: List<String>) {
        val viewport = compose.onNodeWithTag("test.viewport").getUnclippedBoundsInRoot()
        val tags = listOf("ds.passcode.dots", "ds.numpad.delete") + (0..9).map { "ds.numpad.$it" } + extraTags
        val nodes = compose.onAllNodes(SemanticsMatcher("PIN geometry") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.let { it in tags } == true
        }).fetchSemanticsNodes()
        assertEquals("$context checked node count", tags.size, nodes.size)
        val byTag = nodes.associateBy { it.config[SemanticsProperties.TestTag] }
        for (tag in tags) {
            val node = byTag.getValue(tag)
            val full = Rect(node.positionInRoot, node.size.toSize())
            val clipped = node.boundsInRoot
            assertTrue("$context $tag visible", clipped.width > 0f && clipped.height > 0f)
            assertEquals("$context $tag left clipped", full.left, clipped.left, 0.6f * density)
            assertEquals("$context $tag right clipped", full.right, clipped.right, 0.6f * density)
            assertEquals("$context $tag top clipped", full.top, clipped.top, 0.6f * density)
            assertEquals("$context $tag bottom clipped", full.bottom, clipped.bottom, 0.6f * density)
            assertTrue("$context $tag above bottom inset", full.bottom / density <= (viewport.bottom - inset.dp).value)
        }
        val bottom = byTag.getValue(lastBlock)
        val keypad = byTag.getValue("ds.numpad.1")
        assertTrue("$context $lastBlock must not overlap the keypad", bottom.positionInRoot.y + bottom.size.height <= keypad.positionInRoot.y)

    }

    private fun footerTag(mode: Int) = if (mode == 2) "applock.confirm.forgot" else "applock.unlock.forgot"

    private companion object {
        const val WRONG = "Неверный код"
        const val ORDINARY = "Неверный код. Осталось 3 попытки"
        const val LAST = "Неверный код. Осталась 1 попытка. После неё войти можно будет только по номеру телефона"
    }
}
