package ru.finassist.pf.feature.analytics.impl.domain

import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsParams

/** ViewModel-owned ready responses; a recalculating response never becomes a financial snapshot. */
internal class AnalyticsSnapshots {
    private val ready = linkedMapOf<AnalyticsParams, Analytics>()

    fun display(response: Analytics, requested: AnalyticsParams?): Analytics? {
        val key = response.params ?: requested
        if (response.state?.recalculating == true) return ready[key]
        if (!response.hasData) {
            // A confirmed empty ledger invalidates snapshots of all periods.
            ready.clear()
        } else if (response.state?.recalculating == false && response.params?.isValidSelection == true) {
            val key = response.params!!
            ready.remove(key)
            ready[key] = response
            if (ready.size > MAX_SNAPSHOTS) ready.remove(ready.keys.first())
        }
        return response
    }

    fun clear() = ready.clear()

    private companion object { const val MAX_SNAPSHOTS = 32 }
}
