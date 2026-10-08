package ru.finassist.pf.feature.assistant.impl.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockRow
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChangedSince
import ru.finassist.pf.core.api.model.ChartKind
import ru.finassist.pf.core.api.model.ChartPoint
import ru.finassist.pf.core.api.model.ChartValueKind
import ru.finassist.pf.core.api.model.Chip
import ru.finassist.pf.core.api.model.ChipScreen
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.designsystem.theme.ChartIntroRegistry
import ru.finassist.pf.core.screenshot.DesignCheck
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Artboards `Chat`, `ChatEmpty`, `ChatBusy`, `ChatStale`, `ChatDark`, `ChatError`, `ChatErrorData`, `ChatOffline`,
 * `ChatLimit`: the Самокат answer (7 200 ₽ over April — September 2026) and its failure / limit states.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatDesignCheckTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 9, 25)
    private val asked = OffsetDateTime.parse("2026-09-25T19:10:00+03:00")
    private val calculated = OffsetDateTime.parse("2026-09-25T19:11:00+03:00")
    private val resets = OffsetDateTime.parse("2026-09-26T00:00:00+03:00")

    /** Chart month labels use the system zone; the fixture's month boundaries are Moscow midnights. */
    private val systemZone = TimeZone.getDefault()

    /**
     * The thread scrolls to its end on first composition, which interrupts the chart's bar intro; the mockup is
     * static, so the chart is drawn in its final state.
     */
    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        ChartIntroRegistry.claimIntro("assistant.a1.1")
    }

    @After fun tearDown() {
        TimeZone.setDefault(systemZone)
        ChartIntroRegistry.reset()
    }

    private fun month(m: Int) = DateRange(
        OffsetDateTime.parse("2026-%02d-01T00:00:00+03:00".format(m)),
        OffsetDateTime.parse("2026-%02d-01T00:00:00+03:00".format(m + 1)),
    )

    private val samokatRubles = listOf(0, 0, 0, 2880, 2350, 1970)

    private val chart = Block(
        type = BlockType.CHART,
        kind = ChartKind.BAR,
        valueKind = ChartValueKind.AMOUNT,
        altText = "Самокат по месяцам",
        points = samokatRubles.mapIndexed { i, rub ->
            val partial = i == samokatRubles.lastIndex
            ChartPoint(
                range = month(4 + i),
                value = rub * 100.0,
                coverage = if (partial) Coverage.PARTIAL else Coverage.COMPLETE,
                dataTo = if (partial) OffsetDateTime.parse("2026-09-25T19:11:00+03:00") else null,
            )
        },
        highlightIndex = 3,
    )

    private fun samokatAnswer(changedSince: List<ChangedSince> = emptyList()) = ChatItem.Answer(
        id = "a1",
        createdAt = calculated,
        status = AnswerStatus.COMPLETE,
        blocks = listOf(
            Block(
                type = BlockType.TEXT,
                text = "За апрель — сентябрь на Самокат ушло **7 200 ₽**, все операции — с июля. " +
                    "В среднем 2 400 ₽ в месяц, больше всего в июле: 2 880 ₽.",
            ),
            chart,
            Block(
                type = BlockType.ROWS,
                rows = listOf(BlockRow("Операций", count = 9), BlockRow("Средний чек", amount = Money(80_000))),
            ),
        ),
        chips = listOf(
            Chip("Самокат · 9 операций", ChipScreen.OPERATIONS, filters = OperationsFilter(q = "Самокат")),
            Chip("Категория «Супермаркеты»", ChipScreen.OPERATIONS, filters = OperationsFilter(categoryId = "groceries")),
            Chip("Аналитика за сентябрь", ChipScreen.ANALYTICS),
        ),
        source = AnswerSource(
            calculatedAt = calculated,
            dataRange = DateRange(month(4).from, month(9).to),
            transferMode = TransferMode.WITH,
            changedSince = changedSince,
        ),
    )

    private fun question(text: String) = ChatItem.Question("q1", asked, text)

    private fun limit(remaining: Int) = AssistantLimit(remaining = remaining, dailyMax = 5, resetsAt = resets)

    private val answered = ChatUiState(
        loading = false,
        items = listOf(question("Сколько я трачу на Самокат?"), samokatAnswer()),
        limit = limit(3),
    )

    private fun pending(text: String, answer: ChatItem.Answer) =
        answered.copy(items = listOf(question(text), answer))

    private val actions = ChatActions(
        onBack = {}, onReload = {}, onLoadOlder = {}, onRetryAnswer = {}, onRetryAfterFailure = {}, onDraft = {},
        onSend = {}, onVoice = {}, onOpenSearch = {}, onOpenAnalytics = {},
    )

    private fun capture(name: String, state: ChatUiState, dark: Boolean = false, heightDp: Int = 844) =
        // The thread is a viewport over a scrolling list, as on the artboard: no full-height growth.
        DesignCheck.capture(name, dark = dark, heightDp = heightDp, fullHeight = false) {
            // Opened at the end of the thread, as the app scrolls it once the history has loaded.
            val list = rememberLazyListState(initialFirstVisibleItemIndex = Int.MAX_VALUE)
            ChatContent(state, zone = zone, today = today, voiceListening = false, actions = actions, listState = list)
        }

    @Test fun chat() = capture("Chat", answered)

    @Test fun chatDark() = capture("ChatDark", answered, dark = true)

    @Test fun chatEmpty() = capture("ChatEmpty", ChatUiState(loading = false, limit = limit(5)))

    @Test fun chatBusy() = capture(
        "ChatBusy",
        pending("Сколько я трачу на заправки?", ChatItem.Answer("a1", calculated, AnswerStatus.GENERATING)),
    )

    @Test fun chatError() = capture(
        "ChatError",
        pending(
            "Сколько я трачу на заправки?",
            ChatItem.Answer("a1", calculated, AnswerStatus.FAILED, errorCode = AnswerErrorCode.MODEL_ERROR),
        ),
    )

    @Test fun chatErrorData() = capture(
        "ChatErrorData",
        pending(
            "Сколько я потрачу в следующем году на путешествия на Марс?",
            ChatItem.Answer("a1", calculated, AnswerStatus.FAILED, errorCode = AnswerErrorCode.CANNOT_ANSWER),
        ),
    )

    @Test fun chatStale() = capture(
        "ChatStale",
        answered.copy(items = listOf(question("Сколько я трачу на Самокат?"), samokatAnswer(listOf(ChangedSince.CATEGORIES)))),
    )

    @Test fun chatOffline() = capture("ChatOffline", answered.copy(offline = true), heightDp = 848)

    @Test fun chatLimit() = capture("ChatLimit", answered.copy(limit = limit(0)))
}
