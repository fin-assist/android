package ru.finassist.pf.feature.auth.impl.domain

import ru.finassist.pf.core.api.model.PhoneVerification
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * End of the call wait by the device's clock (api.md 1.1, «Срок на устройстве», decision of 17.10.2026). The device
 * and server clocks differ, so `expires_at` is never compared with the device time directly: only the time left is
 * taken from the server and added to the moment the response arrived on the device.
 */
object CallDeadline {
    /**
     * Device deadline of [verification] received at [receivedAt] (device time):
     * - `expires_in` present → [receivedAt] + `expires_in` (a negative value counts as 0);
     * - otherwise, with the `Date` header → [receivedAt] + (`expires_at` − server time), not before [receivedAt];
     * - neither → null: no time on the screen and no local timer, the stream's `expired` ends the wait.
     */
    fun of(verification: PhoneVerification, receivedAt: Instant): Instant? {
        verification.expiresIn?.let { return receivedAt.plusSeconds(it.coerceAtLeast(0).toLong()) }
        val serverTime = verification.serverTime ?: return null
        val left = Duration.between(serverTime, verification.expiresAt.toInstant())
        return receivedAt.plus(if (left.isNegative) Duration.ZERO else left)
    }

    /** «ЧЧ:ММ» of [deadline] in the device [zone]; seconds are dropped (22:18:40 → «22:18», api.md 1.1). */
    fun clockText(deadline: Instant, zone: ZoneId): String {
        val local = deadline.atZone(zone)
        return "%02d:%02d".format(Locale.ROOT, local.hour, local.minute)
    }
}
