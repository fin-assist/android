package ru.finassist.pf.feature.applock.api

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Routes of the app-lock feature. */
object AppLockRoutes {
    /** First-time passcode setup right after sign-in (step 3 of 4 of the first run). Not in the main graph. */
    @Serializable
    data object Setup

    /** Profile → «Код-пароль и биометрия». */
    @Serializable
    data object Security

    /** Change passcode: old → new → repeat. */
    @Serializable
    data object Change

    /**
     * Confirms the current passcode for a dangerous action (account deletion). On success the previous screen
     * receives [RESULT_CONFIRMED] = "true" through the navigator's result mechanism.
     */
    @Serializable
    data object Confirm {
        const val RESULT_CONFIRMED = "applock.confirmed"
    }
}

/** What the gate shows on top of the app. */
sealed interface LockState {
    /** No passcode yet: the setup screen must be completed before the main graph (only right after sign-in). */
    data object NotConfigured : LockState

    /** Passcode set, screen locked: unlock overlay. */
    data object Locked : LockState

    data object Unlocked : LockState
}

enum class BiometricAvailability { NONE, FINGERPRINT, FACE }

sealed interface VerifyResult {
    data object Ok : VerifyResult
    data class Wrong(val attemptsLeft: Int) : VerifyResult

    /** Fifth wrong code: the session has been cleared, the app returns to the phone screen. */
    data object LockedOut : VerifyResult
}

/**
 * Device-local passcode + biometrics. The passcode never leaves the device (hash in the encrypted prefs);
 * «Забыли код?» and 5 wrong codes end the session instead of recovering it.
 */
interface AppLock {
    val state: StateFlow<LockState>

    /** Whether the user enabled biometric unlock (and the device still supports it). */
    val biometricEnabled: StateFlow<Boolean>

    fun biometricAvailability(): BiometricAvailability

    suspend fun setPasscode(code: String)

    /** Checks [code] against the stored one; counts wrong attempts. */
    suspend fun verify(code: String): VerifyResult

    /** Checks a code without counting attempts or unlocking (change passcode, delete account). */
    suspend fun matches(code: String): Boolean

    suspend fun setBiometricEnabled(enabled: Boolean)

    /** Called by the unlock screen after a successful biometric prompt. */
    fun unlockByBiometric()

    /** Locks now (used by tests and the «lock on background» timer). */
    fun lock()

    /** «Забыли код?»: forgets the passcode and ends the session (the app shows the phone screen). */
    suspend fun forgetAndSignOut()

    /** Removes the local passcode and biometric flag — on sign-out and account deletion. */
    suspend fun reset()
}

const val PASSCODE_LENGTH = 4
const val MAX_WRONG_ATTEMPTS = 5
