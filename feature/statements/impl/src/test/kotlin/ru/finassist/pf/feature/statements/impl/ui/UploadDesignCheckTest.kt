package ru.finassist.pf.feature.statements.impl.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.api.model.LoadedSummary
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.screenshot.DesignCheck
import java.time.OffsetDateTime

/** Artboards `ImportGuide*` (guide, fallback, busy, cancel dialog, offline) and `ImportError*` (rejected files). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadDesignCheckTest {
    private fun t(s: String) = OffsetDateTime.parse(s)

    private val config = ImportConfig(
        downloadUrl = "https://www.tbank.ru/login/",
        loginUrl = "https://www.tbank.ru/login/",
        maxFileSizeBytes = 10L * 1024 * 1024,
        acceptedFormats = listOf("OFX"),
        suggestedPeriod = DateRange(t("2025-09-26T00:00:00+03:00"), t("2026-09-26T00:00:00+03:00")),
    )

    /** «Сейчас загружено 1–25 сентября 2026 · 181 операция». */
    private val loaded = config.copy(
        loaded = LoadedSummary(t("2026-09-01T08:15:00+03:00"), t("2026-09-25T21:40:00+03:00"), operationCount = 181),
    )

    private fun capture(
        name: String,
        state: UploadUiState,
        dark: Boolean = false,
        heightDp: Int = 844,
        popups: Boolean = false,
    ) = DesignCheck.capture(name, dark = dark, heightDp = heightDp, popups = popups) {
        UploadContent(
            state, firstRun = false, onBack = {}, onLater = {}, onRetry = {}, onToggleFallback = {}, onOpenUrl = {},
            onPick = {}, onPickAnother = {}, onBackToGuide = {}, onAskCancel = {}, onCancelImport = {},
        )
    }

    private val guide = UploadUiState(phase = UploadPhase.Guide(config))
    private val busy = UploadUiState(phase = UploadPhase.Busy("operations_2026.ofx", 0.6f, uploadId = "u1"))

    private fun error(code: String, file: String) = UploadUiState(phase = UploadPhase.Error(code, file, config))

    @Test fun importGuide() = capture("ImportGuide", guide)

    @Test fun importGuideDark() = capture("ImportGuideDark", guide, dark = true)

    @Test fun importGuideMore() = capture("ImportGuideMore", UploadUiState(phase = UploadPhase.Guide(loaded)), heightDp = 884)

    /** The app expands the manual-export card inline; the mockup draws it as a bottom sheet. */
    @Test fun importGuideFallback() = capture("ImportGuideFallback", guide.copy(fallbackOpen = true))

    @Test fun importGuideBusy() = capture("ImportGuideBusy", busy, heightDp = 884)

    @Test fun importGuideBusyCancel() = capture("ImportGuideBusyCancel", busy.copy(askCancel = true), heightDp = 884, popups = true)

    /** The file did not go out for lack of network: the app shows it as an upload error. */
    @Test fun importGuideOffline() = capture("ImportGuideOffline", error("OFFLINE", "operations_2026.ofx"), heightDp = 1020)

    @Test fun importError() = capture("ImportError", error(ErrorCodes.WRONG_FORMAT, "statement_09_2026.pdf"))

    @Test fun importErrorDark() = capture("ImportErrorDark", error(ErrorCodes.WRONG_FORMAT, "statement_09_2026.pdf"), dark = true)

    @Test fun importErrorBank() = capture("ImportErrorBank", error(ErrorCodes.WRONG_BANK, "statement_09_2026.ofx"))

    @Test fun importErrorExcel() = capture("ImportErrorExcel", error(ErrorCodes.CSV_NOT_ACCEPTED, "operations_09_2026.csv"))

    @Test fun importErrorSize() = capture("ImportErrorSize", error(ErrorCodes.FILE_TOO_LARGE, "statement_09_2026.ofx"))
}

