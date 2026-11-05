package ru.finassist.pf.e2e

import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.SystemClock
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.MockConfig
import ru.finassist.pf.mock.MockConsentScenarios
import ru.finassist.pf.mock.api.MockAuthApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class E2eConsentControlTest {
    private suspend fun withControl(test: suspend (E2eConsentControl, MockBackend) -> Unit) {
        val backend = MockBackend(MockConfig(networkDelayMs = 0), SystemClock(Clock.MOSCOW))
        backend.ready.complete(Unit)
        try { test(E2eConsentControl(backend.consents), backend) } finally { backend.scope.cancel() }
    }

    private fun command(vararg values: Pair<String, String>) =
        (mapOf("consent_type" to listOf("personal_data")) + values.associate { it.first to listOf(it.second) })

    @Test fun `publication and stale document reply are independent across commands`() = runTest {
        withControl { control, backend ->
            assertNull(control.apply(command("consent_publish" to "pd-2", "consent_document" to "pd-1")))
            assertNull(control.apply(command("consent_read" to "offline")))
            assertEquals("pd-1", backend.consents.documentSettings(ConsentType.PERSONAL_DATA).versionOverride)
            assertNull(control.apply(command("consent_read" to "ready")))
            val auth = MockAuthApi(backend)
            assertEquals("pd-1", auth.getConsentDocument(ConsentType.PERSONAL_DATA).version)
            assertNull(control.apply(command("consent_document" to "current")))
            assertEquals("pd-2", auth.getConsentDocument(ConsentType.PERSONAL_DATA).version)
        }
    }

    @Test fun `invalid or duplicate parameters cannot partially publish a document`() = runTest {
        withControl { control, backend ->
            assertNotNull(control.apply(command("consent_publish" to "pd-2", "consent_read" to "unexpected")))
            assertNotNull(control.apply(command("consent_publish" to "pd-2") + ("consent_read" to listOf("ready", "held"))))
            assertNotNull(control.apply(command("consent_publish" to "pd-2", "expect_consent_unknown" to "0")))
            assertEquals(MockBackend.CONSENT_VERSION_PD, MockAuthApi(backend).getConsentDocument(ConsentType.PERSONAL_DATA).version)
            assertEquals(MockConsentScenarios.DocumentMode.READY, backend.consents.documentSettings(ConsentType.PERSONAL_DATA).mode)
        }
    }

    @Test fun `evidence checks are exact and empty request lists mean no requests`() = runTest {
        withControl { control, backend ->
            assertNull(control.apply(command("expect_consent_reads" to "0", "expect_consent_starts" to "", "expect_consent_registers" to "")))
            MockAuthApi(backend).getConsentDocument(ConsentType.PERSONAL_DATA)
            assertNotNull(control.apply(command("expect_consent_reads" to "0")))
            assertNull(control.apply(command("expect_consent_reads" to "1", "expect_consent_active" to "0", "expect_consent_max_active" to "1")))
            assertNotNull(control.apply(command("expect_consent_starts" to "pd-1")))
            assertNotNull(control.apply(command("expect_consent_wait_elapsed" to "true")), "No 429 receipt is not an elapsed wait")
        }
    }

    @Test fun `malformed and oversized controls return bounded failure without mutating settings`() = runTest {
        withControl { control, backend ->
            assertNotNull(control.apply(command("consent_publish" to "x".repeat(65))))
            assertNotNull(control.apply(command("consent_document" to "../document")))
            assertNotNull(control.apply(command("consent_retry_s" to "-1")))
            assertNotNull(control.apply(command("expect_consent_reads" to "129")))
            assertNotNull(control.apply(command("consent_release" to "false")))
            assertNotNull(control.apply(command("expect_consent_starts" to "pd-1,")))
            assertNotNull(control.apply(mapOf("consent_publish" to listOf("pd-2"))))
            assertEquals(MockConsentScenarios.DocumentSettings(), backend.consents.documentSettings(ConsentType.PERSONAL_DATA))
        }
    }
}
