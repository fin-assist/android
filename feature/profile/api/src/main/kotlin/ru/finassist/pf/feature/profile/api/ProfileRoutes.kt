package ru.finassist.pf.feature.profile.api

import kotlinx.serialization.Serializable

/** Routes of the profile feature. Stage 7 adds categories, assistant consent and account deletion. */
object ProfileRoutes {
    /** Tab root «Профиль». */
    @Serializable
    data object Home
}
