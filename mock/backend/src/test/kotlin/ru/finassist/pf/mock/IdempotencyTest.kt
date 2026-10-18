package ru.finassist.pf.mock

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.common.error.AppError
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdempotencyTest {
    private val backend = testBackend()

    @Test fun `a repeat while the first request runs is in progress, after it the cached answer`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            backend.idempotent("k") { gate.await(); "first" }
        }
        assertFailsWith<AppError.InProgress> { backend.idempotent("k") { "second" } }
        gate.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("first", backend.idempotent("k") { "third" })
    }

    @Test fun `a request cancelled while the lock is busy does not leave its key in progress`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val request = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            backend.idempotent("k") { started.complete(Unit); CompletableDeferred<Unit>().await(); "never" }
        }
        started.await()
        // The cancelled request's cleanup has to wait for the lock (e.g. held by an answer being generated).
        backend.mutex.lock()
        val cancelled = launch(Dispatchers.Default) { request.cancelAndJoin() }
        Thread.sleep(100)
        backend.mutex.unlock()
        withTimeout(5_000) { cancelled.join() }

        assertEquals("again", backend.idempotent("k") { "again" })
    }

    @Test fun `a repeat with another request is IDEMPOTENCY_CONFLICT, with the same one the cached answer`() = runBlocking {
        assertEquals("first", backend.idempotent("k", request = "a") { "first" })
        assertEquals("first", backend.idempotent("k", request = "a") { "second" })
        val e = assertFailsWith<AppError.Api> { backend.idempotent("k", request = "b") { "third" } }
        assertEquals(ErrorCodes.IDEMPOTENCY_CONFLICT to 409, e.code to e.httpStatus)
        // An endpoint that does not pass its request is not compared.
        assertEquals("first", backend.idempotent("k") { "fourth" })
    }

    @Test fun `a failed request does not keep its key`() = runBlocking {
        assertFailsWith<AppError.Offline> { backend.idempotent("k") { throw AppError.Offline() } }
        assertEquals("ok", backend.idempotent("k") { "ok" })
    }
}
