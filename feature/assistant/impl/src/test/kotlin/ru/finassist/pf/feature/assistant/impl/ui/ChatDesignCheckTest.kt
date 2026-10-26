package ru.finassist.pf.feature.assistant.impl.ui

import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import androidx.compose.foundation.lazy.rememberLazyListState
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.AnswerSource
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockRow
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChangedSince
import ru.finassist.pf.core.api.model.ChartKind
import ru.finassist.pf.core.api.model.ChartPoint
import ru.finassist.pf.core.api.model.ChartData
import ru.finassist.pf.core.api.model.RowsData
import ru.finassist.pf.core.api.model.ValueKind
import ru.finassist.pf.core.api.model.Chip
import ru.finassist.pf.core.api.model.ChipScreen
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.designsystem.theme.ChartIntroRegistry
import ru.finassist.pf.core.screenshot.DesignCheck
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Artboards `Chat`, `ChatEmpty`, `ChatBusy`, `ChatStale`, `ChatDark`, `ChatError`, `ChatErrorData`, `ChatOffline`,
 * `ChatLimit`, `ChatOffTopic`, `ChatOffTopicDark`, `ChatErrorGeneric` and `ChatErrorGenericDark` (the last four —
 * FIN-160 states, artboards FIN-173): the Самокат answer (7 200 ₽ over April — September 2026) and its failure / limit
 * states.
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

    private val chart = Block.of(
        BlockType.CHART, ChartData.serializer(),
        ChartData(
            kind = ChartKind.BAR,
            valueKind = ValueKind.AMOUNT,
            points = samokatRubles.mapIndexed { i, rub ->
                val partial = i == samokatRubles.lastIndex
                ChartPoint(
                    range = month(4 + i),
                    value = rub * 100.0,
                    coverage = if (partial) Coverage.PARTIAL else Coverage.COMPLETE,
                    dataTo = if (partial) OffsetDateTime.parse("2026-09-25T19:11:00+03:00") else null,
                    note = if (partial) "по 25-е" else null,
                )
            },
            highlightIndex = 3,
        ),
    ).copy(altText = "Самокат по месяцам")

    private fun samokatAnswer(changedSince: List<ChangedSince> = emptyList()) = ChatItem.Answer(
        id = "a1",
        createdAt = calculated,
        status = AnswerStatus.COMPLETE,
        blocks = listOf(
            Block.text(
                "За апрель — сентябрь на Самокат ушло **7 200 ₽**, все операции — с июля. " +
                    "В среднем 2 400 ₽ в месяц, больше всего в июле: 2 880 ₽.",
            ),
            chart,
            Block.of(
                BlockType.ROWS, RowsData.serializer(),
                RowsData(listOf(BlockRow("Операций", value = 9.0, valueKind = ValueKind.COUNT), BlockRow("Средний чек", value = 80_000.0, valueKind = ValueKind.AMOUNT))),
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
            ChatItem.Answer("a1", calculated, AnswerStatus.FAILED, errorText = "Не получилось ответить. Вопрос не потрачен", retryable = true),
        ),
    )

    @Test fun chatErrorData() = capture(
        "ChatErrorData",
        pending(
            "Сколько я потрачу в следующем году на путешествия на Марс?",
            ChatItem.Answer(
                "a1", calculated, AnswerStatus.FAILED,
                errorText = "Не получилось ответить — недостаточно данных, переформулируйте вопрос. Вопрос не потрачен",
            ),
        ),
    )

    /**
     * OFF_TOPIC: the server's text without «Повторить», shown as any failed answer (warning notice; owner's decision
     * on FIN-173).
     */
    private val offTopic = pending(
        "Какая погода будет завтра?",
        ChatItem.Answer(
            "a1", calculated, AnswerStatus.FAILED,
            errorText = "Вопрос не по теме — помощник отвечает только на вопросы о ваших финансах. " +
                "С другими вопросами помогут универсальные ассистенты: Алиса AI, GigaChat, ChatGPT, Claude, DeepSeek",
        ),
    )

    @Test fun chatOffTopic() = capture("ChatOffTopic", offTopic)

    @Test fun chatOffTopicDark() = capture("ChatOffTopicDark", offTopic, dark = true)

    /**
     * A server older than FIN-159 sends no text: the generic «Не получилось ответить», «Повторить» by the MODEL_ERROR
     * fallback.
     */
    private val errorGeneric =
        pending("Сколько я трачу на заправки?", ChatItem.Answer("a1", calculated, AnswerStatus.FAILED, errorText = null, retryable = true))

    @Test fun chatErrorGeneric() = capture("ChatErrorGeneric", errorGeneric)

    @Test fun chatErrorGenericDark() = capture("ChatErrorGenericDark", errorGeneric, dark = true)

    @Test fun chatStale() = capture(
        "ChatStale",
        answered.copy(items = listOf(question("Сколько я трачу на Самокат?"), samokatAnswer(listOf(ChangedSince.CATEGORIES)))),
    )

    @Test fun chatOffline() = capture("ChatOffline", answered.copy(offline = true), heightDp = 853)

    @Test fun chatLimit() = capture("ChatLimit", answered.copy(limit = limit(0)))
    // FIN-223 fixture: proposed artboard; snapshots are not executed under the current instruction.
    @Test fun serverRefusal() = capture("ChatServerRefusal", answered.copy(draft = "Вопрос о расходах", errorPresentation = ErrorPresentation(UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"), ErrorSeverity.WARNING), requestRetryable = false, askRetryReady = false, askBlocked = true))
    @Test fun serverRefusalDark() = capture("ChatServerRefusalDark", answered.copy(draft = "Вопрос о расходах", errorPresentation = ErrorPresentation(UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"), ErrorSeverity.WARNING), requestRetryable = false, askRetryReady = false, askBlocked = true), dark = true)

    @Test fun askRecovery() = capture("ChatAskRecovery", answered.copy(draft = "Вопрос о расходах", askBlocked = true,
        recovery = ChatRecovery(ChatRecoveryTarget.ASK)))
    @Test fun askRecoveryDark() = capture("ChatAskRecoveryDark", answered.copy(draft = "Вопрос о расходах", askBlocked = true,
        recovery = ChatRecovery(ChatRecoveryTarget.ASK)), dark = true)
    @Test fun retryRecovery() = capture("ChatRetryRecovery", answered.copy(
        items = listOf(ChatItem.Answer("a1", calculated, AnswerStatus.FAILED, retryable = true)),
        recovery = ChatRecovery(ChatRecoveryTarget.RETRY_ANSWER, "a1", historyChecked = true)))
}
