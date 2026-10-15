package ru.finassist.pf.mock

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import ru.finassist.pf.core.api.model.Consent
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.mock.api.MockStatementsApi
import ru.finassist.pf.mock.ofx.OfxAccount
import ru.finassist.pf.mock.ofx.OfxDocument
import ru.finassist.pf.mock.ofx.OfxParser
import ru.finassist.pf.mock.ofx.OfxTransaction
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext

/** Shared helpers of the fake backend tests: a movable clock, a fast config and a tiny statement builder. */
internal class TestClock(var at: OffsetDateTime) : Clock {
    constructor(at: String) : this(OffsetDateTime.parse(at))

    override fun now() = at
    override val zone: ZoneId = Clock.MOSCOW
}

/** No simulated latency anywhere: tests wait for real work only. */
internal val FAST = MockConfig(
    networkDelayMs = 0,
    importStepDelayMs = 0,
    assistantChunkDelayMs = 0,
    callVerifyDelayMs = 0,
    preloadStatement = false,
)

internal const val NOW = "2026-10-06T12:00:00+03:00"

internal val fixtureText: String by lazy { File("src/main/assets/statements/fixture.ofx").readText() }

/**
 * A ready backend; [userId] non-empty = a signed-in user, [assistantConsent] = consent to the assistant given,
 * [dispatcher] — where its background jobs run ([ManualDispatcher] to decide when they do).
 */
internal fun testBackend(
    clock: Clock = TestClock(NOW),
    config: MockConfig = FAST,
    userId: String = "u1",
    assistantConsent: Boolean = false,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
): MockBackend {
    val backend = MockBackend(config, clock, dispatcher)
    backend.ready.complete(Unit)
    backend.profile = backend.emptyProfile("+79160000000", userId).let {
        if (assistantConsent) it.copy(consents = listOf(Consent(ConsentType.ASSISTANT, true, MockBackend.CONSENT_VERSION_ASSISTANT, MockBackend.CONSENT_VERSION_ASSISTANT))) else it
    }
    return backend
}

/** Imports [doc] synchronously, the way the start-up preload does. */
internal fun MockBackend.load(doc: OfxDocument, fileName: String = "statement.ofx"): MockBackend.Upload =
    runBlocking { MockStatementsApi(this@load).startUpload(fileName, doc, now().minusDays(1), instant = true) }

internal fun fixtureDoc(): OfxDocument = OfxParser.parse(fixtureText)

internal object Acc {
    val CURRENT = OfxAccount("40817810000000001111", "CHECKING")
    val SAVINGS = OfxAccount("42301810000000002222", "SAVINGS")
    val OTHER_BANK = OfxAccount("OB$40817810999900003333", "CHECKING")
    val ALL = listOf(CURRENT, SAVINGS, OTHER_BANK)
}

/** One statement line; [rub] in roubles, [at] as `yyyy-MM-ddTHH:mm` Moscow time. */
internal fun trn(
    id: String,
    at: String,
    rub: Long,
    name: String,
    memo: String,
    debit: Boolean = true,
    account: OfxAccount = Acc.CURRENT,
) = OfxTransaction(
    accountId = account.id, fitId = id, isDebit = debit, postedAt = OffsetDateTime.parse("$at:00+03:00"),
    amountMinor = rub * 100, name = name, memo = memo, currency = "RUB", lineNumber = 0,
)

/** A statement over [from]..[to] (Moscow dates, `yyyy-MM-dd`) with every test account. */
internal fun statement(from: String, to: String, vararg transactions: OfxTransaction) = OfxDocument(
    bankId = "T-BANK",
    accounts = Acc.ALL,
    transactions = transactions.toList(),
    unreadLines = emptyList(),
    coverageFrom = OffsetDateTime.parse("${from}T00:00:00+03:00"),
    coverageTo = OffsetDateTime.parse("${to}T23:59:59+03:00"),
)

/**
 * Holds dispatched work until [runAll]: a test decides when a background job (import, answer generation) gets to
 * run, so races of the zero-latency config are reproduced deterministically.
 */
internal class ManualDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(queue) { queue.addLast(block) }
    }

    /** Runs everything queued, including what the work queues meanwhile, on the calling thread. */
    fun runAll() {
        while (true) {
            val next = synchronized(queue) { queue.removeFirstOrNull() } ?: return
            next.run()
        }
    }
}
