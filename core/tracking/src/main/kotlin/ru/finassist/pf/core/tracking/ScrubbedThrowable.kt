package ru.finassist.pf.core.tracking

/**
 * A copy of an exception that is safe to send or log: the same type name and stack trace, but the message is
 * replaced by a machine-readable [code]. Exception messages are free text written by libraries and parsers and
 * can quote what they failed on (an operation title, an amount), so they stay out of reports and logs. Causes
 * are converted the same way, up to [MAX_CAUSES] deep.
 */
class ScrubbedThrowable private constructor(
    /** Fully qualified class name of the original exception. */
    val type: String,
    code: String?,
    cause: ScrubbedThrowable?,
) : Exception(if (code == null) type else "$type [$code]", cause) {

    companion object {
        const val MAX_CAUSES = 5

        /** Already scrubbed exceptions pass through unchanged. */
        fun of(original: Throwable, code: String? = null): ScrubbedThrowable =
            original as? ScrubbedThrowable ?: convert(original, code, depth = 0)

        private fun convert(original: Throwable, code: String?, depth: Int): ScrubbedThrowable {
            val cause = original.cause?.takeIf { it !== original && depth < MAX_CAUSES }
            return ScrubbedThrowable(
                type = original.javaClass.name,
                code = code,
                cause = cause?.let { convert(it, code = null, depth = depth + 1) },
            ).also { it.stackTrace = original.stackTrace }
        }
    }
}
