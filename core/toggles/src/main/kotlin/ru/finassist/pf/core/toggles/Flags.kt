package ru.finassist.pf.core.toggles

/**
 * Registry of every flag the app reads. Keep `docs/flags.md` in sync (CI checks the list).
 * Naming: `<feature>.<object>[.<sub-object>]`, lowercase, dots. Defaults are the "everything on" state.
 */
object Flags {
    val authRegistration = Flag("auth.registration", true, "New phone numbers may register; off = login for existing users only")

    val analyticsBlockTiles = Flag("analytics.block.tiles", true, "Expense/income/balance/daily/forecast tiles")
    val analyticsBlockExpenseCategories = Flag("analytics.block.expense_categories", true, "Expenses by category card")
    val analyticsBlockIncomeCategories = Flag("analytics.block.income_categories", true, "Income by category card")
    val analyticsBlockMonthlyChart = Flag("analytics.block.monthly_chart", true, "Monthly bar charts")
    val analyticsBlockRegularPayments = Flag("analytics.block.regular_payments", true, "Subscriptions and regular payments card")
    val analyticsBlockNotableSpending = Flag("analytics.block.notable_spending", true, "Notable spending card")
    val analyticsBlockBankFees = Flag("analytics.block.bank_fees", true, "Bank fees and interest card")
    val analyticsBlockSmallFrequent = Flag("analytics.block.small_frequent", true, "Small frequent spending card")

    val assistant = Flag("assistant", true, "Assistant screen and every link to it (analytics card, profile row, chips)")

    val searchFilterPeriod = Flag("search.filter.period", true, "Period chip in operations search")
    val searchFilterCategory = Flag("search.filter.category", true, "Category chip in operations search")
    val searchFilterAmount = Flag("search.filter.amount", true, "Amount chip in operations search")
    val searchFilterKind = Flag("search.filter.kind", true, "'Only expenses' chip in operations search")
    val analyticsFilterTransfers = Flag("analytics.filter.transfers", true, "'With / without transfers' chip on analytics")

    val statementsUpload = Flag("statements.upload", true, "Statement upload screen and every link to it")

    val profileDeleteAccount = Flag("profile.delete_account", true, "'Delete account' link in profile")

    val all: List<Flag> = listOf(
        authRegistration,
        analyticsBlockTiles, analyticsBlockExpenseCategories, analyticsBlockIncomeCategories, analyticsBlockMonthlyChart,
        analyticsBlockRegularPayments, analyticsBlockNotableSpending, analyticsBlockBankFees, analyticsBlockSmallFrequent,
        assistant,
        searchFilterPeriod, searchFilterCategory, searchFilterAmount, searchFilterKind, analyticsFilterTransfers,
        statementsUpload,
        profileDeleteAccount,
    )
}
