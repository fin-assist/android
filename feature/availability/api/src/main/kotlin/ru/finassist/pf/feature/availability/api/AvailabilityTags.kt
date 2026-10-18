package ru.finassist.pf.feature.availability.api

/**
 * Test tags of the update and maintenance screens (FIN-22) for UI tests (docs/e2e.md). The screens themselves are
 * not routes: the app root shows them over everything by the state of `ServiceGate`.
 */
object AvailabilityTags {
    const val UPDATE = "availability.update"
    const val UPDATE_BUTTON = "availability.update.button"
    const val UPDATE_SUPPORT = "availability.update.support"
    const val MAINTENANCE = "availability.maintenance"
    const val MAINTENANCE_UNTIL = "availability.maintenance.until"
    const val MAINTENANCE_CHECK = "availability.maintenance.check"
    const val MAINTENANCE_SUPPORT = "availability.maintenance.support"
}
