package ru.finassist.pf.feature.statements.impl.ui

import androidx.compose.runtime.CompositionLocalProvider
import ru.finassist.pf.core.common.error.ConnectionEnvironment
import ru.finassist.pf.core.common.error.ConnectionEnvironmentReader
import ru.finassist.pf.core.designsystem.components.LocalConnectionEnvironmentReader
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
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
        environment: ConnectionEnvironment = ConnectionEnvironment.UNKNOWN,
    ) = DesignCheck.capture(name, dark = dark, heightDp = heightDp, popups = popups) {
        CompositionLocalProvider(LocalConnectionEnvironmentReader provides ConnectionEnvironmentReader { environment }) {
            UploadContent(
                state, firstRun = false, onBack = {}, onLater = {}, onRetry = {}, onShowFallback = {}, onOpenUrl = {},
                onPick = {}, onPickAnother = {}, onBackToGuide = {}, onAskCancel = {}, onCancelImport = {}, onRetryUpload = {}, onSupport = {},
            )
        }
    }

    private val guide = UploadUiState(phase = UploadPhase.Guide(config), config = config)
    private val more = UploadUiState(phase = UploadPhase.Guide(loaded), config = loaded)

    /** Parsing keeps the guide on screen; only the file area changes (mockup `ImportGuideBusy`). */
    private val busy = UploadUiState(phase = UploadPhase.Busy("operations_2026.ofx", 0.6f, uploadId = "u1"), config = loaded)

    private fun error(code: String, file: String) = UploadUiState(phase = UploadPhase.Error(code, file, config))

    @Test fun importGuide() = capture("ImportGuide", guide)

    @Test fun importGuideDark() = capture("ImportGuideDark", guide, dark = true)

    @Test fun importGuideMore() = capture("ImportGuideMore", more, heightDp = 924)

    @Test fun importGuideFallback() = capture("ImportGuideFallback", guide.copy(fallbackOpen = true), popups = true)

    @Test fun importGuideBusy() = capture("ImportGuideBusy", busy, heightDp = 924)

    @Test fun importGuideBusyCancel() = capture("ImportGuideBusyCancel", busy.copy(askCancel = true), heightDp = 924, popups = true)

    /** The file did not go out for lack of network: the guide with a notice and «Повторить». */
    @Test fun importGuideOffline() = capture("ImportGuideOffline", more.copy(uploadOffline = true), heightDp = 1040)

    @Test fun importGuideOfflineVpn() = capture("ImportGuideOfflineVpn", more.copy(uploadOffline = true), heightDp = 1040, environment = ConnectionEnvironment.VPN)

    @Test fun importError() = capture("ImportError", error(ErrorCodes.WRONG_FORMAT, "statement_09_2026.pdf"))

    @Test fun importErrorDark() = capture("ImportErrorDark", error(ErrorCodes.WRONG_FORMAT, "statement_09_2026.pdf"), dark = true)

    @Test fun importErrorBank() = capture("ImportErrorBank", error(ErrorCodes.WRONG_BANK, "statement_09_2026.ofx"))

    @Test fun importErrorExcel() = capture("ImportErrorExcel", error(ErrorCodes.CSV_NOT_ACCEPTED, "operations_09_2026.csv"))

    @Test fun importErrorSize() = capture("ImportErrorSize", error(ErrorCodes.FILE_TOO_LARGE, "statement_09_2026.ofx"))
    // FIN-223 fixture: proposed artboard; snapshots are not executed under the current instruction.
    @Test fun serverWarning() {
        val base = error(ErrorCodes.WRONG_BANK, "statement.ofx")
        capture("ImportServerWarning", base.copy(phase = (base.phase as UploadPhase.Error).copy(presentation = ErrorPresentation(UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"), ErrorSeverity.WARNING))))
    }

    @Test fun uploadRecovery() = capture("ImportRecovery", guide.copy(phase = UploadPhase.RecoveryRequired("statement.ofx")))
    @Test fun uploadRecoveryDark() = capture("ImportRecoveryDark", guide.copy(phase = UploadPhase.RecoveryRequired("statement.ofx")), dark = true)
}

