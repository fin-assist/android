package ru.finassist.pf.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverageTest {

    @Test
    fun `unitTest runs the test task of the coverage variant of each module kind`() {
        assertEquals("test", coverageTestTask("org.jetbrains.kotlin.jvm"))
        assertEquals("testDebugUnitTest", coverageTestTask("com.android.library"))
        assertEquals("testMockDebugUnitTest", coverageTestTask("com.android.application"))
    }

    @Test
    fun `every plugin the convention reacts to has a test task`() {
        COVERAGE_TEST_PLUGINS.forEach { id -> assertEquals(id, true, coverageTestTask(id) != null) }
    }

    @Test
    fun `other plugins add no test task`() {
        assertNull(coverageTestTask("org.jetbrains.kotlin.android"))
        assertNull(coverageTestTask("com.google.dagger.hilt.android"))
    }
}
