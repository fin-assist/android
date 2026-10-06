package ru.finassist.pf.feature.auth.api

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Routes of the sign-in feature. The app installs them in a separate graph shown while there is no session. */
object AuthRoutes {
    /** Phone number screen. [reason] selects the notice on top: `logged_out`, `deleted`, or none. */
    @Serializable
    data class Phone(val reason: String? = null) {
        companion object {
            const val REASON_LOGGED_OUT = "logged_out"
            const val REASON_DELETED = "deleted"
            const val REASON_EXPIRED = "expired"
        }
    }

    /** Waiting for the callback. The verification request lives in the feature's flow holder, not in the route. */
    @Serializable
    data object Call

    /** Consent to personal-data processing (new number only). */
    @Serializable
    data object Consent

    /** Shown to a new number when `auth.registration` is off. */
    @Serializable
    data object RegistrationClosed
}

/** Who is signed in. */
sealed interface SessionState {
    data object Unknown : SessionState
    data object SignedOut : SessionState
    data class SignedIn(val userId: String) : SessionState
}

/**
 * Session facade for the rest of the app: current state, sign-out, and whether the session was just created
 * by registration (the app then continues to the upload step after the passcode is set).
 */
interface SessionRepository {
    val state: Flow<SessionState>

    /**
     * True once after a registration (persisted, survives process death until consumed); the app then opens
     * the upload screen as step 4 of 4.
     */
    suspend fun consumeJustRegistered(): Boolean

    /** Ends the session on the server (best effort) and locally; the app returns to the phone screen. */
    suspend fun signOut(reason: String? = null)

    /** Clears the session locally only (account deleted on the server, tokens revoked). */
    suspend fun clearLocal(reason: String? = null)

    /** Why the last sign-out happened — for the notice on the phone screen; consumed on read. */
    fun consumeSignOutReason(): String?
}
