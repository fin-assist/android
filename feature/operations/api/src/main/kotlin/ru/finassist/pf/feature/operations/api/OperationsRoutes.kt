package ru.finassist.pf.feature.operations.api

import kotlinx.serialization.Serializable

/** Routes of the operations feature. Stage 5 adds search, filters and the operation card. */
object OperationsRoutes {
    /** Tab root «Операции». */
    @Serializable
    data object Feed
}
