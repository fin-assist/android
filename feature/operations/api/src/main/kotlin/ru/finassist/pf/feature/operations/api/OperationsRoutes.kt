package ru.finassist.pf.feature.operations.api

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.navigation.OperationsFilter
import ru.finassist.pf.core.navigation.jsonNavTypeMap

object OperationsRoutes {
    /** «Операции» tab. */
    @Serializable data object Feed
    /** Search with optional preset filters (from analytics, assistant chips, import result). */
    @Serializable data class Search(val filter: OperationsFilter = OperationsFilter()) {
        companion object { val typeMap = jsonNavTypeMap(OperationsFilter.serializer()) }
    }
    @Serializable data class Detail(val id: String)
}
