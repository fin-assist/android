package ru.finassist.pf.core.navigation

import kotlinx.serialization.Serializable

/**
 * `operations_filter` of the contract as a route argument: every field optional, strings on the wire
 * (ISO date-times, open codes) so the argument never breaks on a new server value.
 */
@Serializable
data class OperationsFilter(
    val from: String? = null,
    val to: String? = null,
    val q: String? = null,
    val categoryId: String? = null,
    val kind: String? = null,
    val amountFrom: Long? = null,
    val amountTo: Long? = null,
    val transferMode: String? = null,
    val selection: String? = null,
    val selectionName: String? = null,
) {
    val isEmpty: Boolean get() = this == OperationsFilter()
}

/** `analytics_params` of the contract as a route argument. */
@Serializable
data class AnalyticsParams(val period: String = "month", val date: String? = null, val transferMode: String = "with")
