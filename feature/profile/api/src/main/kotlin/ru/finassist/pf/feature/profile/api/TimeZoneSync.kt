package ru.finassist.pf.feature.profile.api

/**
 * Keeps `profile.timezone` (api.md 2.2) in step with the device time zone without chasing short trips (FIN-91).
 *
 * A device zone that differs from the profile's is remembered with the moment it was first seen; the zone is sent
 * with `PATCH /v1/profile {timezone}` only once that same zone has held for 72 hours. Going back to the profile's
 * zone earlier drops the record, another zone starts it over. Checked on app start, on every return from the
 * background, on a device time zone change and on sign-in. Registration sends the zone itself (api.md 1.3).
 */
interface TimeZoneSync {
    /** Starts watching the triggers above; called once from `Application.onCreate`, later calls do nothing. */
    fun start()
}
