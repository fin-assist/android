package ru.finassist.pf.mock.api

import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.api.AnalyticsApi
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsPeriodsList
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.mock.MockBackend

class MockAnalyticsApi(private val backend: MockBackend) : AnalyticsApi {

    override suspend fun getAnalytics(period: PeriodTypeCode?, date: PeriodKey?, transferMode: TransferMode?): Analytics {
        backend.simulateNetwork()
        backend.requireSession()
        if (period == PeriodTypeCode.UNKNOWN || transferMode == TransferMode.UNKNOWN) throw AppError.Api(ErrorCodes.VALIDATION_ERROR, 400)
        if (date != null && !PeriodKey.isValid(date.value)) throw AppError.Api(ErrorCodes.VALIDATION_ERROR, 400)
        return backend.mutex.withLock { backend.analytics.analytics(period, date, transferMode ?: TransferMode.WITH) }
    }

    override suspend fun listPeriods(period: PeriodTypeCode?): AnalyticsPeriodsList {
        backend.simulateNetwork()
        backend.requireSession()
        return backend.mutex.withLock { backend.analytics.periods(period ?: PeriodTypeCode.MONTH) }
    }

    override suspend fun dismissRegularPayment(id: String) {
        backend.simulateNetwork()
        backend.requireSession()
        if (backend.analytics.regularPaymentName("s_regular_$id") == null) throw AppError.Api(ErrorCodes.NOT_FOUND, 404)
        backend.mutex.withLock { backend.dismissedPayments += id }
    }

    override suspend fun restoreRegularPayment(id: String) {
        backend.simulateNetwork()
        backend.requireSession()
        backend.mutex.withLock { backend.dismissedPayments -= id }
    }
}
