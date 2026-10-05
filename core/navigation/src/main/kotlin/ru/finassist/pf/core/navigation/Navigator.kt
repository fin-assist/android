package ru.finassist.pf.core.navigation

/**
 * Navigation facade handed to every feature. Routes are `@Serializable` objects/classes declared in api modules.
 * Keeps features away from NavController so navigation can be tested and replaced.
 */
interface Navigator {
    fun navigate(route: Any)
    /** Navigate and drop everything above (and including, when [inclusive]) [popUpTo] from the back stack. */
    fun navigate(route: Any, popUpTo: Any, inclusive: Boolean = false)
    fun back()
    /** Replace the whole stack with [route] — used after login, logout and account deletion. */
    fun resetTo(route: Any)
}
