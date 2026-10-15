package ru.finassist.pf.core.toggles

/**
 * Experiments over server-composed screens (FIN-9): the variant comes from the same provider as the flags (RuStore
 * Remote Config, a string under [key]) and goes to the server with the request (api.md 6.1 `exp`) — the server
 * composes the screen, so it has to know the variant. A value outside [variants] is [defaultVariant]. Registry of keys:
 * docs/flags.md, «Experiments».
 */
enum class Experiment(val key: String, val variants: List<String>, val defaultVariant: String = variants.first()) {
    /** Order of the Analytics blocks: `a` — as on the mockups, `b` — the categories first. */
    ANALYTICS_ORDER("analytics_order", listOf("a", "b")),
    ;

    /** [raw] if it is one of [variants], else [defaultVariant]. */
    fun variantOf(raw: String?): String = raw?.takeIf { it in variants } ?: defaultVariant

    companion object {
        fun byKey(key: String): Experiment? = entries.firstOrNull { it.key == key }
    }
}
