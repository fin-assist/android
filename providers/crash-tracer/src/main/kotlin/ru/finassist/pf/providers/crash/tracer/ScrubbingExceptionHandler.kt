package ru.finassist.pf.providers.crash.tracer

import ru.finassist.pf.core.tracking.ScrubbedThrowable

/**
 * Default uncaught-exception handler put in front of Tracer's: the crash goes on to Tracer (and through it to the
 * system handler that kills the process) as a [ScrubbedThrowable] — type and stack trace, no message. Messages
 * of crashes in parsers and the fake backend can quote the statement.
 *
 * [logOriginal] receives the original exception first, so a debuggable build still shows the full message in
 * logcat. It must not throw; whatever it does, the crash is passed on.
 *
 * The crash always reaches [next], which ends the process. Building the scrubbed copy allocates, and with the heap
 * exhausted it can throw itself: then a VM error (its message names sizes, never user data) goes on as is, and any
 * other crash as [unscrubbable], allocated up front.
 */
internal class ScrubbingExceptionHandler(
    private val next: Thread.UncaughtExceptionHandler?,
    private val scrub: (Throwable) -> Throwable = { ScrubbedThrowable.of(it) },
    private val logOriginal: (Thread, Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {

    private val unscrubbable = IllegalStateException("Uncaught exception could not be scrubbed")

    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            logOriginal(thread, error)
        } catch (_: Throwable) {
            // The log is best effort.
        }
        val forward = try {
            scrub(error)
        } catch (_: Throwable) {
            if (error is VirtualMachineError) error else unscrubbable
        }
        next?.uncaughtException(thread, forward)
    }

    companion object {
        /**
         * Wraps the current default handler. Called from `Application.onCreate`, after Tracer's handler is in
         * place: Tracer 1.5.0 installs it synchronously from its startup provider (`InitializationProvider` →
         * `TracerStartup.init` → `CrashReportInitializer` → `TracerCrashReport.init` →
         * `UncaughtExceptionHandlers.prependDefault`), and content providers are created before
         * `Application.onCreate`. A crash before this point (in another provider's `onCreate`) goes to Tracer
         * unscrubbed; the only other provider of the app is `androidx.startup`, which runs library code (emoji2,
         * lifecycle, profile installer) and handles no user data. Idempotent.
         */
        fun install(logOriginal: (Thread, Throwable) -> Unit) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current is ScrubbingExceptionHandler) return
            Thread.setDefaultUncaughtExceptionHandler(ScrubbingExceptionHandler(current, logOriginal = logOriginal))
        }
    }
}
