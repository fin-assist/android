package ru.finassist.pf.core.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.Tile

class HelpMetadataTest {
    @Test
    fun parsesOnboardingAlongsideUnknownTagsAndOrdinaryHelp() {
        val block = ApiJson.decodeFromString<Block>(
            """{"type":"chart","help_text":"Graph","help_tags":["onboarding","new_tag"],"help_order":2}""",
        )
        assertEquals(listOf("onboarding", "new_tag"), block.helpTags)
        assertEquals(2, block.helpOrder)
        val manual = ApiJson.decodeFromString<Tile>("""{"id":"expenses","title":"Expenses","help_text":"Daily"}""")
        assertEquals("Daily", manual.helpText)
        assertEquals(emptyList<String>(), manual.helpTags)
        assertNull(manual.helpOrder)
    }

    @Test
    fun serializingWithoutMetadataOmitsOptionalFieldsEvenWithDefaultsEnabled() {
        val json = kotlinx.serialization.json.Json(ApiJson) { encodeDefaults = true }
        val body = json.encodeToString(Tile.serializer(), Tile("expenses", "Expenses", helpText = "Daily"))
        assertFalse(body.contains("help_tags"))
        assertFalse(body.contains("help_order"))
    }

    @Test
    fun malformedMetadataLeavesManualHelpAndFinancialValuesAvailable() {
        for (invalid in listOf("0", "-1", "2147483648", "1.5", "\"1\"", "null", "{}")) {
            val tile = ApiJson.decodeFromString<Tile>(
                """{"id":"expenses","title":"Expenses","value":250,"help_text":"Daily",
                    "help_tags":[{},"onboarding"],"help_order":$invalid}""",
            )
            assertNull(tile.helpOrder)
            assertEquals("Daily", tile.helpText)
            assertEquals(250.0, tile.value)
            assertEquals(listOf("onboarding"), tile.helpTags)
        }
    }
}
