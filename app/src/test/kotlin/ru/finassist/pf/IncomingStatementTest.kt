package ru.finassist.pf

import android.app.Application
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.feature.auth.api.SessionState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IncomingStatementTest {
    private val file = Uri.parse("content://example.test/statement.ofx")
    private val session = SessionState.SignedIn("user", "first-session")

    @Test fun `keeps the selection until sign-in then delivers it once`() {
        val incoming = IncomingStatement()
        incoming.offer(file)
        val request = requireNotNull(incoming.id)
        assertNull(incoming.consume(request, session))

        incoming.observeSession(SessionState.SignedOut)
        incoming.observeSession(session)

        assertEquals(file, incoming.consume(request, session))
        assertNull(incoming.id)
        assertNull(incoming.consume(request, session))
    }

    @Test fun `does not carry a previous session's selection to the next session`() {
        val incoming = IncomingStatement()
        incoming.observeSession(session)
        incoming.offer(file)
        val request = requireNotNull(incoming.id)

        incoming.observeSession(SessionState.SignedOut)
        val next = session.copy(authSessionEpoch = "second-session")
        incoming.observeSession(next)

        assertNull(incoming.consume(request, next))
        assertNull(incoming.id)
    }

    @Test fun `same pending URI retains the request and consuming an old ID cannot remove a replacement`() {
        val incoming = IncomingStatement()
        incoming.observeSession(session)
        incoming.offer(file)
        val request = requireNotNull(incoming.id)
        incoming.offer(file)
        assertEquals(request, incoming.id)

        val replacement = Uri.parse("content://example.test/replacement.ofx")
        incoming.offer(replacement)

        assertNull(incoming.consume(request, session))
        assertEquals(replacement, incoming.consume(requireNotNull(incoming.id), session))
    }
}
