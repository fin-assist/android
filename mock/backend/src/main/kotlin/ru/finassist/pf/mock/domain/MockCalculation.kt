package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.AnalyticsCalculation

/** Observable calculation watermark for the in-process server; owned by the backend mutex. */
class MockCalculation {
    private var current = 0
    private var applied = 0
    private var marker = 0
    private val assignments = mutableMapOf<String, Int>()
    var pending = false
        private set

    fun dismiss(id: String): Int = assignments.getOrPut(id) {
        check(current <= Int.MAX_VALUE - 3)
        pending = true
        current + 1
    }.also { marker = maxOf(marker, it) }

    fun restore(id: String) { assignments.remove(id); pending = true }
    fun mark(version: Int) { require(version > 0); marker = maxOf(marker, version) }
    fun calculateNow() {
        check(current <= Int.MAX_VALUE - 3)
        current += 3
        applied = current
        pending = false
    }
    fun state() = AnalyticsCalculation(applied, marker > applied)
    fun clear() { current = 0; applied = 0; marker = 0; assignments.clear(); pending = false }
}
