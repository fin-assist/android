package ru.finassist.pf.mock

import kotlinx.coroutines.runBlocking
import org.junit.Test
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.api.MockAnalyticsApi
import ru.finassist.pf.mock.api.MockOperationsApi
import ru.finassist.pf.core.api.model.OperationsQuery
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Analytics endpoints (api.md 6): parameter validation, defaults, periods, dismissing regular payments. */
class MockAnalyticsApiTest {
    /** June — September, a subscription on the 3rd of every month and some groceries. */
    private val backend = testBackend().apply {
        load(
            statement(
                "2026-06-01", "2026-09-30",
                *(6..9).flatMap { m ->
                    listOf(
                        trn("sub$m", "2026-%02d-03T09:00".format(m), 499, "Netflix", "Онлайн-кинотеатры"),
                        trn("food$m", "2026-%02d-12T19:00".format(m), 3_000L + m, "Пятёрочка", "Супермаркеты"),
                    )
                }.toTypedArray(),
            ),
        )
    }
    private val api = MockAnalyticsApi(backend)

    private suspend fun regularTitles() =
        backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH).insights!!.regularPayments.items.orEmpty().map { it.title }

    @Test
    fun `without parameters the latest month with transfers is shown`() = runBlocking<Unit> {
        val analytics = api.getAnalytics(null, null, null)
        assertTrue(analytics.hasData)
        val params = assertNotNull(analytics.params)
        assertEquals(PeriodTypeCode.MONTH, params.period)
        assertEquals(PeriodKey("2026-09"), params.date)
        assertEquals(TransferMode.WITH, params.transferMode)
    }

    @Test
    fun `an unknown period type, transfer mode or a malformed key is a VALIDATION_ERROR`() = runBlocking<Unit> {
        val calls = listOf<suspend () -> Unit>(
            { api.getAnalytics(PeriodTypeCode.UNKNOWN, null, null) },
            { api.getAnalytics(null, null, TransferMode.UNKNOWN) },
            { api.getAnalytics(PeriodTypeCode.MONTH, PeriodKey("2026-13-01"), null) },
        )
        for (call in calls) {
            val e = assertFailsWith<AppError.Api> { call() }
            assertEquals(ErrorCodes.VALIDATION_ERROR to 400, e.code to e.httpStatus)
        }
    }

    @Test
    fun `periods with data are listed newest first with their coverage`() = runBlocking<Unit> {
        val months = api.listPeriods(null).periods
        assertEquals(listOf("2026-09", "2026-08", "2026-07", "2026-06"), months.map { it.key.value })
        assertTrue(months.all { it.coverage == Coverage.COMPLETE })
        val quarters = api.listPeriods(PeriodTypeCode.QUARTER).periods
        assertEquals(listOf("2026-Q3", "2026-Q2"), quarters.map { it.key.value })
        // Q2 has data for June only.
        assertEquals(listOf(Coverage.COMPLETE, Coverage.PARTIAL), quarters.map { it.coverage })
    }

    @Test
    fun `a dismissed regular payment leaves the card until it is restored`() = runBlocking<Unit> {
        val card = backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH).insights!!.regularPayments
        val netflix = card.items.orEmpty().single { it.title == "Netflix" }
        assertEquals(49_900L, netflix.monthlyAmount.minor)
        assertEquals("3-го числа", netflix.scheduleName)

        // Its selection finds the payments it was computed from.
        val found = MockOperationsApi(backend).listOperations(OperationsQuery(netflix.filters))
        assertEquals(listOf("Netflix"), found.items.map { it.title })
        val all = MockOperationsApi(backend).listOperations(OperationsQuery(assertNotNull(card.filters)))
        assertEquals(card.items.orEmpty().map { it.title }.toSet(), all.items.map { it.title }.toSet())

        api.dismissRegularPayment(netflix.id)
        assertFalse("Netflix" in regularTitles())
        // Restoring twice is harmless.
        api.restoreRegularPayment(netflix.id)
        api.restoreRegularPayment(netflix.id)
        assertTrue("Netflix" in regularTitles())
    }

    @Test
    fun `dismissing or restoring an unknown regular payment is NOT_FOUND`() = runBlocking<Unit> {
        val unknown = "0b9d5c2e-6f1a-4c3b-9a7e-2d4f6b8c0e1a"
        val dismiss = assertFailsWith<AppError.Api> { api.dismissRegularPayment(unknown) }
        assertEquals(ErrorCodes.NOT_FOUND to 404, dismiss.code to dismiss.httpStatus)
        val restore = assertFailsWith<AppError.Api> { api.restoreRegularPayment(unknown) }
        assertEquals(ErrorCodes.NOT_FOUND to 404, restore.code to restore.httpStatus)
        assertTrue(backend.dismissedPayments.isEmpty())
    }

    @Test
    fun `a payment id that is not a lowercase UUID is a VALIDATION_ERROR before the lookup (api md 6_3)`() = runBlocking<Unit> {
        val netflix = backend.analytics.analytics(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH).insights!!
            .regularPayments.items.orEmpty().single { it.title == "Netflix" }.id
        for (id in listOf("rp_unknown_merchant", netflix.uppercase(), "Netflix", "")) {
            for (call in listOf<suspend () -> Unit>({ api.dismissRegularPayment(id) }, { api.restoreRegularPayment(id) })) {
                val e = assertFailsWith<AppError.Api>(id) { call() }
                assertEquals(ErrorCodes.VALIDATION_ERROR to 400, e.code to e.httpStatus, id)
                assertEquals(listOf("id"), e.details.map { it.field }, id)
            }
        }
        assertTrue(backend.dismissedPayments.isEmpty())
    }

    @Test
    fun `an empty ledger has no data`() = runBlocking<Unit> {
        val empty = MockAnalyticsApi(testBackend())
        assertFalse(empty.getAnalytics(null, null, null).hasData)
        assertTrue(empty.listPeriods(PeriodTypeCode.YEAR).periods.isEmpty())
    }

    @Test
    fun `analytics needs a session`() = runBlocking<Unit> {
        val signedOut = MockAnalyticsApi(testBackend(userId = ""))
        assertFailsWith<AppError.Unauthorized> { signedOut.getAnalytics(null, null, null) }
        assertFailsWith<AppError.Unauthorized> { signedOut.listPeriods(null) }
        assertFailsWith<AppError.Unauthorized> { signedOut.dismissRegularPayment("rp_x") }
        assertFailsWith<AppError.Unauthorized> { signedOut.restoreRegularPayment("rp_x") }
    }
}
