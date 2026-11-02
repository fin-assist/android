package ru.finassist.pf.core.blocks

import ru.finassist.pf.core.common.error.ErrorPresentation

/** Analytics-only page capability. Other block hosts never fetch chart history. */
data class ChartPaging(
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    val loading: Boolean,
    val error: ErrorPresentation?,
    val retryReady: Boolean,
    val load: (older: Boolean) -> Unit,
    val retry: () -> Unit,
    val selectedMonth: String? = null,
)
