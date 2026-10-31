package ru.finassist.pf.core.blocks

import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.PeriodType

/** Exact, supported target of a server-provided Analytics period action. */
data class AnalyticsPeriodTarget(val period: PeriodTypeCode, val date: PeriodKey)

/** Parses action fields without assuming that a server sent a known type or a valid key. */
object AnalyticsPeriodTargets {
    fun of(period: PeriodTypeCode?, date: PeriodKey?): AnalyticsPeriodTarget? {
        val knownPeriod = period ?: return null
        val type = PeriodType.fromCode(knownPeriod.code) ?: return null
        if (date == null || !PeriodKey.isValid(date.value) || date.type != type) return null
        return AnalyticsPeriodTarget(knownPeriod, date)
    }
}
