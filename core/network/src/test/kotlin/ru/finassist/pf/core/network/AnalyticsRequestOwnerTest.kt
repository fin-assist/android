package ru.finassist.pf.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.PeriodKey
import kotlin.test.*

class AnalyticsRequestOwnerTest {
    @Test fun `every analytics route and its quota read reject a replaced login before sending`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val stack = NetworkStack(server, InMemoryTokenStore(TokenStore.Session("u1", TokenPair("new", "refresh"), authSessionEpoch = "login-2")))
            val api = stack.api
            val actions = listOf<suspend () -> Unit>(
                { api.analytics.getAnalytics(null, null, null) },
                { api.analytics.listPeriods(null) },
                { api.analytics.getMonthlyChartPage("opaque") },
                { api.analytics.dismissRegularPayment("id", PeriodKey("2026-09"), TransferMode.WITH) },
                { api.analytics.restoreRegularPayment("id") },
                { api.assistant.getLimit() },
            )
            for (action in actions) assertFailsWith<AppError.Offline> { withContext(RequestOwner("u1", "login-1")) { action() } }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `page cursor is opaque encoded once and page source zone is preserved`() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val stack = NetworkStack(server, InMemoryTokenStore(TokenStore.Session("u1", TokenPair("access", "refresh"), authSessionEpoch = "login-1")))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"time_zone":"Asia/Vladivostok","block":{"id":"monthly_chart","type":"chart","v":1,"status":"ready","data":{"kind":"bar","value_kind":"amount","points":[],"page":{"revision":"opaque-revision"}}}}"""))
            val cursor = "opaque +/=&? token"
            val response = withContext(RequestOwner("u1", "login-1")) { stack.api.analytics.getMonthlyChartPage(cursor) }
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/analytics/charts/monthly_chart", request.requestUrl!!.encodedPath)
            assertEquals(setOf("cursor"), request.requestUrl!!.queryParameterNames)
            assertEquals(cursor, request.requestUrl!!.queryParameter("cursor"))
            assertEquals("Bearer access", request.getHeader("Authorization"))
            assertEquals("Asia/Vladivostok", response.timeZone)
        }
    }
}
