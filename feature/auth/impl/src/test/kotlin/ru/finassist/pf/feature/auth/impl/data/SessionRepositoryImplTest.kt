package ru.finassist.pf.feature.auth.impl.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.tracking.Breadcrumb
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.auth.impl.SessionHarness
import ru.finassist.pf.feature.auth.impl.eventually
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionRepositoryImplTest {
    private val pair = TokenPair("a1", "r1")
    private val harnesses = mutableListOf<SessionHarness>()

    private fun harness(initial: TokenStore.Session? = null) = SessionHarness(initial).also { harnesses += it }

    private fun SessionHarness.awaitSignedIn() = eventually {
        crash.crumbs.lastOrNull() == Breadcrumb.SessionChanged(Breadcrumb.SessionCrumb.SIGNED_IN)
    }

    @AfterTest fun tearDown() = harnesses.forEach { it.close() }

    @Test fun `state is signed in only with a user id`() = runBlocking {
        val h = harness()
        assertEquals(SessionState.SignedOut, h.repo.state.first())
        // An interrupted sign-in from a previous version can leave an incomplete session.
        h.tokens.flow.value = TokenStore.Session("", pair)
        assertEquals(SessionState.SignedOut, h.repo.state.first())
        h.tokens.flow.value = TokenStore.Session("u1", pair)
        assertEquals(SessionState.SignedIn("u1"), h.repo.state.first())
    }

    @Test fun `login epoch reaches consumers and refresh retains their context`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair, "login-1"))
        assertEquals(SessionState.SignedIn("u1", "login-1"), h.repo.state.first())
        h.tokens.flow.value = TokenStore.Session("u1", TokenPair("a2", "r2"), "login-1")
        assertEquals(SessionState.SignedIn("u1", "login-1"), h.repo.state.first())
        h.tokens.flow.value = TokenStore.Session("u1", pair, "login-2")
        assertEquals(SessionState.SignedIn("u1", "login-2"), h.repo.state.first())
    }

    @Test fun `an existing user's session is saved once with its id`() = runBlocking {
        val h = harness()
        h.repo.start(userId = "u42", tokens = pair, registered = false)

        assertEquals(TokenStore.Session("u42", pair), h.tokens.current())
        val log = h.log.all()
        assertEquals(listOf("save:u42"), log.filter { it.startsWith("save:") })
        assertTrue("track:auth.verified" in log)
        assertFalse("track:auth.registered" in log)
        assertFalse(h.repo.consumeJustRegistered())
    }

    @Test fun `sign-in sends an event without account parameters`() = runBlocking {
        val h = harness()
        h.repo.start(userId = "u1", tokens = pair, registered = false)
        assertEquals(listOf(Events.AUTH_VERIFIED to emptyMap()), h.tracker.events.toList())
    }

    @Test fun `the one-time sign-in state is dropped before the final session is saved`() = runBlocking {
        val h = harness()
        h.flow.phone = "+79161234567"
        h.flow.phoneRequestKey = "key-1"
        h.flow.registrationToken = "reg-1"
        var keyAtFinalSave: String? = "not saved"
        h.tokens.onSave = { if (it.userId.isNotEmpty()) keyAtFinalSave = h.flow.phoneRequestKey }

        h.repo.start(userId = "u1", tokens = pair, registered = true)

        assertNull(keyAtFinalSave)
        assertNull(h.flow.registrationToken)
        assertNull(h.flow.verification)
        assertEquals("+79161234567", h.flow.phone, "the number stays for the phone screen")
    }

    @Test fun `a registration is reported once to start the first upload`() = runBlocking {
        val h = harness()
        h.repo.start(userId = "u1", tokens = pair, registered = true)
        assertTrue("track:auth.registered" in h.log.all())
        assertTrue(h.repo.consumeJustRegistered())
        assertFalse(h.repo.consumeJustRegistered())
    }

    @Test fun `a successful session save clears only its own verification request`() = runBlocking {
        val h = harness()
        h.flow.phoneRequestKey = "key-1"
        h.tokens.onSave = { h.flow.phoneRequestKey = "key-2" }

        h.repo.start(userId = "u1", tokens = pair, registered = false)

        assertEquals("key-2", h.flow.phoneRequestKey)
    }

    @Test fun `sign-out clears the local session first and revokes the saved tokens afterwards`() = runBlocking {
        val h = harness()
        h.repo.start(userId = "u1", tokens = pair, registered = true)

        h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT)

        assertNull(h.tokens.current())
        assertFalse(h.repo.consumeJustRegistered(), "a pending first upload does not survive a sign-out")
        eventually { "logout:a1/r1" in h.log.all() }
        val log = h.log.all()
        assertEquals(listOf(Events.AUTH_REGISTERED to emptyMap(), Events.AUTH_LOGGED_OUT to emptyMap()), h.tracker.events.toList())
        assertTrue(log.indexOf("clear") < log.indexOf("logout:a1/r1"), log.toString())
        assertEquals(AuthRoutes.Phone.REASON_LOGGED_OUT, h.repo.consumeSignOutReason())
        assertNull(h.repo.consumeSignOutReason(), "the reason is consumed on read")
    }

    @Test fun `logout with an expired access token refreshes the pair and repeats`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        h.auth.logoutFailure = { access -> if (access == "a1") AppError.Unauthorized("TOKEN_EXPIRED") else null }

        h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT)

        eventually { "logout:a_fresh/r_fresh" in h.log.all() }
        val log = h.log.all()
        assertTrue(log.indexOf("logout:a1/r1") < log.indexOf("refresh:r1"), log.toString())
        assertTrue(log.indexOf("refresh:r1") < log.indexOf("logout:a_fresh/r_fresh"), log.toString())
    }

    @Test fun `a failing logout does not reach the caller`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        h.auth.logoutFailure = { AppError.Offline() }
        h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT)
        eventually { "logout:a1/r1" in h.log.all() }
        assertNull(h.tokens.current())
        assertFalse(h.log.all().any { it.startsWith("refresh:") }, "only 401 triggers a refresh")
    }

    @Test fun `sign-out without a session sends no logout`() = runBlocking {
        val h = harness()
        h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT)
        assertTrue("track:auth.logged_out" in h.log.all())
        Thread.sleep(100)
        assertFalse(h.log.all().any { it.startsWith("logout:") })
    }

    @Test fun `sign-out completes even when the calling screen is cancelled`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.tokens.beforeClear = { entered.complete(Unit); release.await() }
        val screen = CoroutineScope(Dispatchers.Default).launch { h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT) }
        withTimeout(5_000) { entered.await() }

        screen.cancelAndJoin() // PfApp drops the screen as soon as the session changes
        release.complete(Unit)

        eventually { "logout:a1/r1" in h.log.all() }
        assertNull(h.tokens.current())
    }

    @Test fun `local clearing sends no logout and no sign-out event`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        h.repo.clearLocal(AuthRoutes.Phone.REASON_DELETED)
        assertNull(h.tokens.current())
        Thread.sleep(100)
        val log = h.log.all()
        assertFalse(log.any { it.startsWith("logout:") }, log.toString())
        assertFalse("track:auth.logged_out" in log)
        assertEquals(AuthRoutes.Phone.REASON_DELETED, h.repo.consumeSignOutReason())
    }

    @Test fun `a session dropped by the network layer is reported as expired`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        h.awaitSignedIn()

        h.tokens.flow.value = null // the refresh was rejected

        eventually { h.crash.crumbs.lastOrNull() == Breadcrumb.SessionChanged(Breadcrumb.SessionCrumb.SIGNED_OUT) }
        assertTrue(h.tracker.events.isEmpty())
        assertEquals(AuthRoutes.Phone.REASON_EXPIRED, h.repo.consumeSignOutReason())
    }

    @Test fun `our own sign-out seen late by the session observer is not reported as expired`() = runBlocking {
        // FIN-74: the observer runs on its own coroutine and may get the `null` only after the sign-out returned
        // and the phone screen already consumed the reason.
        val h = harness(TokenStore.Session("u1", pair))
        h.awaitSignedIn()
        val nullReached = CompletableDeferred<Unit>()
        val deliverNull = CompletableDeferred<Unit>()
        h.tokens.beforeDeliver = { if (it == null) { nullReached.complete(Unit); deliverNull.await() } }

        h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT)
        assertEquals(AuthRoutes.Phone.REASON_LOGGED_OUT, h.repo.consumeSignOutReason())
        // Held until the observer has the `null`: set earlier, the state flow would conflate it into `u2` (FIN-172).
        withTimeout(5_000) { nullReached.await() }
        deliverNull.complete(Unit)
        // The observer handles values in order: once the next session is applied, the late `null` has been handled.
        h.tokens.flow.value = TokenStore.Session("u2", pair)
        eventually { h.crash.crumbs.size >= 3 }

        assertNull(h.repo.consumeSignOutReason(), "the next phone screen must not show «session expired»")
    }

    @Test fun `a local clearing seen late without a reason is not reported as expired`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        h.awaitSignedIn()
        val nullReached = CompletableDeferred<Unit>()
        val deliverNull = CompletableDeferred<Unit>()
        h.tokens.beforeDeliver = { if (it == null) { nullReached.complete(Unit); deliverNull.await() } }

        h.repo.clearLocal(reason = null)
        withTimeout(5_000) { nullReached.await() } // see above: `u2` must not conflate the `null` away
        deliverNull.complete(Unit)
        h.tokens.flow.value = TokenStore.Session("u2", pair)
        eventually { h.crash.crumbs.size >= 3 }

        assertNull(h.repo.consumeSignOutReason())
    }

    @Test fun `a session dropped by the network after an own sign-out is still reported as expired`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        h.awaitSignedIn()
        h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT)
        h.repo.consumeSignOutReason()
        // Signed in again only once the observer has the `null`: earlier, the state flow would conflate it (FIN-172).
        eventually { h.crash.crumbs.size >= 2 } // restored, signed out
        h.tokens.flow.value = TokenStore.Session("u1", TokenPair("a2", "r2")) // signed in again
        // Handled in order: once the new sign-in is applied, the sign-out's `null` has been handled too.
        eventually { h.crash.crumbs.size >= 3 }
        val changesBefore = h.crash.crumbs.size

        h.tokens.flow.value = null // the refresh was rejected

        eventually { h.crash.crumbs.size > changesBefore }
        assertEquals(AuthRoutes.Phone.REASON_EXPIRED, h.repo.consumeSignOutReason())
    }

    @Test fun `a sign-out absorbed by a quick sign-in of the same user does not hide a later expiry`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        h.awaitSignedIn()
        // The observer stalls on a value it is handling while the user signs out and
        // back in with the same account: the `null` in between is conflated away and never reaches it.
        val refreshed = TokenStore.Session("u1", TokenPair("a2", "r2"))
        val stalled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.tokens.beforeDeliver = { if (it == refreshed) { stalled.complete(Unit); release.await() } }
        h.tokens.flow.value = refreshed
        withTimeout(5_000) { stalled.await() }

        h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT)
        assertEquals(AuthRoutes.Phone.REASON_LOGGED_OUT, h.repo.consumeSignOutReason())
        h.repo.start(userId = "u1", tokens = TokenPair("a3", "r3"), registered = false)
        release.complete(Unit)
        eventually { h.crash.crumbs.size >= 3 } // restored, refreshed, signed in again — no `null` in between
        val changesBefore = h.crash.crumbs.size

        h.tokens.flow.value = null // the refresh was rejected
        eventually { h.crash.crumbs.size > changesBefore }
        // Handled in order: once the next session is applied, the `null` has been handled.
        h.tokens.flow.value = TokenStore.Session("u2", pair)
        eventually { h.crash.crumbs.size >= 3 }

        assertEquals(AuthRoutes.Phone.REASON_EXPIRED, h.repo.consumeSignOutReason())
    }

    @Test fun `a sign-in whose session failed to save after an own sign-out seen late is not reported as expired`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        h.awaitSignedIn()
        val refreshed = TokenStore.Session("u1", TokenPair("a2", "r2"))
        val stalled = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.tokens.beforeDeliver = { if (it == refreshed) { stalled.complete(Unit); release.await() } }
        h.tokens.flow.value = refreshed
        withTimeout(5_000) { stalled.await() }

        h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT)
        assertEquals(AuthRoutes.Phone.REASON_LOGGED_OUT, h.repo.consumeSignOutReason())
        h.tokens.onSave = { if (it.tokens.accessToken == "a3") throw java.io.IOException("disk full") }
        assertFailsWith<java.io.IOException> { h.repo.start(userId = "u1", tokens = TokenPair("a3", "r3"), registered = false) }
        val changesBefore = h.crash.crumbs.size
        release.complete(Unit)
        // The breadcrumb is emitted after the observer has decided on the reason.
        eventually {
            h.crash.crumbs.size > changesBefore &&
                h.crash.crumbs.lastOrNull() == Breadcrumb.SessionChanged(Breadcrumb.SessionCrumb.SIGNED_OUT)
        }

        assertNull(h.repo.consumeSignOutReason(), "the next phone screen must not show «session expired»")
    }

    @Test fun `clearing a pending sign-in does not hide a later expiry`() = runBlocking {
        val h = harness()
        h.tokens.flow.value = TokenStore.Session("", pair) // profile is being fetched
        h.repo.clearLocal(reason = null)
        h.tokens.flow.value = TokenStore.Session("u1", pair)
        h.awaitSignedIn()

        h.tokens.flow.value = null // the refresh was rejected

        eventually { h.repo.consumeSignOutReason() == AuthRoutes.Phone.REASON_EXPIRED }
    }

    @Test fun `a sign-out that failed to clear the session does not hide its later expiry`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        h.awaitSignedIn()
        h.tokens.beforeClear = { throw java.io.IOException("disk full") }
        assertFailsWith<java.io.IOException> { h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT) }
        h.repo.consumeSignOutReason()
        h.tokens.beforeClear = {}

        h.tokens.flow.value = null // the refresh was rejected later

        eventually { h.repo.consumeSignOutReason() == AuthRoutes.Phone.REASON_EXPIRED }
    }

    @Test fun `a restored session leaves the time zone alone`() {
        val h = harness(TokenStore.Session("u1", pair))
        h.awaitSignedIn()
        Thread.sleep(100)
        assertTrue(h.tracker.events.isEmpty())
        // FIN-91: the profile feature's TimeZoneSync sends the zone once it has held for 72 h, not every sign-in.
        assertTrue(h.log.all().isEmpty())
    }

    @Test fun `session changes leave breadcrumbs without user data`() {
        val h = harness(TokenStore.Session("", pair))
        eventually { h.crash.crumbs.isNotEmpty() }
        h.tokens.flow.value = TokenStore.Session("u1", pair)
        eventually { h.crash.crumbs.size >= 2 }
        h.tokens.flow.value = null
        eventually { h.crash.crumbs.size >= 3 }
        assertEquals(
            listOf(Breadcrumb.SessionCrumb.PENDING_USER, Breadcrumb.SessionCrumb.SIGNED_IN, Breadcrumb.SessionCrumb.SIGNED_OUT),
            h.crash.crumbs.map { (it as Breadcrumb.SessionChanged).state },
        )
    }
}
