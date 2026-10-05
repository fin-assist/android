package ru.finassist.pf.feature.statements.api

import kotlinx.serialization.Serializable

/** Routes of the statements feature. Stage 5 adds the import result and history screens. */
object StatementsRoutes {
    /**
     * Upload guide + file picker. [firstRun] — step 4 of 4 right after registration (no back, «later» link);
     * otherwise opened from the feed or the profile.
     */
    @Serializable
    data class Upload(val firstRun: Boolean = false)
}
