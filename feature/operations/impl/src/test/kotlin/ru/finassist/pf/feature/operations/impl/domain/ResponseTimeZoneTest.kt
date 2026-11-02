package ru.finassist.pf.feature.operations.impl.domain

import org.junit.Test
import ru.finassist.pf.core.api.model.OperationsList
import ru.finassist.pf.core.api.model.FeedState
import ru.finassist.pf.core.common.error.AppError
import java.time.OffsetDateTime
import kotlin.test.*

class ResponseTimeZoneTest {
    @Test fun `empty undated list does not require zone but dated state does`() {
        assertNull(OperationsList(emptyList()).sourceZone())
        assertNull(OperationsList(emptyList(), timeZone = "Area/Future_City").sourceZone())
        val dated = OperationsList(emptyList(), state = FeedState(stale = true, dataTo = OffsetDateTime.parse("2026-10-01T00:00:00Z")))
        assertFailsWith<AppError.Server> { dated.sourceZone() }
        assertEquals("Asia/Vladivostok", dated.copy(timeZone = "Asia/Vladivostok").sourceZone()!!.id)
        assertFailsWith<AppError.Api> { dated.copy(timeZone = "Area/Future_City").sourceZone() }
    }
}
