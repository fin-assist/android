package ru.finassist.pf.core.network.codes

/*
 * Open `code` sets from the contract. Every enum has an `Unknown` member and a `fromWire` that never throws:
 * a new server value must not break an installed client (api.md, «Нотация»). Behaviour for Unknown is the one
 * the contract prescribes for each field and is documented on the enum.
 */

/** `kind` of an operation. Unknown → amount shown without sign or colour. */
enum class OperationKind(val wire: String) {
    Expense("expense"), Income("income"), OwnTransfer("own_transfer"), Unknown("");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v && it != Unknown } ?: Unknown }
}

/** `status` of an operation. Unknown → treated as posted. */
enum class OperationStatus(val wire: String) {
    Posted("posted"), Pending("pending"), Unknown("");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v && it != Unknown } ?: Unknown }
}

/** `transfer_mode`. In responses Unknown → With; in requests only known values are sent. */
enum class TransferMode(val wire: String) {
    With("with"), Without("without");
    companion object { fun fromWireOrWith(v: String?) = entries.firstOrNull { it.wire == v } ?: With }
}

/** `coverage`. Unknown → Partial. */
enum class Coverage(val wire: String) {
    Complete("complete"), Partial("partial"), NoData("no_data");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: Partial }
}

/** `status` of a metric. Unknown → Locked without details. */
enum class MetricStatus(val wire: String) {
    Ready("ready"), Tentative("tentative"), Locked("locked"), None("none");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: Locked }
}

/** `lock.reason`. Unknown → lock without details. */
enum class LockReason(val wire: String) {
    NeedFullMonths("need_full_months"), NeedMonthsWithData("need_months_with_data"),
    TooEarlyInMonth("too_early_in_month"), StaleData("stale_data"), Unknown("");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v && it != Unknown } ?: Unknown }
}

/** `status` of an upload. Unknown → Done. */
enum class UploadStatus(val wire: String) {
    Processing("processing"), Done("done");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: Done }
}

/** Phone verification `status`. Unknown → the request failed, offer to start over. */
enum class VerificationStatus(val wire: String) {
    Pending("pending"), Verified("verified"), Expired("expired"), Unknown("");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v && it != Unknown } ?: Unknown }
}

/** Assistant message `status`. Unknown → Failed. */
enum class AnswerStatus(val wire: String) {
    Generating("generating"), Complete("complete"), Failed("failed");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: Failed }
}

/** Assistant stream / history error code. Unknown → ModelError (retry offered). */
enum class AnswerErrorCode(val wire: String) {
    ModelError("MODEL_ERROR"), CannotAnswer("CANNOT_ANSWER");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v } ?: ModelError }
}

/** Message `role`. Unknown → message not shown. */
enum class MessageRole(val wire: String) {
    User("user"), Assistant("assistant"), Unknown("");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v && it != Unknown } ?: Unknown }
}

/** Block `type`. Unknown → rendered as `alt_text`. */
enum class BlockType(val wire: String) {
    Text("text"), Chart("chart"), Rows("rows"), Unknown("");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v && it != Unknown } ?: Unknown }
}

/** Chip `screen`. Unknown → chip not shown. */
enum class ChipScreen(val wire: String) {
    Operations("operations"), Analytics("analytics"), Unknown("");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v && it != Unknown } ?: Unknown }
}

/** `changed_since` entries. Unknown → generic "data changed" text. */
enum class ChangedSince(val wire: String) {
    Categories("categories"), Uploads("uploads"), Unknown("");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v && it != Unknown } ?: Unknown }
}

/** Import result `notices`. Unknown → skipped. */
enum class ImportNotice(val wire: String) {
    EmptyStatement("empty_statement"), NoExpenses("no_expenses"), Unknown("");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v && it != Unknown } ?: Unknown }
}

/** `coverage.features[].feature` in the import result. Unknown → skipped. */
enum class ImportFeature(val wire: String) {
    Comparison("comparison"), MonthlyChart("monthly_chart"), Typical("typical"), RegularPayments("regular_payments"),
    NotableSpending("notable_spending"), SmallFrequent("small_frequent"), YearForecast("year_forecast"), Unknown("");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v && it != Unknown } ?: Unknown }
}

/** Category `kinds`. Unknown → skipped. */
enum class CategoryKind(val wire: String) {
    Expense("expense"), Income("income"), Unknown("");
    companion object { fun fromWire(v: String?) = entries.firstOrNull { it.wire == v && it != Unknown } ?: Unknown }
}

/** Consent document types. */
object ConsentType {
    const val PERSONAL_DATA = "personal_data"
    const val ASSISTANT = "assistant"
}
