package ru.finassist.pf.feature.assistant.impl.ui

import ru.finassist.pf.core.designsystem.components.ErrorMessage
import androidx.compose.runtime.getValue
import ru.finassist.pf.core.designsystem.theme.LocalPfClock
import androidx.compose.foundation.layout.Arrangement
import ru.finassist.pf.core.designsystem.components.PfTestTags
import ru.finassist.pf.core.designsystem.components.PfLimitMeter
import ru.finassist.pf.core.designsystem.components.PfDivider
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChartData
import ru.finassist.pf.core.api.model.RowsData
import ru.finassist.pf.core.blocks.BlockChartBars
import ru.finassist.pf.core.api.model.ChipScreen
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.designsystem.components.ChatStatusTone
import ru.finassist.pf.core.designsystem.components.ComposerState
import ru.finassist.pf.core.designsystem.components.PfAssistantMessage
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfChatComposer
import ru.finassist.pf.core.designsystem.components.PfChatDay
import ru.finassist.pf.core.designsystem.components.PfChatStatus
import ru.finassist.pf.core.designsystem.components.PfChip
import ru.finassist.pf.core.designsystem.components.PfChipRow
import ru.finassist.pf.core.designsystem.components.PfDataRow
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfNoticeLink
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.components.PfUserMessage
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.assistant.impl.domain.AnswerFormat
import ru.finassist.pf.feature.assistant.impl.domain.TextPart
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onOpenConsent: () -> Unit,
    onOpenSearch: (OperationsFilter) -> Unit,
    onOpenAnalytics: (AnalyticsParams) -> Unit,
    consentResult: State<String?>,
    onConsentResultConsumed: () -> Unit,
    vm: ChatViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clock = LocalPfClock.current
    val zone = clock.zone

    LifecycleResumeEffect(Unit) {
        vm.resumeStreams()
        onPauseOrDispose { vm.pauseStreams() }
    }
    LaunchedEffect(state.event) {
        if (state.event == ChatEvent.OPEN_CONSENT) {
            vm.consumeEvent()
            onOpenConsent()
        }
    }
    val consent by consentResult
    LaunchedEffect(consent) {
        if (consent == "true") {
            onConsentResultConsumed()
            vm.onConsentGranted()
        }
    }
    val voice = rememberVoiceInput(onResult = { vm.send(voiceText = it) })
    ChatContent(
        state = state,
        zone = zone,
        // Read on every recomposition, as before FIN-71: a chat left open past midnight relabels its days.
        today = clock.today(),
        voiceListening = voice.listening,
        actions = remember(vm, voice.start, onBack, onOpenSearch, onOpenAnalytics) {
            ChatActions(
                onBack = onBack,
                onReload = vm::load,
                onLoadOlder = vm::loadOlder,
                onRetryAnswer = vm::retry,
                onRetryAfterFailure = vm::retryAfterFailure,
                onDraft = vm::setDraft,
                onSend = { vm.send() },
                onVoice = voice.start,
                onOpenSearch = onOpenSearch,
                onOpenAnalytics = onOpenAnalytics,
            )
        },
    )
}

/** Chat callbacks; the ViewModel-free [ChatContent] gets them from [ChatScreen] or a design-check test. */
@Immutable
internal class ChatActions(
    val onBack: () -> Unit,
    val onReload: () -> Unit,
    val onLoadOlder: () -> Unit,
    val onRetryAnswer: (answerId: String) -> Unit,
    val onRetryAfterFailure: () -> Unit,
    val onDraft: (String) -> Unit,
    val onSend: () -> Unit,
    val onVoice: () -> Unit,
    val onOpenSearch: (OperationsFilter) -> Unit,
    val onOpenAnalytics: (AnalyticsParams) -> Unit,
)

@Composable
internal fun ChatContent(
    state: ChatUiState,
    zone: ZoneId,
    today: LocalDate,
    voiceListening: Boolean,
    actions: ChatActions,
    listState: LazyListState = rememberLazyListState(),
) {
    val d = PfTheme.dimens
    // Keep the newest message in view as the thread grows at the bottom or an answer streams in. Keyed by the
    // last item, not the count: «Показать раньше» prepends history and must keep the reader where they are.
    LaunchedEffect(state.items.lastOrNull()?.id, (state.items.lastOrNull() as? ChatItem.Answer)?.blocks?.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }

    Column(Modifier.fillMaxSize().imePadding().testTag(AssistantTags.CHAT)) {
        val limit = state.limit
        // The remaining questions are in the meter above the composer (mockup `Chat`).
        PfPageHeader("Помощник", onBack = actions.onBack, subtitle = "Отвечает по вашим операциям")
        state.limitErrorPresentation?.let {
            ErrorMessage(it, "Не получилось обновить лимит вопросов", modifier = Modifier.padding(PfTheme.dimens.space4))
        }
        state.loadErrorPresentation?.let { presentation ->
            ErrorMessage(presentation, "Не получилось загрузить диалог. Попробуйте ещё раз", modifier = Modifier.padding(d.space4),
                onRetry = if (!state.loadRetryReady) null else actions.onReload)
        }
        state.streamErrorPresentation?.let {
            ErrorMessage(it, "Не получилось получить ответ. Попробуйте ещё раз",
                modifier = Modifier.padding(horizontal = d.space5, vertical = d.space2),
                onRetry = if (state.streamRetryable == false) null else actions.onRetryAfterFailure)
        }
        state.olderErrorPresentation?.let {
            ErrorMessage(it, "Не получилось загрузить предыдущие сообщения", onRetry = if (!state.olderRetryReady) null else actions.onLoadOlder,
                modifier = Modifier.padding(horizontal = d.space5, vertical = d.space2))
        }
        when {
            state.loading -> Column(Modifier.weight(1f)) {}
            state.loadFailed && state.loadErrorPresentation != null -> Column(Modifier.weight(1f)) {}
            state.loadFailed -> Column(Modifier.weight(1f)) {
                PfEmptyState(PfIcons.ALERT, "Не получилось загрузить диалог", "Проверьте интернет и попробуйте ещё раз") {
                    PfLink("Повторить", onClick = actions.onReload)
                }
            }
            // An empty dialog: the empty state in the middle (mockup `ChatEmpty`).
            state.items.isEmpty() && !state.hasOlder -> Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = d.space5, vertical = d.space4),
                verticalArrangement = Arrangement.Center,
            ) {
                PfEmptyState(
                    PfIcons.MESSAGE, "Спросите о своих тратах и доходах", "Помощник отвечает по вашим операциям. 5 вопросов в день",
                    modifier = Modifier.testTag(AssistantTags.EMPTY),
                )
            }
            // The thread sits at the bottom, over the meter, while it is shorter than the screen (`justify-content: flex-end`).
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = d.space5, vertical = d.space4),
                verticalArrangement = Arrangement.spacedBy(d.space4, Alignment.Bottom),
            ) {
                if (state.hasOlder && state.olderRetryReady) {
                    item(key = "older") { PfLink(if (state.loadingOlder) "Загружаем…" else "Показать раньше", onClick = actions.onLoadOlder) }
                }
                var lastDay: LocalDate? = null
                state.items.forEach { item ->
                    val day = item.createdAt.atZoneSameInstant(zone).toLocalDate()
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "day-$day-${item.id}") { PfChatDay(dayLabel(day, today)) }
                    }
                    item(key = item.id) {
                        when (item) {
                            is ChatItem.Question -> PfUserMessage(item.text, Modifier.testTag(AssistantTags.QUESTION))
                            is ChatItem.Answer -> Answer(item, zone, requestRetryBlocked = state.blockedRetryAnswerId == item.id || state.retryReadyByAnswer[item.id] == false, onRetry = { actions.onRetryAnswer(item.id) }, onOpenSearch = actions.onOpenSearch, onOpenAnalytics = actions.onOpenAnalytics)
                        }
                    }
                }
            }
        }
        if (voiceListening) {
            Text("Слушаю…", style = PfTheme.type.caption, color = PfTheme.colors.textMuted, modifier = Modifier.padding(horizontal = d.space5, vertical = d.space1))
        }
        if (state.errorPresentation != null && !state.offline) {
            ErrorMessage(state.errorPresentation, "Не получилось отправить вопрос",
                modifier = Modifier.padding(horizontal = d.space5, vertical = d.space2).testTag(AssistantTags.SEND_FAILED),
                onRetry = state.failedRetryAnswerId?.let { id ->
                    if (state.retryReadyByAnswer[id] == false) null else ({ actions.onRetryAnswer(id) })
                } ?: if (state.failedRetryAnswerId != null || !state.askRetryReady) null else actions.onRetryAfterFailure)
        } else if (state.sendFailed && !state.offline) {
            PfNotice(
                "Не получилось отправить вопрос",
                tone = NoticeTone.WARNING,
                modifier = Modifier.padding(horizontal = d.space5, vertical = d.space2).testTag(AssistantTags.SEND_FAILED),
                action = if (state.askBlocked || state.failedRetryAnswerId != null) null else ({ PfNoticeLink("Повторить", onClick = actions.onRetryAfterFailure) }),
            )
        }
        // «Осталось 3 из 5 вопросов на сегодня» above the composer; with the limit spent the composer says it itself.
        if (limit != null && !state.limitExhausted) {
            PfLimitMeter(
                used = (limit.dailyMax - limit.remaining).coerceAtLeast(0), total = limit.dailyMax,
                modifier = Modifier.padding(horizontal = d.space5, vertical = d.space2).testTag(PfTestTags.LIMIT_METER),
            )
        }
        PfChatComposer(
            value = state.draft,
            onValueChange = actions.onDraft,
            onSend = actions.onSend,
            onVoice = actions.onVoice,
            sendEnabled = state.askRetryReady && !state.askBlocked,
            // Offline replaces the input with a notice (mockup `ChatOffline`); the app has no network monitor, so
            // the notice has «Повторить» (FIN-40, decided with the owner).
            state = when {
                state.limitExhausted -> ComposerState.LIMIT
                state.offline -> ComposerState.OFFLINE
                state.sending || state.generating || voiceListening -> ComposerState.BUSY
                else -> ComposerState.IDLE
            },
            onRetry = actions.onRetryAfterFailure,
            limitText = limit?.let { "Вопросы на сегодня закончились. Новые — в ${resetTime(it.resetsAt, zone)}" } ?: "Вопросы на сегодня закончились. Новые — в 00:00 по Москве",
        )
    }
}

/** «00:00 по Москве», plus local time in brackets when the phone is in another zone (api.md 7.5). */
private fun resetTime(at: java.time.OffsetDateTime, zone: ZoneId): String {
    val moscow = at.atZoneSameInstant(ZoneId.of("Europe/Moscow"))
    val local = at.atZoneSameInstant(zone)
    val base = "%02d:%02d по Москве".format(moscow.hour, moscow.minute)
    return if (local.offset == moscow.offset) base else base + " (%02d:%02d по вашему времени)".format(local.hour, local.minute)
}

private fun dayLabel(day: LocalDate, today: LocalDate) = when (day) {
    today -> "Сегодня"
    today.minusDays(1) -> "Вчера"
    else -> RussianDates.day(day, today.year)
}

@Composable
private fun Answer(
    item: ChatItem.Answer,
    zone: ZoneId,
    requestRetryBlocked: Boolean,
    onRetry: () -> Unit,
    onOpenSearch: (OperationsFilter) -> Unit,
    onOpenAnalytics: (AnalyticsParams) -> Unit,
) {
    when {
        // The text and «Повторить» come from the server, whatever the code (api.md 7.2, 7.3, FIN-159).
        item.status == AnswerStatus.FAILED -> Column(Modifier.testTag(AssistantTags.ANSWER_FAILED)) {
            ErrorMessage(item.errorPresentation, GENERIC_ANSWER_ERROR, legacySafeText = item.errorText)
            if (item.retryable && !requestRetryBlocked) {
                PfButton("Повторить", onClick = onRetry, variant = ButtonVariant.GHOST,
                    modifier = Modifier.testTag(AssistantTags.ANSWER_FAILED_RETRY))
            }
        }
        item.blocks.isEmpty() && item.status == AnswerStatus.GENERATING -> PfChatStatus("Считаю по выписке…", Modifier.testTag(AssistantTags.ANSWER_GENERATING))
        else -> Column(verticalArrangement = Arrangement.spacedBy(PfTheme.dimens.space4)) {
            PfAssistantMessage(
                // A finished answer gets its own tag, so tests can wait for the end of the stream.
                modifier = Modifier.testTag(if (item.status == AnswerStatus.COMPLETE) AssistantTags.ANSWER else AssistantTags.ANSWER_GENERATING),
                source = item.source?.let { AnswerFormat.source(it, zone) },
                staleText = item.source?.let { AnswerFormat.changedSince(it.changedSince, it.changedSinceText) },
            ) {
                AnswerFormat.groupBlocks(item.blocks).forEachIndexed { i, group -> BlockGroupView(group, chartId = "assistant.${item.id}.$i", currency = item.currency) }
            }
            // The chips follow the message and its source line, across the full width (mockup `Chat`).
            val chips = item.chips.filter { it.screen != ChipScreen.UNKNOWN }
            if (chips.isNotEmpty() && item.status == AnswerStatus.COMPLETE) {
                PfChipRow {
                    chips.forEach { chip ->
                        PfChip(chip.label, transition = true, modifier = Modifier.testTag(AssistantTags.chip(chip.screen.name.lowercase())), onClick = {
                            when (chip.screen) {
                                ChipScreen.OPERATIONS -> chip.filters?.let(onOpenSearch)
                                ChipScreen.ANALYTICS -> chip.params?.let(onOpenAnalytics)
                                ChipScreen.UNKNOWN -> Unit
                            }
                        })
                    }
                }
            }
        }
    }
}

/** A chart followed by its rows is one card (mockup `Chat`); other blocks stand alone. */
@Composable
private fun BlockGroupView(group: List<Block>, chartId: String, currency: String) {
    val chartBlock = group.firstOrNull { it.type == BlockType.CHART }
    val chart = chartBlock?.let(AnswerFormat::drawableChart)
    val rowsBlock = group.firstOrNull { it.type == BlockType.ROWS }
    val rows = rowsBlock?.decode(RowsData.serializer())
    if (chartBlock != null && chart != null && rowsBlock != null && rows != null) {
        val rowValues = rows.rows.mapNotNull { r -> AnswerFormat.rowValue(r, currency)?.let { r to it } }
        PfCard {
            ChartBody(chartBlock, chart, chartId, currency)
            if (rowValues.isNotEmpty()) {
                Spacer(Modifier.height(PfTheme.dimens.space3))
                PfDivider()
                rowValues.forEachIndexed { i, (r, value) -> PfDataRow(r.label, value, sublabel = r.sublabel, divider = i < rowValues.lastIndex) }
            }
        }
    } else {
        group.forEachIndexed { i, block -> BlockView(block, chartId = if (i == 0) chartId else "$chartId.$i", currency = currency) }
    }
}

@Composable
private fun BlockView(block: Block, chartId: String, currency: String) {
    val c = PfTheme.colors
    when (block.type) {
        // `.pf-msg-body`: 15/20 (`lead`), `<strong>` is 700.
        BlockType.TEXT -> Text(markdown(block.text.orEmpty()), style = PfTheme.type.lead, color = c.text)
        BlockType.CHART -> {
            val chart = AnswerFormat.drawableChart(block)
            if (chart == null) {
                Text(block.altText.orEmpty(), style = PfTheme.type.lead, color = c.text)
            } else {
                PfCard { ChartBody(block, chart, chartId, currency) }
            }
        }
        BlockType.ROWS -> {
            val rows = block.decode(RowsData.serializer())?.rows.orEmpty().mapNotNull { r -> AnswerFormat.rowValue(r, currency)?.let { r to it } }
            if (rows.isNotEmpty()) {
                PfCard {
                    rows.forEachIndexed { i, (r, value) -> PfDataRow(r.label, value, sublabel = r.sublabel, divider = i < rows.lastIndex) }
                }
            } else {
                block.altText?.let { Text(it, style = PfTheme.type.lead, color = c.text) }
            }
        }
        // A type this client does not draw in an answer: its text version (api.md «Блоки»).
        else -> block.altText?.let { Text(it, style = PfTheme.type.lead, color = c.text) }
    }
}

/** The title and the bars of a chart block, 64 dp high (mockup `Chat`) — the bars are the shared renderer's (FIN-7). */
@Composable
private fun ChartBody(block: Block, chart: ChartData, chartId: String, currency: String) {
    block.title?.let { Text(it, style = PfTheme.type.bodyStrong, color = PfTheme.colors.text) }
    // Without a highlight from the server the last point is the one the answer is about.
    BlockChartBars(chart.copy(highlightIndex = chart.highlightIndex ?: chart.points.lastIndex), chartId, height = 64.dp, currency = currency)
}

private fun markdown(text: String): AnnotatedString = buildAnnotatedString {
    AnswerFormat.parse(text).forEachIndexed { i, part ->
        if (i > 0) append("\n")
        val spans = when (part) {
            is TextPart.Paragraph -> part.spans
            is TextPart.Bullet -> {
                append("•  ")
                part.spans
            }
        }
        spans.forEach { s ->
            if (s.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.text) } else append(s.text)
        }
        if (part is TextPart.Paragraph) append("\n")
    }
}.let { if (it.text.endsWith("\n")) it.subSequence(0, it.length - 1) else it }

/** Shown for a failed answer without a server text: a server older than FIN-159, an unknown status (api.md 7.2, 7.3). */
internal const val GENERIC_ANSWER_ERROR = "Не получилось ответить"

/** Test tags of the assistant screens (UI tests in `.maestro/`, convention in docs/e2e.md). */
internal object AssistantTags {
    const val CHAT = "assistant.chat"
    const val QUESTION = "assistant.question"
    /** A complete answer; while it streams the message carries [ANSWER_GENERATING]. */
    const val ANSWER = "assistant.answer"
    const val ANSWER_GENERATING = "assistant.answer.generating"
    /** A failed answer: the server's text (or the generic one), whatever the code. */
    const val ANSWER_FAILED = "assistant.answer.failed"
    /** «Повторить» under a failed answer the server marked `retryable`. */
    const val ANSWER_FAILED_RETRY = "assistant.answer.failed.retry"
    /** «Не получилось отправить вопрос» above the composer. */
    const val SEND_FAILED = "assistant.chat.send_failed"
    /** `assistant.answer.chip.operations`, `assistant.answer.chip.analytics`. */
    fun chip(screen: String) = "assistant.answer.chip.$screen"

    const val CONSENT = "assistant.consent"
    const val CONSENT_CHECKBOX = "assistant.consent.checkbox"
    const val EMPTY = "assistant.chat.empty"
    const val CONSENT_SUBMIT = "assistant.consent.submit"
    const val CONSENT_DOCUMENT = "assistant.consent.document"
    const val CONSENT_LATER = "assistant.consent.later"
}
