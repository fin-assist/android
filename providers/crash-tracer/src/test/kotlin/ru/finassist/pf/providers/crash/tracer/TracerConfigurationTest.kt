package ru.finassist.pf.providers.crash.tracer

import ru.ok.tracer.CoreTracerConfiguration
import ru.ok.tracer.crash.report.CrashFreeConfiguration
import ru.ok.tracer.crash.report.CrashReportConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals

class TracerConfigurationTest {
    @Test fun `core, crash report and crash-free, once each`() {
        val types = tracerConfiguration(debugUpload = false).map { it::class }
        assertEquals(listOf(CoreTracerConfiguration::class, CrashReportConfiguration::class, CrashFreeConfiguration::class), types)
    }
}
