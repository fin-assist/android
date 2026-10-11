package ru.finassist.pf.core.tracking

/**
 * Keeps the crash report context in step with the app: the version of the applied feature-flag config
 * ([CrashKey.RC_VERSION]) and the trail of opened screens. The app calls it from its root; providers of the
 * values stay unaware of crash reporting.
 *
 * @param configVersion `FeatureFlags.version` — read on every [onConfigApplied].
 */
class CrashContextSync(
    private val reporter: CrashReporter,
    private val configVersion: () -> String,
) {
    private var appliedVersion: String? = null

    /** Call once at start and after every config update; repeated calls with an unchanged version do nothing. */
    @Synchronized
    fun onConfigApplied() {
        val version = configVersion()
        if (version == appliedVersion) return
        appliedVersion = version
        // A version that is not a valid value (a provider bug) clears the key rather than sending free text.
        val value = CrashValue.orNull(version)
        reporter.setKey(CrashKey.RC_VERSION, value)
        if (value != null) reporter.breadcrumb(Breadcrumb.ConfigApplied(value))
    }

    /** Call when navigation lands on a destination, with its route pattern (never the filled-in route). */
    fun onScreenOpened(routePattern: String?) {
        val screen = screenSlug(routePattern) ?: return
        reporter.breadcrumb(Breadcrumb.ScreenOpened(screen))
    }
}
