package ru.finassist.pf.core.tracking

/**
 * Product analytics events. Names follow `<feature>.<object>.<action>` (see `docs/flags.md` for the shared naming scheme).
 * Parameters never contain personal data or amounts; user identity is our own user id, set via [setUser].
 */
interface Tracker {
    fun track(event: String, params: Map<String, String> = emptyMap())
    fun setUser(userId: String?)
}

/** Crash and ANR reporting (AppMetrica in prod). Only non-fatal errors go through here explicitly. */
interface CrashReporter {
    fun reportNonFatal(throwable: Throwable, message: String? = null)
    fun setUser(userId: String?)
}
