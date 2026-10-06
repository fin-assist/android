package ru.finassist.pf.feature.profile.api

import kotlinx.serialization.Serializable

/** Routes of the profile feature. */
object ProfileRoutes {
    /** Tab root «Профиль». */
    @Serializable
    data object Home

    /** «Удалить аккаунт»: what is deleted, then the passcode confirmation. */
    @Serializable
    data object DeleteAccount
}
