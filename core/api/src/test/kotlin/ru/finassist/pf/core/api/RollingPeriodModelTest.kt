package ru.finassist.pf.core.api

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.PeriodKey

class RollingPeriodModelTest {
    @Test fun `known rolling codes decode with exact keys while newer codes stay unknown`() {
        for ((code, type) in listOf("rolling_year" to PeriodTypeCode.ROLLING_YEAR, "rolling_quarter" to PeriodTypeCode.ROLLING_QUARTER)) {
            val params = ApiJson.decodeFromString(AnalyticsParams.serializer(), """{"period":"$code","date":"$code","transfer_mode":"without"}""")
            assertEquals(type, params.period)
            assertEquals(PeriodKey(code), params.date)
            assertEquals(TransferMode.WITHOUT, params.transferMode)
        }
        assertEquals(PeriodTypeCode.UNKNOWN, ApiJson.decodeFromString(PeriodTypeCode.serializer(), "\"rolling_week\""))
    }
}
