package ru.finassist.pf.core.tracking

/**
 * Product analytics events (MyTracker in `prod`, logcat in `mock`). Event names follow the flag naming
 * scheme — `<feature>.<object>.<action>` — so a flag and the events of the thing it gates share a prefix.
 * Parameters never carry personal data (no phone numbers, no operation titles), only ids and codes.
 */
interface Tracker {
    fun track(event: Event, params: Map<String, String> = emptyMap())

    /** Our own `user_id`; `null` after sign-out. */
    fun setUserId(userId: String?)
}

/** Crash and ANR reporting (AppMetrica in `prod`). Non-fatal errors are reported with [report]. */
interface CrashReporter {
    fun report(throwable: Throwable, message: String? = null)
    fun log(message: String)
    fun setUserId(userId: String?)
}

@JvmInline
value class Event(val name: String)

/** Event registry. Add here, not inline at the call site, so the names stay greppable and documented. */
object Events {
    val AUTH_PHONE_SUBMITTED = Event("auth.phone.submitted")
    val AUTH_CALL_STARTED = Event("auth.call.started")
    val AUTH_VERIFIED = Event("auth.verified")
    val AUTH_REGISTERED = Event("auth.registered")
    val AUTH_LOGGED_OUT = Event("auth.logged_out")

    val APPLOCK_PASSCODE_SET = Event("applock.passcode.set")
    val APPLOCK_BIOMETRIC_ENABLED = Event("applock.biometric.enabled")
    val APPLOCK_UNLOCK_FAILED = Event("applock.unlock.failed")

    val STATEMENTS_UPLOAD_STARTED = Event("statements.upload.started")
    val STATEMENTS_UPLOAD_COMPLETED = Event("statements.upload.completed")
    val STATEMENTS_UPLOAD_FAILED = Event("statements.upload.failed")
    val STATEMENTS_UPLOAD_CANCELLED = Event("statements.upload.cancelled")
    val STATEMENTS_UPLOAD_DELETED = Event("statements.upload.deleted")

    val OPERATIONS_FEED_OPENED = Event("operations.feed.opened")
    val OPERATIONS_SEARCH_SUBMITTED = Event("operations.search.submitted")
    val OPERATIONS_DETAIL_OPENED = Event("operations.detail.opened")
    val OPERATIONS_CATEGORY_CHANGED = Event("operations.category.changed")

    val ANALYTICS_OPENED = Event("analytics.opened")
    val ANALYTICS_PERIOD_CHANGED = Event("analytics.period.changed")
    val ANALYTICS_TRANSFERS_CHANGED = Event("analytics.filter.transfers.changed")
    val ANALYTICS_REGULAR_DISMISSED = Event("analytics.regular_payment.dismissed")
    val ANALYTICS_TILE_OPENED = Event("analytics.tile.opened")

    val ASSISTANT_OPENED = Event("assistant.opened")
    val ASSISTANT_QUESTION_SENT = Event("assistant.question.sent")
    val ASSISTANT_ANSWER_DONE = Event("assistant.answer.done")
    val ASSISTANT_ANSWER_FAILED = Event("assistant.answer.failed")
    val ASSISTANT_CONSENT_GRANTED = Event("assistant.consent.granted")
    val ASSISTANT_CONSENT_REVOKED = Event("assistant.consent.revoked")
    val ASSISTANT_CHIP_OPENED = Event("assistant.chip.opened")

    val PROFILE_THEME_CHANGED = Event("profile.theme.changed")
    val PROFILE_ACCOUNT_DELETED = Event("profile.account.deleted")
}

object NoopTracker : Tracker {
    override fun track(event: Event, params: Map<String, String>) = Unit
    override fun setUserId(userId: String?) = Unit
}

object NoopCrashReporter : CrashReporter {
    override fun report(throwable: Throwable, message: String?) = Unit
    override fun log(message: String) = Unit
    override fun setUserId(userId: String?) = Unit
}
