package ru.finassist.pf.feature.auth.api

import kotlinx.serialization.Serializable

/** Why the phone screen is shown; drives the notice on top of it. */
@Serializable
enum class PhoneScreenNotice { None, LoggedOut, AccountDeleted, SessionExpired }

object AuthRoutes {
    /** Phone number entry — the only entry point of the auth flow. */
    @Serializable
    data class Phone(val notice: PhoneScreenNotice = PhoneScreenNotice.None)
}
