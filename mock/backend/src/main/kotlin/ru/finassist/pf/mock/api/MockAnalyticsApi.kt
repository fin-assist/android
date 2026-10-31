package ru.finassist.pf.mock.api

import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.AnalyticsApi
import ru.finassist.pf.core.api.model.Analytics
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
import ru.finassist.pf.mock.MockCodes
import ru.finassist.pf.mock.domain.AnalyticsComposer

class MockAnalyticsApi(private val backend: MockBackend) : AnalyticsApi {

    override suspend fun getAnalytics(period: PeriodTypeCode?, date: PeriodKey?, transferMode: TransferMode?, supports: String?, exp: String?): Analytics {
        backend.simulateNetwork()
        backend.requireSession()
        if (period == PeriodTypeCode.UNKNOWN || transferMode == TransferMode.UNKNOWN) throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400)
        if (date != null && !PeriodKey.isValid(date.value)) throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400)
        val coarserDate = date != null && when (period ?: PeriodTypeCode.MONTH) {
            PeriodTypeCode.MONTH -> date.type != PeriodType.MONTH
            PeriodTypeCode.QUARTER -> date.type == PeriodType.YEAR
            else -> false
        }
        if (coarserDate) throw backend.apiError(ErrorCodes.VALIDATION_ERROR, 400,
            details = listOf(AppError.FieldError("date", FieldErrorCodes.INVALID_FORMAT, "date must be exact or finer than period")))
        val pending = backend.mutex.withLock {
            val remaining = backend.config.analyticsPendingResponses
            if (remaining <= 0) false else {
                backend.config = backend.config.copy(analyticsPendingResponses = remaining - 1)
                true
            }
        }
        if (pending) return Analytics(hasData = false)
        val snapshot = backend.mutex.withLock { backend.analytics.analytics(period, date, transferMode ?: TransferMode.WITH) }
        val composed = AnalyticsComposer.compose(
            snapshot,
            supports = AnalyticsComposer.parseSupports(supports),
            experiments = AnalyticsComposer.parseExperiments(exp),
            failing = backend.config.failingBlocks,
        )
        return if (backend.config.unknownCodes && composed.hasData) withUnknownCodes(composed) else composed
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
        return backend.mutex.withLock { backend.analytics.periods(period ?: PeriodTypeCode.MONTH) }
    }

    override suspend fun dismissRegularPayment(id: String) {
        backend.simulateNetwork()
        backend.requireSession()
        requirePayment(id)
        backend.mutex.withLock { backend.dismissedPayments += id }
    }

    override suspend fun restoreRegularPayment(id: String) {
        backend.simulateNetwork()
        backend.requireSession()
        requirePayment(id)
        backend.mutex.withLock { backend.dismissedPayments -= id }
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
        if (backend.analytics.regularPaymentName("s_regular_$id") == null) throw backend.apiError(ErrorCodes.NOT_FOUND, 404)
    }

    private companion object {
        val PAYMENT_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    }
}
