package ru.finassist.pf.feature.analytics.impl.ui

import org.junit.Test
import ru.finassist.pf.core.blocks.CatalogV1
import kotlin.test.assertEquals

class AnalyticsGoldenCapabilitiesTest {
    @Test fun `server golden requests use the capabilities advertised by the actual renderer catalog`() {
        val advertised = javaClass.getResource("/analytics/supports.txt")!!.readText().trim()
        assertEquals(CatalogV1.registry().supports, advertised)
    }
}
