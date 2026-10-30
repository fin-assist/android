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
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StageControlStatusTest {
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
