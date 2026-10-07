package ru.finassist.pf.feature.auth.impl.domain

import ru.finassist.pf.core.api.model.PhoneVerification
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of one sign-in attempt shared by the phone, call and consent screens. The verification token is a
 * secret that must stay in memory only (api.md 1.2), so it lives here and not in navigation arguments.
 */
@Singleton
class SignInFlow @Inject constructor() {
    @Volatile var phone: String = ""
    @Volatile var verification: PhoneVerification? = null
    @Volatile var registrationToken: String? = null
    /** `Idempotency-Key` of the current phone verification request (reused on retry of the same action). */
    @Volatile var phoneRequestKey: String? = null

    fun reset() {
        verification = null
        registrationToken = null
        phoneRequestKey = null
    }
}

/**
 * +7 XXX XXX-XX-XX formatting of a Russian number while typing. The field keeps digits only and shows the mask
 * through a visual transformation, so the cursor is tracked on digits and never jumps when the mask inserts
 * `+7 `, spaces or dashes (FIN-25).
 */
object PhoneFormat {
    /** Keeps at most 10 significant digits after the country code. */
    fun digits(raw: String): String = edit(raw, raw.length).first

    /**
     * Normalizes raw field text and the cursor position in it: keeps digits, drops a leading `7`/`8` country
     * prefix (typed or pasted), keeps at most 10 digits. Returns the digits and the cursor in digit units.
     */
    fun edit(raw: String, cursor: Int): Pair<String, Int> {
        var d = raw.filter { it.isDigit() }
        var c = raw.take(cursor.coerceIn(0, raw.length)).count { it.isDigit() }
        if (d.startsWith("7") || d.startsWith("8")) {
            d = d.drop(1)
            c = (c - 1).coerceAtLeast(0)
        }
        d = d.take(10)
        return d to c.coerceAtMost(d.length)
    }

    fun display(digits: String): String {
        if (digits.isEmpty()) return ""
        val sb = StringBuilder("+7")
        digits.forEachIndexed { i, ch ->
            when (i) {
                0 -> sb.append(' ')
                3 -> sb.append(' ')
                6, 8 -> sb.append('-')
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    /** Cursor in [digits] → cursor in [display]: right before the digit, or at the end after the last one. */
    fun digitToDisplay(digits: String, offset: Int): Int {
        if (digits.isEmpty()) return 0
        val shown = display(digits)
        if (offset >= digits.length) return shown.length
        var seen = 0
        shown.forEachIndexed { i, ch ->
            if (i >= 2 && ch.isDigit()) {
                if (seen == offset) return i
                seen++
            }
        }
        return shown.length
    }

    /** Cursor in [display] → cursor in [digits]: the number of digits to the left of it (the `7` of `+7` excluded). */
    fun displayToDigit(digits: String, offset: Int): Int {
        val shown = display(digits)
        return shown.take(offset.coerceIn(0, shown.length)).drop(2).count { it.isDigit() }
    }

    fun toE164(digits: String): String = "+7$digits"

    fun isComplete(digits: String) = digits.length == 10
}
