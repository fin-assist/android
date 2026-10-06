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

/** +7 XXX XXX-XX-XX formatting of a Russian number while typing; digits only are kept in state. */
object PhoneFormat {
    /** Keeps at most 10 significant digits after the country code. */
    fun digits(raw: String): String {
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("7") || d.startsWith("8")) d = d.drop(1)
        return d.take(10)
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

    fun toE164(digits: String): String = "+7$digits"

    fun isComplete(digits: String) = digits.length == 10
}
