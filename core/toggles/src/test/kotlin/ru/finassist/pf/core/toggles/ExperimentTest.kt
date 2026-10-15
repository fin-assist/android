package ru.finassist.pf.core.toggles

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExperimentTest {
    @Test fun `a variant outside the list is the default one, a provider without variants gives the default`() {
        assertEquals("b", Experiment.ANALYTICS_ORDER.variantOf("b"))
        assertEquals("a", Experiment.ANALYTICS_ORDER.variantOf("z"))
        assertEquals("a", Experiment.ANALYTICS_ORDER.variantOf(null))
        assertEquals("a", StaticFlags().variant(Experiment.ANALYTICS_ORDER))
        assertEquals(Experiment.ANALYTICS_ORDER, Experiment.byKey("analytics_order"))
        assertNull(Experiment.byKey("nope"))
    }
}
