package ru.finassist.pf.e2e

import android.app.Application
import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StageControlObservationTest {
    private lateinit var context: Context
    private lateinit var store: StageRunStore
    private lateinit var faults: StageFaults

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.filesDir.listFiles().orEmpty().filter { it.name.startsWith("stage-") }.forEach { it.delete() }
        File(context.filesDir, "stage-run.json").writeText(Json.encodeToString(
            StageRun("11111111-2222-4333-8444-555555555555", "+70000001234"),
        ))
        store = StageRunStore(context)
        faults = StageFaults(context, store)
    }

    @Test
    fun `local application observations persist separately from parsing and cleanup`() {
        faults.configure(mapOf("offline" to "false"))
        faults.clearForCleanup()
        assertEquals(0, faults.count("flag_override"))
        assertEquals(0, faults.count("flags_reset"))
        assertEquals(0, faults.count("pending_upload_seed"))

        repeat(4) { faults.flagOverrideApplied() }
        faults.flagsResetApplied()
        faults.pendingUploadSeeded()
        faults.clearForCleanup()

        val restored = StageFaults(context, store)
        assertEquals(4, restored.count("flag_override"))
        assertEquals(1, restored.count("flags_reset"))
        assertEquals(1, restored.count("pending_upload_seed"))
        assertEquals(0, restored.count("real_requests"))
        val text = File(context.filesDir, "stage-evidence.json").readText()
        assertTrue(Json.parseToJsonElement(text).jsonObject.getValue("has_client_injections").jsonPrimitive.boolean)
        assertFalse(text.contains("+70000001234"))
        assertFalse(text.contains("1234\""))
        assertFalse(text.contains("token"))
    }
}
