package ru.finassist.pf.core.mockbackend.engine

import java.security.SecureRandom
import java.util.UUID

/** UUIDv7-shaped ids (time-ordered), opaque to the client as the contract requires. */
object Ids {
    private val random = SecureRandom()

    fun next(nowMillis: Long = System.currentTimeMillis()): String {
        val rand = ByteArray(10).also(random::nextBytes)
        val msb = (nowMillis shl 16) or 0x7000L or ((rand[0].toLong() and 0x0F) shl 8) or (rand[1].toLong() and 0xFF)
        var lsb = 0L
        for (i in 2 until 10) lsb = (lsb shl 8) or (rand[i].toLong() and 0xFF)
        lsb = (lsb and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE   // variant 10xx
        return UUID(msb, lsb).toString()
    }

    fun secret(prefix: String): String {
        val b = ByteArray(32).also(random::nextBytes)
        return prefix + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}
