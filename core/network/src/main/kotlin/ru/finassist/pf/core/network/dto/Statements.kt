package ru.finassist.pf.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class ImportConfigDto(
    val downloadUrl: String,
    val loginUrl: String,
    val maxFileSizeBytes: Long,
    val acceptedFormats: List<String>,
    val suggestedPeriod: RangeDto,
    val loaded: LoadedSummaryDto? = null,
)

@Serializable
data class LoadedSummaryDto(val firstOperationAt: String, val lastOperationAt: String, val operationCount: Int)

@Serializable
data class UploadAcceptedDto(val uploadId: String, val fileName: String)

@Serializable
data class ImportProgressEventDto(val stage: String, val percent: Int)

@Serializable
data class ImportResultDto(
    val operationCount: Int,
    val newCount: Int,
    val duplicateCount: Int,
    val unreadCount: Int,
    val firstOperationAt: String? = null,
    val lastOperationAt: String? = null,
    val isFirstImport: Boolean,
    val totals: ImportTotalsDto? = null,
    val accounts: List<ImportAccountDto>,
    val categorizedCount: Int,
    val ownTransferCount: Int,
    val uncategorizedCount: Int,
    val uncategorizedFilters: OperationsFilterDto? = null,
    val coverage: ImportCoverageDto,
    val notices: List<String>,
)

@Serializable
data class ImportTotalsDto(val scope: String, val expense: Long, val income: Long)

@Serializable
data class ImportAccountDto(val typeName: String, val mask: String? = null, val isOtherBank: Boolean)

@Serializable
data class ImportCoverageDto(
    val fullMonths: Int,
    val fullMonthsBefore: Int,
    val newlyFullMonths: List<String>,
    val incompleteMonths: List<IncompleteMonthDto>,
    val features: List<FeatureAvailabilityDto>,
)

@Serializable
data class IncompleteMonthDto(
    val month: String,
    val range: RangeDto,
    val dataFrom: String,
    val dataTo: String,
    val gaps: List<RangeDto>,
    val inProgress: Boolean,
)

@Serializable
data class FeatureAvailabilityDto(
    val feature: String,
    val open: Boolean,
    val openedNow: Boolean,
    val requiredFullMonths: Int? = null,
    val requiredMonthsWithData: Int? = null,
)

@Serializable
data class StatementsListDto(val summary: StatementsSummaryDto? = null, val uploads: List<UploadDto>)

@Serializable
data class StatementsSummaryDto(
    val uploadCount: Int,
    val operationCount: Int,
    val firstOperationAt: String,
    val lastOperationAt: String,
    val gaps: List<RangeDto>,
)

@Serializable
data class UploadDto(
    val uploadId: String,
    val fileName: String,
    val status: String,
    val uploadedAt: String,
    val operationCount: Int,
    val unreadCount: Int,
    val firstOperationAt: String? = null,
    val lastOperationAt: String? = null,
    val sourceName: String,
)

@Serializable
data class UnreadLinesListDto(val lines: List<UnreadLineDto>)

@Serializable
data class UnreadLineDto(
    val uploadId: String,
    val fileName: String,
    val lineNumber: Int,
    val date: String? = null,
    val reason: String,
    val reasonName: String,
)
