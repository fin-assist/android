package ru.finassist.pf.core.common.time

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.DateTimeException
import java.time.ZoneId

/**
 * The zone days, months and "today" are counted in on the client: `profile.timezone` (api.md 2.2), the zone analytics
 * counts in, once the profile has been read; the device zone before that and after sign-out (FIN-92). The app [Clock]
 * reads its zone from here.
 *
 * The profile feature keeps it current: it sets the zone on every read or write of the profile and restores the last
 * known one on start, so an offline start does not fall back to the device zone.
 */
class ReportZone(private val device: () -> ZoneId = ZoneId::systemDefault) {
    private val profileZone = MutableStateFlow<ZoneId?>(null)

    /** The profile zone; null while it is not known. */
    val profile: StateFlow<ZoneId?> = profileZone

    val current: ZoneId get() = profileZone.value ?: device()

    /**
     * An id Java does not know (a newer tzdb on the server) is ignored: the last known zone stays. Returns whether the
     * zone was taken.
     */
    fun set(id: String): Boolean {
        val zone = try {
            ZoneId.of(id)
        } catch (e: DateTimeException) {
            return false
        }
        profileZone.value = zone
        return true
    }

    fun clear() {
        profileZone.value = null
    }
}
