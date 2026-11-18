package ru.finassist.pf.mock.api

import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.AnalyticsApi
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsBlockUpdate
import ru.finassist.pf.core.api.model.AnalyticsChartPage
import ru.finassist.pf.core.api.model.AnalyticsCalculation
import ru.finassist.pf.core.api.model.ChartData
import ru.finassist.pf.core.api.ApiJson
import kotlinx.serialization.json.jsonObject
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockLock
import ru.finassist.pf.core.api.model.BlockStatus
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.InsightCardData
import ru.finassist.pf.core.api.model.LockReason
import ru.finassist.pf.core.api.model.AnalyticsPeriodsList
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.FieldErrorCodes
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.PeriodType
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.apiError
import ru.finassist.pf.mock.MockErrorOperation
import ru.finassist.pf.mock.MockCodes
import ru.finassist.pf.mock.domain.AnalyticsComposer
import ru.finassist.pf.mock.domain.withAnalyticsHelp

class MockAnalyticsApi(private val backend: MockBackend) : AnalyticsApi {
    private val chartPages = ru.finassist.pf.mock.domain.MockChartPages(backend)

    override suspend fun getAnalytics(period: PeriodTypeCode?, date: PeriodKey?, transferMode: TransferMode?, supports: String?, exp: String?): Analytics {
        val supported = try {
            AnalyticsComposer.parseSupports(supports)
        } catch (e: IllegalArgumentException) {
            throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400,
                details = listOf(AppError.FieldError("supports", FieldErrorCodes.INVALID_FORMAT, "supports must contain type@positive_int32")))
        }
        backend.simulateNetwork()
        backend.requireSession()
        if (period == PeriodTypeCode.UNKNOWN || transferMode == TransferMode.UNKNOWN) throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400)
        if (date != null && !PeriodKey.isValid(date.value)) throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400,
            details = if (period?.isRolling == true) listOf(AppError.FieldError("date", FieldErrorCodes.INVALID_FORMAT, "date must match the singleton period")) else emptyList())
        val selectedPeriod = period ?: PeriodTypeCode.MONTH
        val singleton = selectedPeriod.takeIf { it.isRolling || it == PeriodTypeCode.ALL_TIME }?.code
        val wrongSingleton = date != null && if (singleton != null) date.value != singleton else !date.isCalendar
        if (wrongSingleton) throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400,
            details = listOf(AppError.FieldError("date", FieldErrorCodes.INVALID_FORMAT, "date must match the singleton period")))
        val coarserDate = date != null && singleton == null && (when (selectedPeriod) {
            PeriodTypeCode.MONTH -> date.type != PeriodType.MONTH
            PeriodTypeCode.QUARTER -> date.type == PeriodType.YEAR
            else -> false
        })
        if (coarserDate) throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400,
            details = listOf(AppError.FieldError("date", FieldErrorCodes.INVALID_FORMAT, "date must be exact or finer than period")))
        val pending = backend.mutex.withLock {
            val remaining = backend.config.analyticsPendingResponses
            if (remaining <= 0) false else {
                backend.config = backend.config.copy(analyticsPendingResponses = remaining - 1)
                true
            }
        }
        if (pending) return Analytics(hasData = false, calculation = backend.calculation.state().copy(recalculating = true))
        return backend.mutex.withLock {
            if (backend.calculation.pending && !backend.deferAnalyticsCalculation && backend.config.analyticsRecalculatingResponses == 0) backend.calculation.calculateNow()
            val snapshot = backend.analytics.analytics(period, date, transferMode ?: TransferMode.WITH)
            if (snapshot.hasData && backend.calculation.state().calcVersion == 0) backend.calculation.calculateNow()
            val published = backend.regularSnapshots.forRead(snapshot, backend.profile.userId, backend.calculation.state().calcVersion)
            val composed = AnalyticsComposer.compose(
                published, supports = supported, experiments = AnalyticsComposer.parseExperiments(exp),
                failing = backend.config.failingBlocks,
                chartPage = snapshot.historyChart?.let { chartPages.info(transferMode ?: TransferMode.WITH, it) }
                    ?: ru.finassist.pf.core.api.model.ChartPageInfo("empty"),
            )
            val remaining = backend.config.analyticsRecalculatingResponses
            if (remaining > 0) backend.config = backend.config.copy(analyticsRecalculatingResponses = remaining - 1)
            val calculation = backend.calculation.state().let { if (remaining > 0) it.copy(recalculating = true) else it }
            val marked = composed.copy(timeZone = composed.timeZone?.let { if (backend.config.unsupportedResponseTimeZone) "Area/Future_City" else it }, calculation = calculation,
                state = composed.state?.copy(recalculating = calculation.recalculating))
            val response = marked.withAnalyticsHelp(backend.config.analyticsHelp)
            if (backend.config.unknownCodes && response.hasData) withUnknownCodes(response) else response
        }
    }

    /**
     * What a newer server may send (FIN-83, `MockConfig.unknownCodes`): a block of a type the client cannot draw and a card
     * locked for a reason it does not know. The fake server builds Kotlin objects, so the unknown codes arrive as UNKNOWN.
     */
    private fun withUnknownCodes(a: Analytics): Analytics {
        val unknown = Block(id = "e2e_unknown", type = BlockType.UNKNOWN, v = 1, altText = MockCodes.BLOCK_UNKNOWN_TEXT)
        val locked = Block.of(BlockType.INSIGHT_CARD, InsightCardData.serializer(), InsightCardData(icon = "lock", title = "Сравнение банков")).copy(
            id = "e2e_locked", status = BlockStatus.LOCKED,
            lock = BlockLock(LockReason.UNKNOWN, MockCodes.LOCK_UNKNOWN_TEXT),
        )
        val blocks = a.blocks.toMutableList()
        val at = (blocks.indexOfFirst { it.id == "tiles" } + 1).coerceAtLeast(0)
        blocks.addAll(at, listOf(unknown, locked))
        return a.copy(blocks = blocks)
    }

    override suspend fun listPeriods(period: PeriodTypeCode?): AnalyticsPeriodsList {
        backend.simulateNetwork()
        backend.requireSession()
        if (period == PeriodTypeCode.UNKNOWN) throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400)
        return backend.mutex.withLock { backend.analytics.periods(period ?: PeriodTypeCode.MONTH).let { it.copy(timeZone = (if (backend.config.unsupportedResponseTimeZone) "Area/Future_City" else backend.profileZone().id).takeIf { _ -> it.periods.isNotEmpty() }) } }
    }

    override suspend fun dismissRegularPayment(id: String, date: PeriodKey, transferMode: TransferMode): AnalyticsBlockUpdate {
        backend.uiEvidence.dismiss()
        backend.simulateNetwork()
        backend.requireSession()
        if (!date.isCalendar || date.type != PeriodType.MONTH || !PeriodKey.isValid(date.value)) invalidField("date")
        if (transferMode == TransferMode.UNKNOWN) invalidField("transfer_mode")
        backend.mutex.withLock { requirePayment(id) }
        backend.maybeInjectError(MockErrorOperation.ANALYTICS_DISMISS)
        val update = backend.mutex.withLock {
            requirePayment(id)
            val before = backend.analytics.analytics(PeriodTypeCode.MONTH, date, transferMode)
            if (!before.hasData || before.period?.coverage == ru.finassist.pf.core.api.model.Coverage.NO_DATA) {
                if (id in backend.dismissedPayments) throw backend.apiError("ANALYTICS_CONTEXT_UNAVAILABLE", 422)
                throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400,
                    details = listOf(AppError.FieldError("date", FieldErrorCodes.OUT_OF_RANGE, "Period unavailable")))
            }
            val target = backend.calculation.dismiss(id)
            backend.dismissedPayments += id
            val response = AnalyticsComposer.compose(backend.analytics.analytics(PeriodTypeCode.MONTH, date, transferMode))
            AnalyticsBlockUpdate(response.params!!, response.currency, if (backend.config.unsupportedResponseTimeZone) "Area/Future_City" else response.timeZone!!, target,
                response.blocks.filter { it.id == "regular_payments" })
        }
        // The assignment and target survive an unavailable response composer, as with the real PUT.
        backend.maybeInjectError(MockErrorOperation.ANALYTICS_DISMISS, afterCommit = true)
        return update
    }

    override suspend fun getMonthlyChartPage(cursor: String): AnalyticsChartPage {
        backend.simulateNetwork()
        backend.requireSession()
        return backend.mutex.withLock {
            val request = chartPages.resolve(cursor)
            val page = backend.analytics.historyPage(request.mode, index = request.index)
            val snapshot = backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey(page.chart.points!!.last().month), request.mode)
                .copy(historyChart = page)
            val composed = AnalyticsComposer.compose(snapshot, chartPage = chartPages.info(request.mode, page))
            val block = composed.blocks.single { it.id == "monthly_chart" }
            // Page responses carry no selection-specific notes/highlight.
            val data = block.decode(ChartData.serializer())!!.copy(notes = emptyList(), highlightIndex = null)
            AnalyticsChartPage(block.copy(data = ApiJson.encodeToJsonElement(ChartData.serializer(), data).jsonObject), if (backend.config.unsupportedResponseTimeZone) "Area/Future_City" else backend.profileZone().id)
        }
    }

    private fun invalidField(field: String): Nothing = throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400,
        details = listOf(AppError.FieldError(field, FieldErrorCodes.INVALID_FORMAT, "Invalid selection")))

    override suspend fun restoreRegularPayment(id: String) {
        backend.simulateNetwork()
        backend.requireSession()
        requirePayment(id)
        backend.mutex.withLock { backend.dismissedPayments -= id; backend.calculation.restore(id) }
    }

    /**
     * api.md 6.3: `{id}` is a canonical lowercase UUID, anything else is 400 before the payment is looked up (no
     * operation description in the URL); an id no payment has is 404, for the dismissal and its undo alike.
     */
    private fun requirePayment(id: String) {
        if (!PAYMENT_ID.matches(id)) {
            throw backend.apiError(
                ErrorCodes.VALIDATION_ERROR, 400,
                details = listOf(AppError.FieldError("id", FieldErrorCodes.INVALID_FORMAT, "must be a lowercase UUID")),
            )
        }
        if (id !in backend.dismissedPayments && backend.analytics.regularPaymentName("s_regular_$id") == null) throw backend.apiError(ErrorCodes.NOT_FOUND, 404)
    }

    private companion object {
        val PAYMENT_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
