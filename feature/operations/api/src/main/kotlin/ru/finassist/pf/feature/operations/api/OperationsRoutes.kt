package ru.finassist.pf.feature.operations.api

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.api.model.OperationsFilter

/** Routes of the operations feature. */
object OperationsRoutes {
    /** Tab root «Операции». */
    @Serializable
    data object Feed

    /**
     * Search with preset filters (from analytics tiles, assistant chips, the import result); an empty filter
     * opens a blank search. Register with `typeMap = PfNavTypes.MAP` — the filter is carried as JSON.
     * Non-null on purpose: `JsonNavType` arguments must not be nullable (FIN-31).
     */
    @Serializable
    data class Search(val filter: OperationsFilter = OperationsFilter())

    /** Operation (or own-transfer pair) details with the category picker. */
    @Serializable
    data class Detail(val id: String)
}
