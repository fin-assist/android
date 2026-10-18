package ru.finassist.pf.mock.domain

import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.ChangedSince

/**
 * What changed in the user's data and in which order: category changes and finished or deleted statements
 * (api.md 7.2, `source.changed_since`). An answer remembers the [version] it was calculated on; [since] tells what
 * changed after that. The order of changes is a counter, not a timestamp, so a change made within the same
 * millisecond as the calculation (a pinned or coarse clock) is still seen as later. Mutated under the backend mutex.
 */
class DataChanges {
    /** One immutable value: a reader outside the backend mutex never sees one field updated and the other not. */
    private data class State(val version: Long = 0, val categoriesAt: Long = 0, val uploadsAt: Long = 0)

    @Volatile private var state = State()

    /** Version of the data: the number of changes recorded so far. */
    val version: Long get() = state.version

    /** An operation's category was changed by the user. Writers hold the backend mutex. */
    fun categoriesChanged() {
        val s = state
        state = s.copy(version = s.version + 1, categoriesAt = s.version + 1)
    }

    /** A statement finished importing or was deleted. A failed or cancelled upload leaves the data as it was. */
    fun uploadsChanged() {
        val s = state
        state = s.copy(version = s.version + 1, uploadsAt = s.version + 1)
    }

    /** Codes of what changed after [calculatedOn], in the order of api.md; empty — nothing did. */
    fun since(calculatedOn: Long): List<ChangedSince> {
        val s = state
        return listOfNotNull(
            ChangedSince.CATEGORIES.takeIf { s.categoriesAt > calculatedOn },
            ChangedSince.UPLOADS.takeIf { s.uploadsAt > calculatedOn },
        )
    }

    /** [source] with `changed_since` and its ready text counted now for data of [calculatedOn]. */
    fun restamp(source: AnswerSource, calculatedOn: Long): AnswerSource {
        val changed = since(calculatedOn)
        return source.copy(changedSince = changed, changedSinceText = text(changed))
    }

    companion object {
        /** The ready text of api.md 7.2 (`changed_since_text`), lowercase like the example there; null — nothing changed. */
        fun text(changed: List<ChangedSince>): String? {
            val categories = ChangedSince.CATEGORIES in changed
            val uploads = ChangedSince.UPLOADS in changed
            return when {
                categories && uploads -> "с тех пор менялись категории и загружали или удаляли выписки"
                categories -> "с тех пор категории менялись"
                uploads -> "с тех пор загружали или удаляли выписки"
                else -> null
            }
        }
    }
}
