package ru.finassist.pf.core.toggles

/**
 * Registry of feature flags. The key is what the remote provider uses; defaults apply when the provider is
 * unreachable or does not know the key. Keep in sync with docs/flags.md.
 */
enum class Flag(val key: String, val defaultValue: Boolean = true) {
    /** New phone number after the call: registration screen vs "Регистрация пока закрыта". */
    AUTH_REGISTRATION("auth.registration"),

    ANALYTICS_BLOCK_TILES("analytics.block.tiles"),
    ANALYTICS_BLOCK_EXPENSE_CATEGORIES("analytics.block.expense_categories"),
    ANALYTICS_BLOCK_INCOME_CATEGORIES("analytics.block.income_categories"),
    ANALYTICS_BLOCK_MONTHLY_CHART("analytics.block.monthly_chart"),
    ANALYTICS_BLOCK_REGULAR_PAYMENTS("analytics.block.regular_payments"),
    ANALYTICS_BLOCK_NOTABLE_SPENDING("analytics.block.notable_spending"),
    ANALYTICS_BLOCK_BANK_FEES("analytics.block.bank_fees"),
    ANALYTICS_BLOCK_SMALL_FREQUENT("analytics.block.small_frequent"),
    ANALYTICS_FILTER_TRANSFERS("analytics.filter.transfers"),

    /** Assistant screen and every link to it (card on Analytics, profile row, consent switch, answer chips). */
    ASSISTANT("assistant"),

    SEARCH_FILTER_PERIOD("search.filter.period"),
    SEARCH_FILTER_CATEGORY("search.filter.category"),
    SEARCH_FILTER_AMOUNT("search.filter.amount"),
    SEARCH_FILTER_KIND("search.filter.kind"),

    /** Upload screen and every link to it (empty states, "Загрузить новую выписку", profile). */
    STATEMENTS_UPLOAD("statements.upload"),

    PROFILE_DELETE_ACCOUNT("profile.delete_account"),
    ;

    companion object {
        fun byKey(key: String): Flag? = entries.firstOrNull { it.key == key }
    }
}
