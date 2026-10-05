package ru.finassist.pf.core.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class AskRequestDto(val text: String, val transferMode: String? = null)

@Serializable
data class AskResponseDto(val question: MessageDto, val answerId: String, val remainingLimit: Int)

@Serializable
data class RetryResponseDto(val answerId: String, val remainingLimit: Int)

@Serializable
data class AssistantLimitDto(val remaining: Int, val dailyMax: Int, val resetsAt: String)

@Serializable
data class MessagesListDto(val messages: List<MessageDto>, val hasOlder: Boolean)

@Serializable
data class MessageDto(
    val id: String,
    val role: String,
    val text: String? = null,
    val status: String? = null,
    val errorCode: String? = null,
    val charged: Boolean? = null,
    val blocks: List<BlockDto>? = null,
    val chips: List<ChipDto>? = null,
    val source: AnswerSourceDto? = null,
    val createdAt: String,
)

/** Flat polymorphic block: fields present depend on `type`. */
@Serializable
data class BlockDto(
    val type: String,
    val altText: String? = null,
    val text: String? = null,
    val kind: String? = null,
    val title: String? = null,
    val valueKind: String? = null,
    val points: List<ChartPointDto>? = null,
    val highlightIndex: Int? = null,
    val rows: List<BlockRowDto>? = null,
)

@Serializable
data class ChartPointDto(
    val range: RangeDto,
    val value: Double? = null,
    val coverage: String,
    val dataFrom: String? = null,
    val dataTo: String? = null,
)

@Serializable
data class BlockRowDto(
    val label: String,
    val sublabel: String? = null,
    val amount: Long? = null,
    val count: Int? = null,
    val percent: Double? = null,
    val text: String? = null,
)

@Serializable
data class ChipDto(
    val label: String,
    val screen: String,
    val filters: OperationsFilterDto? = null,
    val params: AnalyticsParamsDto? = null,
)

@Serializable
data class AnswerSourceDto(
    val calculatedAt: String,
    val dataRange: RangeDto? = null,
    val transferMode: String,
    val changedSince: List<String>,
)

@Serializable
data class AnswerSnapshotEventDto(
    val status: String,
    val blocks: List<BlockDto>,
    val chips: List<ChipDto>,
    val source: AnswerSourceDto? = null,
)

@Serializable
data class AnswerChunkEventDto(val index: Int, val text: String)

@Serializable
data class AnswerBlockEventDto(val index: Int, val block: BlockDto)

@Serializable
data class AnswerDoneEventDto(val charged: Boolean, val remainingLimit: Int)
