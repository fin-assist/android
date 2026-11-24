package ru.finassist.pf.core.common

/** Support contacts used by «Написать в поддержку» and «Сообщить нам» (`mailto:`). */
object Support {
    const val EMAIL = "admin@plainfin.ru"

    /** Footer of a user-initiated support draft. */
    fun userIdFooter(userId: String): String = "\n\n—\nID пользователя: $userId"
}

/**
 * Public privacy policy linked from the registration footer and the upload screen's privacy notice.
 * The contract covers consent documents separately, so the policy address is an app constant opened in the browser.
 */
object LegalLinks {
    const val PRIVACY = "https://documents.plainfin.ru/privacy"
}
