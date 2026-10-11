package ru.finassist.pf.core.common

/** Support contacts used by «Написать в поддержку» and «Сообщить нам» (`mailto:`). */
object Support {
    const val EMAIL = "admin@plainfin.ru"
}

/**
 * Public documents of the service (FIN-40): the registration footer links to them, the upload screen's privacy
 * notice links to the privacy policy. The contract has no such documents (`ConsentType` covers only the consents),
 * so the addresses are constants of the app; they open in the browser.
 */
object LegalLinks {
    const val TERMS = "https://plainfin.ru/terms"
    const val PRIVACY = "https://plainfin.ru/privacy"
}
