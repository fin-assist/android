package ru.finassist.pf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.statements.impl.ui.StatementFile
import java.util.UUID

/** Keeps the temporary URI in the activity's memory through sign-in and unlocking. */
internal class IncomingStatement : ViewModel() {
    private class Selection(val id: String, val file: StatementFile, val owner: SessionState.SignedIn?)

    private var selection by mutableStateOf<Selection?>(null)
    private var owner: SessionState.SignedIn? = null
    val id: String? get() = selection?.id

    fun offer(file: StatementFile) {
        if (selection?.file?.uri == file.uri) return
        selection = Selection(UUID.randomUUID().toString(), file, owner)
    }

    fun observeSession(state: SessionState) {
        if (state == SessionState.Unknown) return
        owner = state as? SessionState.SignedIn
        selection?.let { current ->
            val currentOwner = current.owner
            if (currentOwner != null && owner != currentOwner) {
                selection = null
            } else if (currentOwner == null && owner != null) {
                selection = Selection(current.id, current.file, owner)
            }
        }
    }

    fun consume(id: String, session: SessionState.SignedIn): StatementFile? {
        val current = selection ?: return null
        if (current.id != id || current.owner != session) return null
        selection = null
        return current.file
    }
}
