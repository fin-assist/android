package ru.finassist.pf.feature.statements.impl.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfxEnvelopeTest {
    @Test fun `recognizes bare OFX, legacy headers and XML declarations`() {
        listOf(
            "<OFX><BANKMSGSRSV1>",
            "<OFX xmlns=\"urn:ofx\">",
            "OFXHEADER:100\r\nDATA:OFXSGML\r\n\r\n<OFX>",
            "\uFEFF<?xml version=\"1.0\"?>\n<?OFX OFXHEADER=\"200\"?>\n<!-- statement -->\n<OFX>",
        ).forEach { assertTrue(it, hasOfxEnvelope(it.toByteArray())) }
    }

    @Test fun `rejects unrelated downloads and incomplete or overlong preambles`() {
        listOf("", "text mentions <OFX>", "<OFXOther>", "<OFX ", "<OFX <csv>", "<?xml version=\"1.0\"?><csv>",
            "OFXHEADER:100\r\n", "<!-- unfinished", " ".repeat(9 * 1024) + "<OFX>",
        ).forEach { assertFalse(hasOfxEnvelope(it.toByteArray())) }
    }
}
