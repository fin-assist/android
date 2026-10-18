package ru.finassist.pf.mock.calc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.math.BigDecimal
import java.time.OffsetDateTime
import kotlin.math.abs

/**
 * Comparison rules of the calculation fixtures (fin-assist/contracts `fixtures/calc/README.md`, «Правила сравнения»),
 * a port of the reference `tools/refcalc/refcalc/compare.py`. The expected responses are already redacted:
 * `<ignored>` marks what is not compared.
 */
internal object CalcCompare {
    const val IGNORED = "<ignored>"

    private val OPAQUE_KEYS = setOf("id", "replaced_id", "upload_id", "payment_id", "selection", "calculated_at")
    private val TEXT_KEYS = setOf(
        "selection_name", "type_name", "feature_name", "text", "alt_text", "empty_text", "title", "caption", "label",
        "note", "description", "badge", "sublabel", "value_suffix", "more_label", "spoken", "note_details", "source_name",
        "message",
    )
    private val DATA_TITLE_OUTSIDE_BLOCKS = setOf("title", "note", "description")
    private val RFC3339 = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$""")
    private const val FLOAT_TOLERANCE = 1e-9

    /**
     * Differences as `path: expected != actual`. Absent equals null; a block or tile without `status` is ready;
     * moments are compared as instants; fractional numbers with a tolerance; arrays in order and of the same length.
     * A field the reference does not have must be absent or null, unless it is a text or an opaque id.
     */
    fun compare(
        expected: JsonElement?,
        actual: JsonElement?,
        path: String = "$",
        inBlocks: Boolean = false,
        parentIsItems: Boolean = false,
        key: String? = null,
    ): List<String> {
        val e = expected.orNull()
        val a = actual.orNull()
        if (e is JsonPrimitive && e.isString && e.content == IGNORED) return emptyList()
        return when (e) {
            is JsonObject -> {
                if (a !is JsonObject) return listOf("$path: expected an object, got $a")
                (e.keys + a.keys).sorted().flatMap { k ->
                    val ev = e[k].orNull()
                    val av = a[k].orNull()
                    when {
                        k == "status" && ev.isReadyOrNull() && av.isReadyOrNull() -> emptyList()
                        ev == null && av == null -> emptyList()
                        k !in e && extraAllowed(k, inBlocks, parentIsItems) -> emptyList()
                        else -> compare(ev, av, "$path.$k", inBlocks || k == "blocks", false, k)
                    }
                }
            }
            is JsonArray -> {
                if (a !is JsonArray) return listOf("$path: expected an array, got $a")
                if (e.size != a.size) return listOf("$path: expected ${e.size} elements, got ${a.size}")
                e.indices.flatMap { i -> compare(e[i], a[i], "$path[$i]", inBlocks, key == "items", key) }
            }
            else -> scalar(e as JsonPrimitive?, a, path)
        }
    }

    private fun scalar(e: JsonPrimitive?, a: JsonElement?, path: String): List<String> {
        val diff = listOf("$path: $e != $a")
        if (e == null) return if (a == null) emptyList() else diff
        if (a !is JsonPrimitive) return diff
        if (e.isNumber() && a.isNumber()) {
            val fractional = e.content.any { it == '.' || it == 'e' || it == 'E' }
            val same = if (fractional) {
                abs(e.content.toDouble() - a.content.toDouble()) <= FLOAT_TOLERANCE
            } else {
                // Sums and counts are exact; 337280.0 equals 337280 (block values are JSON doubles).
                BigDecimal(e.content).compareTo(BigDecimal(a.content)) == 0
            }
            return if (same) emptyList() else diff
        }
        val me = e.moment()
        val ma = a.moment()
        if (me != null && ma != null) return if (me.toInstant() == ma.toInstant()) emptyList() else diff
        return if (e.isString == a.isString && e.content == a.content) emptyList() else diff
    }

    /** A field the reference does not have may appear only where redaction would have ignored it. */
    private fun extraAllowed(k: String, inBlocks: Boolean, parentIsItems: Boolean): Boolean = when {
        k in OPAQUE_KEYS && k != "id" -> true
        k !in TEXT_KEYS -> false
        !inBlocks && k in DATA_TITLE_OUTSIDE_BLOCKS -> false
        else -> !(inBlocks && k == "title" && parentIsItems)
    }

    private fun JsonElement?.orNull(): JsonElement? = if (this == null || this is JsonNull) null else this

    private fun JsonElement?.isReadyOrNull() = this == null || (this is JsonPrimitive && isString && content == "ready")

    private fun JsonPrimitive.isNumber() = !isString && booleanOrNull == null && content.toBigDecimalOrNull() != null

    private fun JsonPrimitive.moment(): OffsetDateTime? =
        if (isString && RFC3339.matches(content)) OffsetDateTime.parse(content) else null
}
