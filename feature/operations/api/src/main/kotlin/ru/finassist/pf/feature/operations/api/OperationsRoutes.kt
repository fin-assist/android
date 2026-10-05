package ru.finassist.pf.feature.operations.api

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.navigation.OperationsFilter

object OperationsRoutes {
    /** «Операции» tab. */
    @Serializable data object Feed
    /** Search with optional preset filters (from analytics, assistant chips, import result). */
    @Serializable data class Search(val filter: OperationsFilter = OperationsFilter())
    @Serializable data class Detail(val id: String)
}
