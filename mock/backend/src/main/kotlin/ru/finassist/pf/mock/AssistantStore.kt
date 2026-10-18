package ru.finassist.pf.mock

import kotlinx.coroutines.flow.MutableStateFlow
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AnswerSnapshotEvent
import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.Chip
import ru.finassist.pf.core.api.model.Message
import ru.finassist.pf.core.api.model.MessageRole
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.mock.domain.DataChanges
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * Dialogue history, per-answer event logs and the daily limit of the fake assistant service. [changes] — what changed
 * in the data since an answer was calculated: history and replays count `changed_since` at the time of the request
 * (api.md 7.3).
 */
class AssistantStore(val changes: DataChanges = DataChanges()) {
    /**
     * One answer to [questionId]. [transferMode] is the mode the question was asked with: a retry regenerates
     * the answer in it, not in the default (FIN-76). With [changes], the source of a snapshot (a replay, the
     * history) reports what changed after [calculatedOn], not what the live `source` event said back then.
     */
    class Answer(
        val id: String,
        val questionId: String,
        val createdAt: OffsetDateTime,
        val transferMode: TransferMode = TransferMode.WITH,
        private val changes: DataChanges? = null,
    ) {
        /** [DataChanges.version] of the data the answer was calculated on; set before its `source` event. */
        @Volatile var calculatedOn: Long = 0

        /** Every event emitted so far, in order; a late subscriber replays them into a snapshot. */
        val log = ArrayList<AnswerEvent>()
        val version = MutableStateFlow(0)
        var status = AnswerStatus.GENERATING
        var errorCode: AnswerErrorCode? = null
        /** The server's text of a failure (api.md 7.2, FIN-159). */
        var errorText: String? = null
        /** Whether a failure may be retried; null — stored before FIN-159, decided by the code ([retryAllowed]). */
        var retryable: Boolean? = null
        var charged: Boolean? = null
        var replaced = false

        @Synchronized
        fun append(event: AnswerEvent) {
            log += event
            when (event) {
                is AnswerEvent.Done -> { status = AnswerStatus.COMPLETE; charged = event.event.charged }
                is AnswerEvent.Error -> {
                    status = AnswerStatus.FAILED
                    errorCode = event.event.code
                    errorText = event.event.text
                    retryable = event.event.retryable
                }
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
                            blocks[e.event.index] = Block.text((b.text ?: "") + e.event.text)
                        } else {
                            blocks += Block.text(e.event.text)
                        }
                    }
                    is AnswerEvent.BlockAdded -> blocks += e.event.block
                    is AnswerEvent.Source -> source = changes?.restamp(e.event, calculatedOn) ?: e.event
                    is AnswerEvent.ChipAdded -> chips += e.event
                    else -> Unit
                }
            }
            val snap = AnswerSnapshotEvent(status = status, blocks = if (status == AnswerStatus.FAILED) emptyList() else blocks, chips = chips, source = source)
            return snap to log.size
        }

        /** api.md 7.4: only a failed answer marked retryable; one saved before FIN-159 — by the code, MODEL_ERROR only. */
        val retryAllowed: Boolean
            get() = !replaced && status == AnswerStatus.FAILED && (retryable ?: (errorCode == AnswerErrorCode.MODEL_ERROR))

        fun toMessage(): Message {
            val (snap, _) = snapshot()
            return Message(
                id = id, role = MessageRole.ASSISTANT, status = status, errorCode = errorCode,
                errorText = errorText, retryable = retryable,
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
