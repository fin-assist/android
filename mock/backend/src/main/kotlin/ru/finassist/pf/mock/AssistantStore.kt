package ru.finassist.pf.mock

import kotlinx.coroutines.flow.MutableStateFlow
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AnswerSnapshotEvent
import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.Chip
import ru.finassist.pf.core.api.model.Message
import ru.finassist.pf.core.api.model.MessageRole
import java.time.LocalDate
import java.time.OffsetDateTime

/** Dialogue history, per-answer event logs and the daily limit of the fake assistant service. */
class AssistantStore {
    class Answer(val id: String, val questionId: String, val createdAt: OffsetDateTime) {
        /** Every event emitted so far, in order; a late subscriber replays them into a snapshot. */
        val log = ArrayList<AnswerEvent>()
        val version = MutableStateFlow(0)
        var status = AnswerStatus.GENERATING
        var errorCode: AnswerErrorCode? = null
        var charged: Boolean? = null
        var replaced = false

        @Synchronized
        fun append(event: AnswerEvent) {
            log += event
            when (event) {
                is AnswerEvent.Done -> { status = AnswerStatus.COMPLETE; charged = event.event.charged }
                is AnswerEvent.Error -> { status = AnswerStatus.FAILED; errorCode = event.event.code }
                else -> Unit
            }
            version.value = log.size
        }

        @Synchronized
        fun snapshot(): Pair<AnswerSnapshotEvent, Int> {
            val blocks = ArrayList<Block>()
            val chips = ArrayList<Chip>()
            var source: AnswerSource? = null
            for (e in log) {
                when (e) {
                    is AnswerEvent.Chunk -> {
                        if (e.event.index < blocks.size) {
                            val b = blocks[e.event.index]
                            blocks[e.event.index] = b.copy(text = (b.text ?: "") + e.event.text)
                        } else {
                            blocks += Block(type = BlockType.TEXT, text = e.event.text)
                        }
                    }
                    is AnswerEvent.BlockAdded -> blocks += e.event.block
                    is AnswerEvent.Source -> source = e.event
                    is AnswerEvent.ChipAdded -> chips += e.event
                    else -> Unit
                }
            }
            val snap = AnswerSnapshotEvent(status = status, blocks = if (status == AnswerStatus.FAILED) emptyList() else blocks, chips = chips, source = source)
            return snap to log.size
        }

        fun toMessage(): Message {
            val (snap, _) = snapshot()
            return Message(
                id = id, role = MessageRole.ASSISTANT, status = status, errorCode = errorCode,
                charged = charged.takeIf { status == AnswerStatus.COMPLETE },
                blocks = snap.blocks, chips = snap.chips.takeIf { status == AnswerStatus.COMPLETE },
                source = snap.source.takeIf { status == AnswerStatus.COMPLETE }, createdAt = createdAt,
            )
        }
    }

    /** Questions and answers in chronological order; a replaced answer is kept but hidden from history. */
    val questions = ArrayList<Message>()
    val answers = LinkedHashMap<String, Answer>()
    val usage = HashMap<LocalDate, Int>()

    fun clear() {
        questions.clear(); answers.clear(); usage.clear()
    }

    fun history(): List<Message> {
        val result = ArrayList<Message>()
        for (q in questions) {
            result += q
            answers.values.lastOrNull { it.questionId == q.id && !it.replaced }?.let { result += it.toMessage() }
        }
        return result
    }
}
