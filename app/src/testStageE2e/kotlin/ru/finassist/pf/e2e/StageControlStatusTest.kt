package ru.finassist.pf.e2e

import android.app.Application
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import ru.finassist.pf.core.common.error.AppError
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StageControlStatusTest {
    @Test
    fun `offline diagnostics distinguish closed transport causes without exporting sensitive messages`() {
        val context = RuntimeEnvironment.getApplication()
        val status = StageControlStatus(context, "11111111-2222-4333-8444-555555555555", StageControlOperation.SETUP)
        val file = File(context.filesDir, "stage-control-status.json")
        val sensitive = "Bearer secret-token +70000001234 https://private.invalid?token=private-token"
        val failures = listOf(
            AppError.Offline(UnknownHostException(sensitive)) to "offline_dns",
            AppError.Offline(SocketTimeoutException(sensitive)) to "offline_timeout",
            AppError.Offline(SSLException(sensitive)) to "offline_tls",
            AppError.Offline(ConnectException(sensitive)) to "offline_connection",
            AppError.Offline(IOException(sensitive, UnknownHostException(sensitive))) to "offline_dns",
            AppError.Offline(IOException(sensitive)) to "offline",
            AppError.Offline() to "offline",
        )
        for ((failure, expected) in failures) {
            assertEquals(expected, StageControlStatus.errorClass(failure))
            status.write(StageBootstrapPhase.DOCUMENT, StageControlOutcome.FAILED, failure)
            val raw = file.readText()
            assertEquals(expected, Json.parseToJsonElement(raw).jsonObject.getValue("error_class").jsonPrimitive.content)
            assertFalse(raw.contains("secret-token"))
            assertFalse(raw.contains("private-token"))
            assertFalse(raw.contains("+70000001234"))
            assertFalse(raw.contains("https://"))
        }
    }

    @Test
    fun `unrecognized cyclic causes retain generic offline classification`() {
        val first = IOException("private first")
        val second = IOException("private second")
        first.initCause(second)
        second.initCause(first)
        assertEquals("offline", StageControlStatus.errorClass(AppError.Offline(first)))
    }

    @Test
    fun `phase artifact records in-flight boundary and only closed safe failure codes`() {
        val context = RuntimeEnvironment.getApplication()
        val status = StageControlStatus(context, "11111111-2222-4333-8444-555555555555", StageControlOperation.SETUP)
        val file = File(context.filesDir, "stage-control-status.json")
        status.write(StageBootstrapPhase.DOCUMENT, StageControlOutcome.STARTED)
        val pending = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("document", pending.getValue("phase").jsonPrimitive.content)
        assertEquals("started", pending.getValue("outcome").jsonPrimitive.content)
        assertFalse("error_class" in pending)
        status.write(StageBootstrapPhase.DOCUMENT, StageControlOutcome.FAILED,
            IOException("Bearer secret +70000001234 https://private.invalid?token=secret"))
        val raw = file.readText()
        val failed = Json.parseToJsonElement(raw).jsonObject
        assertEquals(setOf("run_id", "operation", "phase", "outcome", "error_class"), failed.keys)
        assertEquals("setup_run", failed.getValue("operation").jsonPrimitive.content)
        assertEquals("io_error", failed.getValue("error_class").jsonPrimitive.content)
        assertFalse(raw.contains("secret"))
        assertFalse(raw.contains("+7000000"))
        assertFalse(raw.contains("https://"))
        status.write(StageBootstrapPhase.DONE, StageControlOutcome.PASSED)
        val passed = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals("passed", passed.getValue("outcome").jsonPrimitive.content)
        assertFalse("error_class" in passed)
    }
}
