package ru.finassist.pf.core.api.model

import kotlinx.serialization.Serializable
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.ApiDateTime
import ru.finassist.pf.core.common.time.DateRange

@Serializable
data class ImportConfig(
    val downloadUrl: String,
    val loginUrl: String,
    val maxFileSizeBytes: Long,
    val acceptedFormats: List<String>,
    val suggestedPeriod: DateRange,
    val loaded: LoadedSummary? = null,
)

@Serializable
data class LoadedSummary(
    val firstOperationAt: ApiDateTime,
    val lastOperationAt: ApiDateTime,
    val operationCount: Int,
)

@Serializable
data class UploadAccepted(
    val uploadId: String,
    val fileName: String,
)

@Serializable(with = ImportStage.Serializer::class)
enum class ImportStage(override val code: String) : ApiCode {
    PARSING("parsing"), DEDUP("dedup"), RULES("rules"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<ImportStage>("ImportStage", entries, UNKNOWN)
}

@Serializable
data class ImportProgress(
    val stage: ImportStage,
    val percent: Int,
)

@Serializable(with = ImportTotalsScope.Serializer::class)
enum class ImportTotalsScope(override val code: String) : ApiCode {
    ALL("all"), NEW("new"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<ImportTotalsScope>("ImportTotalsScope", entries, UNKNOWN)
}

@Serializable
data class ImportTotals(
    val scope: ImportTotalsScope,
    val expense: Money,
    val income: Money,
)

@Serializable
data class ImportAccount(
    val typeName: String,
    val mask: String? = null,
    val isOtherBank: Boolean,
)

@Serializable
data class IncompleteMonth(
    val month: String,
    val range: DateRange,
    val dataFrom: ApiDateTime,
    val dataTo: ApiDateTime,
    val gaps: List<DateRange>,
    val inProgress: Boolean,
)

@Serializable(with = AnalyticsFeature.Serializer::class)
enum class AnalyticsFeature(override val code: String) : ApiCode {
    COMPARISON("comparison"),
    MONTHLY_CHART("monthly_chart"),
    TYPICAL("typical"),
    REGULAR_PAYMENTS("regular_payments"),
    NOTABLE_SPENDING("notable_spending"),
    SMALL_FREQUENT("small_frequent"),
    YEAR_FORECAST("year_forecast"),
    UNKNOWN("?");

    object Serializer : OpenEnumSerializer<AnalyticsFeature>("AnalyticsFeature", entries, UNKNOWN)
}

@Serializable
data class FeatureAvailability(
    val feature: AnalyticsFeature,
    val open: Boolean,
    val openedNow: Boolean,
    val requiredFullMonths: Int? = null,
    val requiredMonthsWithData: Int? = null,
)

@Serializable
data class ImportCoverage(
    val fullMonths: Int,
    val fullMonthsBefore: Int,
    val newlyFullMonths: List<String>,
    val incompleteMonths: List<IncompleteMonth>,
    val features: List<FeatureAvailability>,
)

@Serializable(with = ImportNotice.Serializer::class)
enum class ImportNotice(override val code: String) : ApiCode {
    EMPTY_STATEMENT("empty_statement"), NO_EXPENSES("no_expenses"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<ImportNotice>("ImportNotice", entries, UNKNOWN)
}

/** Terminal `complete` event of the import progress stream: everything the import result screen shows. */
@Serializable
data class ImportResult(
    val operationCount: Int,
    val newCount: Int,
    val duplicateCount: Int,
    val unreadCount: Int,
    val firstOperationAt: ApiDateTime? = null,
    val lastOperationAt: ApiDateTime? = null,
    val isFirstImport: Boolean,
    val totals: ImportTotals? = null,
    val accounts: List<ImportAccount>,
    val categorizedCount: Int,
    val ownTransferCount: Int,
    val uncategorizedCount: Int,
    val uncategorizedFilters: OperationsFilter? = null,
    val coverage: ImportCoverage,
    val notices: List<ImportNotice>,
)

@Serializable(with = UploadStatus.Serializer::class)
enum class UploadStatus(override val code: String) : ApiCode {
    PROCESSING("processing"), DONE("done"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<UploadStatus>("UploadStatus", entries, UNKNOWN)
}

@Serializable
data class Upload(
    val uploadId: String,
    val fileName: String,
    val status: UploadStatus,
    val uploadedAt: ApiDateTime,
    val operationCount: Int,
    val unreadCount: Int,
    val firstOperationAt: ApiDateTime? = null,
    val lastOperationAt: ApiDateTime? = null,
    val sourceName: String,
)

@Serializable
data class StatementsSummary(
    val uploadCount: Int,
    val operationCount: Int,
    val firstOperationAt: ApiDateTime,
    val lastOperationAt: ApiDateTime,
    val gaps: List<DateRange>,
)

@Serializable
data class StatementsList(
    val summary: StatementsSummary? = null,
    val uploads: List<Upload>,
)

@Serializable(with = UnreadReason.Serializer::class)
enum class UnreadReason(override val code: String) : ApiCode {
    BAD_AMOUNT("bad_amount"), BAD_DATE("bad_date"), NO_DATE("no_date"), NO_DESCRIPTION("no_description"),
    TRUNCATED("truncated"), UNKNOWN("?");

    object Serializer : OpenEnumSerializer<UnreadReason>("UnreadReason", entries, UNKNOWN)
}

@Serializable
data class UnreadLine(
    val uploadId: String,
    val fileName: String,
    val lineNumber: Int,
    val date: ApiDateTime? = null,
    val reason: UnreadReason,
    val reasonName: String,
)

@Serializable
data class UnreadLinesList(val lines: List<UnreadLine>)
