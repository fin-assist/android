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
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.auth.impl.SessionHarness
import ru.finassist.pf.feature.auth.impl.eventually
import java.util.TimeZone
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

    @AfterTest fun tearDown() = harnesses.forEach { it.close() }

    @Test fun `state is signed in only with a user id`() = runBlocking {
        val h = harness()
        assertEquals(SessionState.SignedOut, h.repo.state.first())
        // The placeholder saved while the profile is being fetched is not a session yet.
        h.tokens.flow.value = TokenStore.Session("", pair)
        assertEquals(SessionState.SignedOut, h.repo.state.first())
        h.tokens.flow.value = TokenStore.Session("u1", pair)
        assertEquals(SessionState.SignedIn("u1"), h.repo.state.first())
    }

    @Test fun `an existing user's id is read from the profile before the session is saved`() = runBlocking {
        val h = harness()
        h.profile.userId = "u42"
        h.repo.start(userId = null, tokens = pair, registered = false)

        assertEquals(TokenStore.Session("u42", pair), h.tokens.current())
        val log = h.log.all()
        assertTrue(log.indexOf("save:") < log.indexOf("getProfile"), "placeholder first, so the profile call is authorized: $log")
        assertTrue(log.indexOf("getProfile") < log.indexOf("save:u42"), log.toString())
        assertTrue("track:auth.verified" in log)
        assertFalse("track:auth.registered" in log)
        assertFalse(h.repo.consumeJustRegistered())
    }

    @Test fun `identity is applied to flags, analytics and crash reports on sign-in`() = runBlocking {
        val h = harness()
        h.repo.start(userId = "u1", tokens = pair, registered = false)
        val log = h.log.all()
        assertTrue("flags.user:u1" in log && "tracker.user:u1" in log && "crash.user:u1" in log, log.toString())
    }

    @Test fun `a failed profile request drops the placeholder session and reaches the caller`() = runBlocking {
        val h = harness()
        h.profile.failure = AppError.Offline()
        assertFailsWith<AppError.Offline> { h.repo.start(userId = null, tokens = pair, registered = false) }
        assertNull(h.tokens.current())
        assertEquals(SessionState.SignedOut, h.repo.state.first())
        assertFalse(h.log.all().any { it.startsWith("track:") })
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

    @Test fun `sign-out clears the local session first and revokes the saved tokens afterwards`() = runBlocking {
        val h = harness()
        h.repo.start(userId = "u1", tokens = pair, registered = true)

        h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT)

        assertNull(h.tokens.current())
        assertFalse(h.repo.consumeJustRegistered(), "a pending first upload does not survive a sign-out")
        eventually { "logout:a1/r1" in h.log.all() }
        val log = h.log.all()
        val loggedOut = log.indexOf("track:auth.logged_out")
        assertTrue(loggedOut in 0 until log.lastIndexOf("tracker.user:null"), "event is attributed to the user: $log")
        assertTrue(log.indexOf("clear") < log.indexOf("logout:a1/r1"), log.toString())
        assertTrue("flags.user:null" in log && "crash.user:null" in log)
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
        assertTrue("tracker.user:null" in h.log.all())
    }

    @Test fun `local clearing sends no logout and no sign-out event`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        h.repo.clearLocal(AuthRoutes.Phone.REASON_DELETED)
        assertNull(h.tokens.current())
        Thread.sleep(100)
        val log = h.log.all()
        assertFalse(log.any { it.startsWith("logout:") }, log.toString())
        assertFalse("track:auth.logged_out" in log)
        assertTrue("tracker.user:null" in log)
        assertEquals(AuthRoutes.Phone.REASON_DELETED, h.repo.consumeSignOutReason())
    }

    @Test fun `a session dropped by the network layer is reported as expired`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        eventually { "flags.user:u1" in h.log.all() }

        h.tokens.flow.value = null // the refresh was rejected

        eventually { "flags.user:null" in h.log.all() }
        assertTrue("tracker.user:null" in h.log.all() && "crash.user:null" in h.log.all())
        assertEquals(AuthRoutes.Phone.REASON_EXPIRED, h.repo.consumeSignOutReason())
    }

    @Test fun `our own sign-out seen late by the session observer is not reported as expired`() = runBlocking {
        // FIN-74: the observer runs on its own coroutine and may get the `null` only after the sign-out returned
        // and the phone screen already consumed the reason.
        val h = harness(TokenStore.Session("u1", pair))
        eventually { "flags.user:u1" in h.log.all() }
        val deliverNull = CompletableDeferred<Unit>()
        h.tokens.beforeDeliver = { if (it == null) deliverNull.await() }

        h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT)
        assertEquals(AuthRoutes.Phone.REASON_LOGGED_OUT, h.repo.consumeSignOutReason())
        deliverNull.complete(Unit)
        // The observer handles values in order: once the next session is applied, the late `null` has been handled.
        h.tokens.flow.value = TokenStore.Session("u2", pair)
        eventually { "flags.user:u2" in h.log.all() }

        assertNull(h.repo.consumeSignOutReason(), "the next phone screen must not show «session expired»")
    }

    @Test fun `a sign-out during a slow identity setup leaves the identity reset`() = runBlocking {
        // The observer is still applying the restored user (a slow flags provider) when the user signs out:
        // the sign-out resets the identity first, then the observer finishes and sets the user again.
        val h = harness()
        val applying = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        h.flags.beforeSetUser = { if (it == "u1") { applying.complete(Unit); release.await() } }
        h.tokens.flow.value = TokenStore.Session("u1", pair)
        withTimeout(5_000) { applying.await() }

        h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT)
        release.complete(Unit)
        eventually { "tracker.user:u1" in h.log.all() } // the stalled setup has finished

        eventually { h.log.all().lastOrNull { it.startsWith("tracker.user:") } == "tracker.user:null" }
        eventually { h.log.all().lastOrNull { it.startsWith("flags.user:") } == "flags.user:null" }
        assertEquals(AuthRoutes.Phone.REASON_LOGGED_OUT, h.repo.consumeSignOutReason(), "not reported as expired")
    }

    @Test fun `a local clearing seen late without a reason is not reported as expired`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        eventually { "flags.user:u1" in h.log.all() }
        val deliverNull = CompletableDeferred<Unit>()
        h.tokens.beforeDeliver = { if (it == null) deliverNull.await() }

        h.repo.clearLocal(reason = null)
        deliverNull.complete(Unit)
        h.tokens.flow.value = TokenStore.Session("u2", pair)
        eventually { "flags.user:u2" in h.log.all() }

        assertNull(h.repo.consumeSignOutReason())
    }

    @Test fun `a session dropped by the network after an own sign-out is still reported as expired`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        eventually { "flags.user:u1" in h.log.all() }
        h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT)
        h.repo.consumeSignOutReason()
        h.tokens.flow.value = TokenStore.Session("u1", TokenPair("a2", "r2")) // signed in again
        // Handled in order: once the new sign-in is applied, the sign-out's `null` has been handled too.
        eventually { h.log.all().count { it == "flags.user:u1" } == 2 }
        val resetsBefore = h.log.all().count { it == "flags.user:null" }

        h.tokens.flow.value = null // the refresh was rejected

        eventually { h.log.all().count { it == "flags.user:null" } == resetsBefore + 1 }
        assertEquals(AuthRoutes.Phone.REASON_EXPIRED, h.repo.consumeSignOutReason())
    }

    @Test fun `a sign-out absorbed by a quick sign-in of the same user does not hide a later expiry`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        eventually { "flags.user:u1" in h.log.all() }
        // The observer stalls on a value it is handling (as in a slow `applyIdentity`) while the user signs out and
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
        val resetsBefore = h.log.all().count { it == "flags.user:null" }

        h.tokens.flow.value = null // the refresh was rejected
        eventually { h.crash.crumbs.size >= 4 }
        // Handled in order: once the next session is applied, the `null` has been handled.
        h.tokens.flow.value = TokenStore.Session("u2", pair)
        eventually { "flags.user:u2" in h.log.all() }

        assertEquals(AuthRoutes.Phone.REASON_EXPIRED, h.repo.consumeSignOutReason())
        assertEquals(resetsBefore + 1, h.log.all().count { it == "flags.user:null" }, "identity reset on the expiry")
    }

    @Test fun `clearing a pending sign-in does not hide a later expiry`() = runBlocking {
        val h = harness()
        h.tokens.flow.value = TokenStore.Session("", pair) // profile is being fetched
        h.repo.clearLocal(reason = null)
        h.tokens.flow.value = TokenStore.Session("u1", pair)
        eventually { "flags.user:u1" in h.log.all() }

        h.tokens.flow.value = null // the refresh was rejected

        eventually { h.repo.consumeSignOutReason() == AuthRoutes.Phone.REASON_EXPIRED }
    }

    @Test fun `a sign-out that failed to clear the session does not hide its later expiry`() = runBlocking {
        val h = harness(TokenStore.Session("u1", pair))
        eventually { "flags.user:u1" in h.log.all() }
        h.tokens.beforeClear = { throw java.io.IOException("disk full") }
        assertFailsWith<java.io.IOException> { h.repo.signOut(AuthRoutes.Phone.REASON_LOGGED_OUT) }
        h.repo.consumeSignOutReason()
        h.tokens.beforeClear = {}

        h.tokens.flow.value = null // the refresh was rejected later

        eventually { h.repo.consumeSignOutReason() == AuthRoutes.Phone.REASON_EXPIRED }
    }

    @Test fun `a restored session applies the identity and syncs a changed time zone`() {
        val h = harness(TokenStore.Session("u1", pair))
        h.profile.timezone = "Asia/Vladivostok-not-this-device"
        eventually { h.profile.updates.isNotEmpty() }
        assertTrue("flags.user:u1" in h.log.all())
        assertEquals(TimeZone.getDefault().id, h.profile.updates.single().timezone)
    }

    @Test fun `an unchanged time zone is not sent`() {
        val h = harness()
        h.profile.timezone = TimeZone.getDefault().id
        h.tokens.flow.value = TokenStore.Session("u1", pair)
        eventually { "getProfile" in h.log.all() }
        Thread.sleep(100)
        assertTrue(h.profile.updates.isEmpty())
    }

    @Test fun `a failed time zone check is silent`() {
        val h = harness()
        h.profile.failure = AppError.Offline()
        h.tokens.flow.value = TokenStore.Session("u1", pair)
        eventually { "crash.user:u1" in h.log.all() }
        Thread.sleep(100)
        assertTrue(h.profile.updates.isEmpty())
        assertNull(h.repo.consumeSignOutReason())
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
