package ru.finassist.pf.mock

import java.time.OffsetDateTime

/** Terminal OFF_TOPIC window, guarded by the backend mutex together with admission and charging. */
internal class AssistantOffTopicPolicy(
    private val limit: Int = 5,
    private val windowSeconds: Long = 7L * 24 * 60 * 60,
) {
    private val completed = mutableSetOf<String>()
    private val offTopic = mutableMapOf<String, OffsetDateTime>()

    init {
        require(limit > 0 && windowSeconds > 0)
    }

    /** Records an answer exactly once; only a charged successful completion resets the window. */
    fun terminal(id: String, at: OffsetDateTime, isOffTopic: Boolean = false, charged: Boolean = false): Boolean {
        require(!isOffTopic || !charged)
        if (!completed.add(id)) return false
        if (charged) offTopic.clear()
        if (isOffTopic) offTopic[id] = at
        return true
    }

    /** Moment when the current count becomes less than the limit, including excess in-flight failures. */
    fun retryAt(now: OffsetDateTime): OffsetDateTime? {
        offTopic.entries.removeAll { !it.value.plusSeconds(windowSeconds).isAfter(now) }
        if (offTopic.size < limit) return null
        val times = offTopic.values.sortedWith(compareBy<OffsetDateTime> { it.toEpochSecond() }.thenBy { it.nano })
        return times[times.size - limit].plusSeconds(windowSeconds)
    }

    fun clear() {
        completed.clear()
        offTopic.clear()
    }
}
