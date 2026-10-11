package ru.finassist.pf.providers.crash.tracer

import ru.finassist.pf.core.tracking.ScrubbedThrowable
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScrubbingExceptionHandlerTest {
    private val previous = Thread.getDefaultUncaughtExceptionHandler()

    @AfterTest fun restore() = Thread.setDefaultUncaughtExceptionHandler(previous)

    private class Recording : Thread.UncaughtExceptionHandler {
        var received: Throwable? = null
        override fun uncaughtException(t: Thread, e: Throwable) { received = e }
    }

    @Test fun `the next handler gets the crash without its message`() {
        val next = Recording()
        val original = IllegalStateException("bad row: Перевод Иванову 15000")
        ScrubbingExceptionHandler(next) { _, _ -> }.uncaughtException(Thread.currentThread(), original)

        val sent = assertIs<ScrubbedThrowable>(next.received)
        assertEquals("java.lang.IllegalStateException", sent.message)
        assertTrue(sent.stackTrace.contentEquals(original.stackTrace))
    }

    @Test fun `the original goes to the local log first`() {
        var logged: Throwable? = null
        val original = RuntimeException("x")
        ScrubbingExceptionHandler(Recording()) { _, e -> logged = e }.uncaughtException(Thread.currentThread(), original)
        assertSame(original, logged)
    }

    @Test fun `a failing log does not stop the crash from being passed on`() {
        val next = Recording()
        ScrubbingExceptionHandler(next) { _, _ -> error("log") }.uncaughtException(Thread.currentThread(), RuntimeException())
        assertIs<ScrubbedThrowable>(next.received)
    }

    @Test fun `install wraps the current default handler once`() {
        val tracer = Recording()
        Thread.setDefaultUncaughtExceptionHandler(tracer)
        ScrubbingExceptionHandler.install { _, _ -> }
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        ScrubbingExceptionHandler.install { _, _ -> }
        assertSame(installed, Thread.getDefaultUncaughtExceptionHandler())

        installed!!.uncaughtException(Thread.currentThread(), RuntimeException("secret"))
        assertIs<ScrubbedThrowable>(tracer.received)
    }
}
