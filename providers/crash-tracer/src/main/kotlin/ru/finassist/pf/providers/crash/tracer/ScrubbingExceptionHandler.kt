package ru.finassist.pf.providers.crash.tracer

import ru.finassist.pf.core.tracking.ScrubbedThrowable

/**
 * Default uncaught-exception handler put in front of Tracer's: the crash goes on to Tracer (and through it to the
 * system handler that kills the process) as a [ScrubbedThrowable] — type and stack trace, no message. Messages
 * of crashes in parsers and the fake backend can quote the statement.
 *
 * [logOriginal] receives the original exception first, so a debuggable build still shows the full message in
 * logcat. It must not throw; whatever it does, the crash is passed on.
 */
internal class ScrubbingExceptionHandler(
    private val next: Thread.UncaughtExceptionHandler?,
    private val logOriginal: (Thread, Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        runCatching { logOriginal(thread, error) }
        next?.uncaughtException(thread, ScrubbedThrowable.of(error))
    }

    companion object {
        /** Wraps the current default handler (Tracer's, installed by its startup provider). Idempotent. */
        fun install(logOriginal: (Thread, Throwable) -> Unit) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is ScrubbingExceptionHandler) return
            Thread.setDefaultUncaughtExceptionHandler(ScrubbingExceptionHandler(current, logOriginal))
        }
    }
}
