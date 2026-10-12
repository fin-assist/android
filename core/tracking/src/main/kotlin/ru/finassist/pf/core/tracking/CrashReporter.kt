package ru.finassist.pf.core.tracking

/**
 * Crash, ANR and non-fatal reporting (Tracer; logcat in builds without a Tracer token). Fatal crashes and ANRs
 * are collected by the provider on its own; the app only adds context through this interface.
 *
 * Everything the app adds here is typed: [Slug]s, enums and the classes below. There is no method that takes a
 * free-form string, so operation titles, amounts and phone numbers cannot end up in a report by accident.
 * Exceptions go out as [ScrubbedThrowable] (type and stack trace, no message) — both non-fatals sent through this
 * interface and fatal crashes, which the Tracer provider scrubs in its uncaught-exception handler.
 */
interface CrashReporter {
    /** A handled error worth knowing about (a degraded block, a failed secondary request). */
    fun report(error: NonFatal)

    /** A step of the session shown next to a crash: what the user opened, which config was applied. */
    fun breadcrumb(crumb: Breadcrumb)

    /** Our own `user_id`; `null` after sign-out and account deletion (the provider keeps the value across launches). */
    fun setUserId(userId: String?)

    /** A value attached to every later report; `null` removes it. */
    fun setKey(key: CrashKey, value: CrashValue?)
}

/**
 * A machine identifier: lowercase latin words of letters, digits and `_`, joined by dots, each starting with a
 * letter (`analytics`, `regular_payments`, `get_analytics`, `server_500`). User data never matches it — titles
 * have spaces or cyrillic, amounts and phone numbers start with a digit or `+`.
 */
@JvmInline
value class Slug private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 40
        private val pattern = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")

        /** @throws IllegalArgumentException when [value] is not a slug — a programming error at the call site. */
        fun of(value: String): Slug = orNull(value) ?: throw IllegalArgumentException("Not a slug: ${value.length} chars")

        fun orNull(value: String): Slug? = if (value.length <= MAX_LENGTH && pattern.matches(value)) Slug(value) else null
    }
}

enum class Severity {
    /** Degraded but working: a secondary request failed, a block was hidden. Does not affect crash-free. */
    WARNING,

    /** The scenario failed and the user saw an error. Does not affect crash-free. */
    ERROR,

    /** Counted as a crash in crash-free. Not for degradations of secondary requests. */
    FATAL,
}

/**
 * A handled error. Reports are grouped by [issueKey] — `<screen>.<block or endpoint>` — so one broken block is
 * one issue whatever the stack trace. [code] is the machine-readable reason ([crashCode]); the message of
 * [cause] is not sent, only its type and stack trace.
 */
class NonFatal(
    val screen: Slug,
    val part: Slug,
    val cause: Throwable,
    val severity: Severity = Severity.WARNING,
    val code: Slug? = null,
) {
    val issueKey: String get() = "${screen.value}.${part.value}"
}

sealed interface Breadcrumb {
    /** One line for the provider's log buffer. */
    fun render(): String

    /** The user moved to [screen] (a route name, never its arguments). */
    data class ScreenOpened(val screen: Slug) : Breadcrumb {
        override fun render(): String = "screen ${screen.value}"
    }

    /** A new feature-flag config took effect. */
    data class ConfigApplied(val version: CrashValue) : Breadcrumb {
        override fun render(): String = "config ${version.value}"
    }

    /**
     * The stored session changed: the app switches its graphs on it, so a screen that stays put after a sign-in
     * can be told apart from a session that never arrived (FIN-63). Never the user id or a token.
     */
    data class SessionChanged(val state: SessionCrumb) : Breadcrumb {
        override fun render(): String = "session ${state.name.lowercase()}"
    }

    enum class SessionCrumb { SIGNED_OUT, PENDING_USER, SIGNED_IN }
}

/**
 * Keys of the report context. The provider allows 15 keys with values up to [CrashValue.MAX_LENGTH] characters:
 * one for the config version, the rest for running experiments.
 */
@JvmInline
value class CrashKey private constructor(val name: String) {
    companion object {
        /** Version (hash) of the applied Remote Config. */
        val RC_VERSION = CrashKey("rc_ver")

        /** The provider keeps key names up to 31 characters and cuts longer ones, so two keys could merge. */
        const val MAX_NAME_LENGTH = 31
        private const val EXPERIMENT_PREFIX = "exp_"

        /** Longest experiment name that still fits a key. */
        const val MAX_EXPERIMENT_NAME_LENGTH = MAX_NAME_LENGTH - EXPERIMENT_PREFIX.length

        /**
         * Variant of a running experiment: `exp_<name>`. Remove it (`null` value) when the experiment ends.
         * @throws IllegalArgumentException when [name] is longer than [MAX_EXPERIMENT_NAME_LENGTH] — a programming
         * error: the experiment needs a shorter name.
         */
        fun experiment(name: Slug): CrashKey {
            require(name.value.length <= MAX_EXPERIMENT_NAME_LENGTH) { "Experiment name too long for a crash key: ${name.value}" }
            return CrashKey(EXPERIMENT_PREFIX + name.value.replace('.', '_'))
        }
    }
}

/** A context value: a config hash or an experiment variant. Lowercase letters, digits, `_`, `.`, `-`. */
@JvmInline
value class CrashValue private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        const val MAX_LENGTH = 31
        private val pattern = Regex("[a-z0-9_.-]+")

        fun of(value: String): CrashValue = orNull(value) ?: throw IllegalArgumentException("Not a crash value: ${value.length} chars")

        fun orNull(value: String): CrashValue? =
            if (value.length <= MAX_LENGTH && pattern.matches(value)) CrashValue(value) else null
    }
}

/**
 * Fans out to every provider of the build: Tracer, plus logcat in `mock`. A provider that throws must not
 * break the caller or keep the others from getting the report.
 */
class CompositeCrashReporter(private val reporters: Collection<CrashReporter>) : CrashReporter {
    override fun report(error: NonFatal) = each { it.report(error) }
    override fun breadcrumb(crumb: Breadcrumb) = each { it.breadcrumb(crumb) }
    override fun setUserId(userId: String?) = each { it.setUserId(userId) }
    override fun setKey(key: CrashKey, value: CrashValue?) = each { it.setKey(key, value) }

    private inline fun each(action: (CrashReporter) -> Unit) {
        reporters.forEach { reporter -> runCatching { action(reporter) } }
    }
}

/**
 * Screen name for [Breadcrumb.ScreenOpened] from a navigation route pattern: the route class without its package
 * and arguments, in snake case — `ru.finassist.pf.feature.analytics.api.AnalyticsRoutes.Period/{params}` →
 * `analytics.period`. Only the pattern is read, never the argument values. A name longer than [Slug.MAX_LENGTH]
 * keeps its innermost segments. `null` for a route it cannot name.
 */
fun screenSlug(routePattern: String?): Slug? {
    val qualifiedName = routePattern?.substringBefore('/')?.substringBefore('?') ?: return null
    val classNames = qualifiedName.split('.').filter { it.firstOrNull()?.isUpperCase() == true }
    if (classNames.isEmpty()) return null
    val segments = classNames.map { className ->
        className.removeSuffix("Routes").ifEmpty { className }
            .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_")
            .lowercase()
    }
    // Too long for a slug: keep the innermost segments (the screen itself) rather than lose the breadcrumb.
    for (from in segments.indices) {
        Slug.orNull(segments.drop(from).joinToString("."))?.let { return it }
    }
    return Slug.orNull(segments.last().take(Slug.MAX_LENGTH).trimEnd('_'))
}
