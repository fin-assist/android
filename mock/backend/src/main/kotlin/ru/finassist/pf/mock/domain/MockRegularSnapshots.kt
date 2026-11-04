package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.AnalyticsParams

/** Published regular metrics for GET; a fresh mutation response never writes this projection. */
internal class MockRegularSnapshots {
    private var context: Triple<String, String, Int>? = null
    private val metrics = linkedMapOf<AnalyticsParams, RegularPaymentsCard>()

    /** Called under the backend mutex. Final calculation, source-zone and owner changes retire old metrics. */
    fun forRead(snapshot: AnalyticsSnapshot, owner: String, appliedVersion: Int): AnalyticsSnapshot {
        if (!snapshot.hasData) { clear(); return snapshot }
        val next = Triple(owner, snapshot.timeZone, appliedVersion)
        if (context != next) { clear(); context = next }
        val params = snapshot.params ?: return snapshot
        val insights = snapshot.insights ?: return snapshot
        val regular = metrics[params] ?: insights.regularPayments.also {
            metrics[params] = it
            if (metrics.size > 32) metrics.remove(metrics.keys.first())
        }
        return snapshot.copy(insights = insights.copy(regularPayments = regular))
    }

    fun clear() { metrics.clear(); context = null }
}
