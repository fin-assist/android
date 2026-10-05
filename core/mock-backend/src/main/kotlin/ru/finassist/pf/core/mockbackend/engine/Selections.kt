package ru.finassist.pf.core.mockbackend.engine

/** Opaque `selection` values the mock hands out in `OperationsFilter` and understands back in search. */
object Selections {
    const val BANK_FEES = "s_bank_fees"
    const val UNCATEGORIZED = "s_uncategorized"
    fun regularPayment(id: String) = "s_regular_$id"
    fun smallFrequent(categoryId: String) = "s_small_$categoryId"
    fun merchant(name: String) = "s_merchant_" + Integer.toHexString(name.lowercase().trim().hashCode())

    fun merchantKey(name: String) = Integer.toHexString(name.lowercase().trim().hashCode())
}
