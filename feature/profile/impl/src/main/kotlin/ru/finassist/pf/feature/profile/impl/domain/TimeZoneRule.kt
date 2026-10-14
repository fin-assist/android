package ru.finassist.pf.feature.profile.impl.domain

import java.time.Duration
import java.time.Instant

/** A device time zone that differs from the profile's, first seen at [since]. */
data class PendingZone(val zone: String, val since: Instant)

/** What [TimeZoneRule.next] tells the sync to do. */
sealed interface TimeZoneStep {
    /** The device is in the profile's zone: drop the record, if any. */
    data object Clear : TimeZoneStep

    /** A zone not counted yet (or the clock went back): start counting from now. */
    data class Record(val pending: PendingZone) : TimeZoneStep

    /** The recorded zone still holds, but not long enough yet. */
    data object Wait : TimeZoneStep

    /** The recorded zone has held for [TimeZoneRule.THRESHOLD]: send it to the profile. */
    data class Send(val zone: String) : TimeZoneStep
}

/**
 * Owner's decision on FIN-91 (variant «В»): the profile zone drives months and «today» in analytics, and a change
 * makes the server recalculate them, so a trip shorter than [THRESHOLD] must not move it.
 */
object TimeZoneRule {
    val THRESHOLD: Duration = Duration.ofHours(72)

    fun next(profileZone: String, deviceZone: String, pending: PendingZone?, now: Instant): TimeZoneStep = when {
        deviceZone == profileZone -> TimeZoneStep.Clear
        // A moment in the future means the device clock was set back: the time held is unknown, so count anew.
        pending == null || pending.zone != deviceZone || now.isBefore(pending.since) ->
            TimeZoneStep.Record(PendingZone(deviceZone, now))
        Duration.between(pending.since, now) >= THRESHOLD -> TimeZoneStep.Send(deviceZone)
        else -> TimeZoneStep.Wait
    }
}
