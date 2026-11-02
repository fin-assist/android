package ru.finassist.pf.feature.statements.impl.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.api.model.LoadedSummary
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.LegalLinks
import ru.finassist.pf.core.designsystem.theme.PfTheme
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UploadDownloadControlTest {
    @get:Rule val compose = createComposeRule()
    private fun t(value: String) = OffsetDateTime.parse(value)
    private val empty = ImportConfig("https://example.org/year", "https://example.org/login", 10_485_760, listOf("OFX"),
        DateRange(t("2025-10-08T00:00:00+03:00"), t("2026-10-09T00:00:00+03:00")))

    @Test fun `copy follows loaded operations and keeps the exact server download URL`() {
        val config = mutableStateOf(empty)
        val opened = mutableListOf<String>()
        compose.setContent {
            PfTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    UploadContent(
                        UploadUiState(phase = UploadPhase.Guide(config.value), config = config.value), false,
                        {}, {}, {}, {}, { opened += it }, {}, {}, {}, {}, {}, {}, {},
                    )
                }
            }
        }
        val download = compose.onNodeWithTag(StatementsTags.UPLOAD_DOWNLOAD)
        download.performScrollTo().assertTextEquals("Скачать за год").performClick()
        assertEquals(listOf(empty.downloadUrl), opened)
        val more = empty.copy(downloadUrl = "https://example.org/new?start=server&end=server",
            loaded = LoadedSummary(t("2026-09-01T10:00:00+03:00"), t("2026-09-25T21:00:00+03:00"), 181),
            suggestedPeriod = DateRange(t("2026-09-25T00:00:00+03:00"), t("2026-10-09T00:00:00+03:00")))
        compose.runOnIdle { config.value = more }
        download.performScrollTo().assertTextEquals("Скачать новые данные").performClick()
        assertEquals(listOf(empty.downloadUrl, more.downloadUrl), opened)
        val layout = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText("Скачать новые данные") and hasAnyAncestor(hasTestTag(StatementsTags.UPLOAD_DOWNLOAD)), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        val textLayout = layout.single()
        assertEquals(2, textLayout.lineCount)
        assertEquals("Скачать новые данные".length, textLayout.getLineEnd(textLayout.lineCount - 1))
        repeat(textLayout.lineCount) { line ->
            assertFalse(textLayout.isLineEllipsized(line))
            assertTrue(textLayout.getLineLeft(line) >= -1f)
            assertTrue(textLayout.getLineRight(line) <= textLayout.size.width + 1f,
                "Line $line extends outside ${textLayout.size}: ${textLayout.getLineRight(line)}")
        }
        assertTrue(textLayout.multiParagraph.height <= textLayout.size.height + 1f)
        val privacy = "Операции видите только вы. Удалить загрузку или аккаунт — в профиле. Как мы защищаем данные"
        compose.onNodeWithText(privacy).performScrollTo().assertTextEquals(privacy)
        compose.onNode(hasClickAction() and hasAnyAncestor(hasText(privacy)), useUnmergedTree = true).performClick()
        assertEquals(LegalLinks.PRIVACY, opened.last())
        compose.runOnIdle { config.value = empty }
        download.performScrollTo().assertTextEquals("Скачать за год")
    }
}
