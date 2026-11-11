package ru.finassist.pf.providers.telemetry.appmetrica

/**
 * The SDK transformer only controls the report sent to AppMetrica. Install this before activation so the SDK's
 * handler also delegates to Android with a scrubbed exception for its system crash report. Android's logging
 * pre-handler runs first and can still print the original exception to logcat.
 */
internal class ScrubbingExceptionHandler(
    private val next: Thread.UncaughtExceptionHandler,
) : Thread.UncaughtExceptionHandler {
    private val unscrubbable = IllegalStateException("Uncaught exception could not be scrubbed")

    override fun uncaughtException(thread: Thread, error: Throwable) {
        next.uncaughtException(thread, scrubCrash(error) ?: unscrubbable)
    }

    companion object {
        fun install() {
            val current = Thread.getDefaultUncaughtExceptionHandler() ?: return
            if (current is ScrubbingExceptionHandler) return
            Thread.setDefaultUncaughtExceptionHandler(ScrubbingExceptionHandler(current))
        }
    }
}
