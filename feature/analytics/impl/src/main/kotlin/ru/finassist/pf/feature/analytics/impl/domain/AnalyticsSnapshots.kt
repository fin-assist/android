package ru.finassist.pf.feature.analytics.impl.domain

import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsBlockUpdate
import ru.finassist.pf.core.api.model.AnalyticsIntegrity
import ru.finassist.pf.core.api.model.AnalyticsParams

/** User-scoped, memory-only whole snapshots and the minimum version accepted across every period. */
internal class AnalyticsSnapshots {
    private val ready = linkedMapOf<AnalyticsParams, Analytics>()
    var requiredVersion: Int = 0
        private set
    private val uncertainMutations = mutableSetOf<String>()

    val hasUnresolvedMutation: Boolean get() = uncertainMutations.isNotEmpty()

    fun isWaiting(response: Analytics): Boolean = response.calculation!!.let {
        it.recalculating || it.calcVersion < requiredVersion || uncertainMutations.isNotEmpty()
    }

    fun unknownOutcome(id: String) { uncertainMutations += id }
    fun resolved(id: String) { uncertainMutations -= id }

    fun display(response: Analytics, requested: AnalyticsParams?): Analytics? {
        AnalyticsIntegrity.requireValid(response)
        val key = response.params ?: requested
        if (isWaiting(response)) return ready[key]
        requiredVersion = maxOf(requiredVersion, response.calculation!!.calcVersion)
        if (!response.hasData) {
            ready.clear()
        } else {
            if (ready.values.any { it.timeZone != response.timeZone }) ready.clear()
            val actual = response.params!!
            ready.remove(actual)
            ready[actual] = response
            if (ready.size > MAX_SNAPSHOTS) ready.remove(ready.keys.first())
        }
        return response
    }

    /** Only listed complete blocks change; timestamps, calculation and every other block remain intact. */
    fun apply(update: AnalyticsBlockUpdate): Analytics? {
        AnalyticsIntegrity.requireValid(update)
        requiredVersion = maxOf(requiredVersion, update.requiredCalcVersion)
        val old = ready[update.params] ?: return null
        if (old.timeZone != update.timeZone || old.currency != update.currency) return null
        val replacements = update.blocks.associateBy { it.id }
        if (!old.blocks.map { it.id }.containsAll(replacements.keys)) return null
        return old.copy(blocks = old.blocks.map { replacements[it.id] ?: it }).also { ready[update.params] = it }
    }

    /** Data invalidation retains the account's version barrier and any unknown command outcome. */
    fun clear() = ready.clear()

    /** A new login has an independent version space, even for the same account. */
    fun resetOwner() { ready.clear(); requiredVersion = 0; uncertainMutations.clear() }

    private companion object { const val MAX_SNAPSHOTS = 32 }
}
