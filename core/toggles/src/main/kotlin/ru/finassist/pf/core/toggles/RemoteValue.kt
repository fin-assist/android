package ru.finassist.pf.core.toggles

/**
 * String values of Remote Config that are not on/off switches ([Flag]) or experiment variants ([Experiment]).
 * [defaultValue] is in effect while the provider has nothing; registry in docs/flags.md.
 */
enum class RemoteValue(val key: String, val defaultValue: String) {
    /**
     * Minimum supported version, `MAJOR.MINOR.PATCH` (FIN-22). Below it the app shows the update screen before its first
     * request. Empty — no minimum. A duplicate of the server's minimum: the server's `CLIENT_VERSION_UNSUPPORTED` wins.
     */
    APP_MIN_VERSION("app.min_version", ""),
}
