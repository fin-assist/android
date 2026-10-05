package ru.finassist.pf.feature.applock.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Device passcode and biometrics (mvp-scope «Вход и аккаунт»): 4-digit code set right after login,
 * unlock on launch and after 5 minutes in background, 5 wrong codes → logout.
 */
interface AppLock {
    /** A passcode exists for the current account. */
    val hasPasscode: Flow<Boolean>
    val biometricEnabled: Flow<Boolean>
    /** True while the lock screen must cover the app. */
    val locked: StateFlow<Boolean>

    /** Emits [reason] of a successful confirmation requested via [AppLockRoutes.Confirm]. */
    val confirmations: SharedFlow<String>

    fun onAppForegrounded(nowMillis: Long)
    fun onAppBackgrounded(nowMillis: Long)
}

object AppLockRoutes {
    /** First setup after login: «Придумайте код-пароль» → «Повторите код» → biometrics offer. */
    @kotlinx.serialization.Serializable data object Setup
    /** Settings screen «Код-пароль и биометрия». */
    @kotlinx.serialization.Serializable data object Security
    /** Change passcode: current code, new code, repeat. */
    @kotlinx.serialization.Serializable data object Change
    /** Confirm identity for a dangerous action; result arrives on [AppLock.confirmations] with the same [reason]. */
    @kotlinx.serialization.Serializable data class Confirm(val reason: String, val title: String)
}
