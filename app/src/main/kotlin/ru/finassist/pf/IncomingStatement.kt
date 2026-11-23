package ru.finassist.pf

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import ru.finassist.pf.feature.auth.api.SessionState

/** Keeps the temporary URI in the activity's memory through sign-in and unlocking. */
internal class IncomingStatement : ViewModel() {
    private class Selection(val id: String, val uri: Uri, val owner: SessionState.SignedIn?)

    private var selection by mutableStateOf<Selection?>(null)
    private var owner: SessionState.SignedIn? = null
    private var nextId = 0L

    val id: String? get() = selection?.id

    fun offer(uri: Uri) {
        if (selection?.uri == uri) return
        selection = Selection((++nextId).toString(), uri, owner)
    }

    fun observeSession(state: SessionState) {
        if (state == SessionState.Unknown) return
        owner = state as? SessionState.SignedIn
        selection?.let { current ->
            val currentOwner = current.owner
            if (currentOwner != null && owner != currentOwner) {
                selection = null
            } else if (currentOwner == null && owner != null) {
                selection = Selection(current.id, current.uri, owner)
            }
        }
    }

    fun consume(id: String, session: SessionState.SignedIn): Uri? {
        val current = selection ?: return null
        if (current.id != id || current.owner != session) return null
        selection = null
        return current.uri
    }
}
