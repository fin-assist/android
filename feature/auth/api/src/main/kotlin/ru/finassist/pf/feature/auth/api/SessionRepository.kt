package ru.finassist.pf.feature.auth.api

import kotlinx.coroutines.flow.StateFlow

sealed interface SessionState {
    /** Not read from storage yet (first frame after process start). */
    data object Unknown : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val userId: String) : SessionState
}

/** Who is logged in. Tokens themselves stay inside the auth feature. */
interface SessionRepository {
    val state: StateFlow<SessionState>
    /** Why the last session ended — the phone screen shows the matching notice. */
    val lastSignOutNotice: StateFlow<PhoneScreenNotice>
    val userId: String? get() = (state.value as? SessionState.LoggedIn)?.userId

    /** Revokes the session on the server (best effort) and clears local tokens and data. */
    suspend fun logout()

    /** Called by the profile feature after `DELETE /v1/profile` succeeded. */
    suspend fun onAccountDeleted()
}
