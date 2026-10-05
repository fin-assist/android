package ru.finassist.pf.core.navigation

/**
 * Entry to developer tools (flag overrides in the mock flavour). Bound optionally: production has none and the
 * profile simply shows no row. Injected as `java.util.Optional<DebugMenu>`.
 */
interface DebugMenu {
    /** Route of the debug screen, registered by the flavour's own [FeatureEntry]. */
    fun route(): Any
}
