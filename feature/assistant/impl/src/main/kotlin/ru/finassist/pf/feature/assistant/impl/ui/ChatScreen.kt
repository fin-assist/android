package ru.finassist.pf.feature.assistant.impl.ui

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
import androidx.compose.runtime.getValue
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
import ru.finassist.pf.core.api.model.AnswerErrorCode
import ru.finassist.pf.core.api.model.AnswerStatus
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.api.model.ChartKind
import ru.finassist.pf.core.api.model.ChartValueKind
import ru.finassist.pf.core.api.model.ChipScreen
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.designsystem.components.BarPoint
import ru.finassist.pf.core.designsystem.components.ChatStatusTone
import ru.finassist.pf.core.designsystem.components.ComposerState
import ru.finassist.pf.core.designsystem.components.PfAssistantMessage
import ru.finassist.pf.core.designsystem.components.PfBarChart
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
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

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
    val zone = remember { ZoneId.systemDefault() }

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
        today = LocalDate.now(zone),
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
        when {
            state.loading -> Column(Modifier.weight(1f)) {}
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
                if (state.hasOlder) {
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
                            is ChatItem.Answer -> Answer(item, zone, onRetry = { actions.onRetryAnswer(item.id) }, onOpenSearch = actions.onOpenSearch, onOpenAnalytics = actions.onOpenAnalytics)
                        }
                    }
                }
            }
        }
        if (voiceListening) {
            Text("Слушаю…", style = PfTheme.type.caption, color = PfTheme.colors.textMuted, modifier = Modifier.padding(horizontal = d.space5, vertical = d.space1))
        }
        if (state.sendFailed && !state.offline) {
            PfNotice(
                "Не получилось отправить вопрос",
                tone = NoticeTone.WARNING,
                modifier = Modifier.padding(horizontal = d.space5, vertical = d.space2),
                action = { PfNoticeLink("Повторить", onClick = actions.onRetryAfterFailure) },
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
    onRetry: () -> Unit,
    onOpenSearch: (OperationsFilter) -> Unit,
    onOpenAnalytics: (AnalyticsParams) -> Unit,
) {
    when {
        item.status == AnswerStatus.FAILED -> {
            if (item.errorCode == AnswerErrorCode.CANNOT_ANSWER) {
                PfChatStatus("Не получилось ответить — недостаточно данных, переформулируйте вопрос. Вопрос не потрачен", tone = ChatStatusTone.ERROR, modifier = Modifier.testTag(AssistantTags.ANSWER_CANNOT))
            } else {
                PfChatStatus(
                    "Не получилось ответить. Вопрос не потрачен", tone = ChatStatusTone.ERROR, action = { PfButton("Повторить", onClick = onRetry, variant = ButtonVariant.GHOST) },
                    modifier = Modifier.testTag(AssistantTags.ANSWER_FAILED),
                )
            }
        }
        item.blocks.isEmpty() && item.status == AnswerStatus.GENERATING -> PfChatStatus("Считаю по выписке…", Modifier.testTag(AssistantTags.ANSWER_GENERATING))
        else -> Column(verticalArrangement = Arrangement.spacedBy(PfTheme.dimens.space4)) {
            PfAssistantMessage(
                // A finished answer gets its own tag, so tests can wait for the end of the stream.
                modifier = Modifier.testTag(if (item.status == AnswerStatus.COMPLETE) AssistantTags.ANSWER else AssistantTags.ANSWER_GENERATING),
                source = item.source?.let { AnswerFormat.source(it, zone) },
                staleText = item.source?.let { AnswerFormat.changedSince(it.changedSince) },
            ) {
                AnswerFormat.groupBlocks(item.blocks).forEachIndexed { i, group -> BlockGroupView(group, chartId = "assistant.${item.id}.$i") }
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
private fun BlockGroupView(group: List<Block>, chartId: String) {
    val chart = group.firstOrNull { it.type == BlockType.CHART }
    val rows = group.firstOrNull { it.type == BlockType.ROWS }
    if (chart != null && rows != null && AnswerFormat.isDrawableChart(chart)) {
        val rowValues = rows.rows.orEmpty().mapNotNull { r -> AnswerFormat.rowValue(r)?.let { r to it } }
        PfCard {
            ChartBody(chart, chartId)
            if (rowValues.isNotEmpty()) {
                Spacer(Modifier.height(PfTheme.dimens.space3))
                PfDivider()
                rowValues.forEachIndexed { i, (r, value) -> PfDataRow(r.label, value, sublabel = r.sublabel, divider = i < rowValues.lastIndex) }
            }
        }
    } else {
        group.forEachIndexed { i, block -> BlockView(block, chartId = if (i == 0) chartId else "$chartId.$i") }
    }
}

@Composable
private fun BlockView(block: Block, chartId: String) {
    val c = PfTheme.colors
    when (block.type) {
        // `.pf-msg-body`: 15/20 (`lead`), `<strong>` is 700.
        BlockType.TEXT -> Text(markdown(block.text.orEmpty()), style = PfTheme.type.lead, color = c.text)
        BlockType.CHART -> {
            if (!AnswerFormat.isDrawableChart(block)) {
                Text(block.altText.orEmpty(), style = PfTheme.type.lead, color = c.text)
            } else {
                PfCard { ChartBody(block, chartId) }
            }
        }
        BlockType.ROWS -> {
            val rows = block.rows.orEmpty().mapNotNull { r -> AnswerFormat.rowValue(r)?.let { r to it } }
            if (rows.isNotEmpty()) {
                PfCard {
                    rows.forEachIndexed { i, (r, value) -> PfDataRow(r.label, value, sublabel = r.sublabel, divider = i < rows.lastIndex) }
                }
            }
        }
        BlockType.UNKNOWN -> block.altText?.let { Text(it, style = PfTheme.type.lead, color = c.text) }
    }
}

/** The bars of a chart block, 64 dp high (mockup `Chat`); a partial month is filled by its days with data. */
@Composable
private fun ChartBody(block: Block, chartId: String) {
    val c = PfTheme.colors
    val points = block.points.orEmpty()
    val max = points.mapNotNull { it.value?.let(::abs) }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    block.title?.let { Text(it, style = PfTheme.type.bodyStrong, color = c.text) }
    PfBarChart(
        chartId = chartId,
        points = points.map { p ->
            val ym = YearMonth.from(p.range.from.atZoneSameInstant(ZoneId.systemDefault()))
            val display = p.value?.let { v ->
                if (block.valueKind == ChartValueKind.PERCENT) "${(v * 100).roundToInt()}%" else Money(v.toLong()).format()
            } ?: "нет данных"
            val partial = p.coverage.effective == Coverage.PARTIAL
            BarPoint(
                key = p.range.from.toString(),
                label = RussianDates.monthShort(ym.month),
                spokenLabel = RussianDates.monthTitle(ym),
                // Signed: refunds can make a period negative; the chart draws a centred baseline then.
                value = p.value?.let { (it / max).toFloat() },
                display = display,
                partial = partial,
                partialFill = AnswerFormat.partialFill(p) ?: 0.5f,
                note = p.dataTo?.takeIf { partial }?.let { "по ${it.dayOfMonth}-е" },
            )
        },
        highlight = block.highlightIndex ?: points.lastIndex,
        height = 64.dp,
    )
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

/** Test tags of the assistant screens (UI tests in `.maestro/`, convention in docs/e2e.md). */
internal object AssistantTags {
    const val CHAT = "assistant.chat"
    const val QUESTION = "assistant.question"
    /** A complete answer; while it streams the message carries [ANSWER_GENERATING]. */
    const val ANSWER = "assistant.answer"
    const val ANSWER_GENERATING = "assistant.answer.generating"
    const val ANSWER_CANNOT = "assistant.answer.cannot"
    const val ANSWER_FAILED = "assistant.answer.failed"
    /** `assistant.answer.chip.operations`, `assistant.answer.chip.analytics`. */
    fun chip(screen: String) = "assistant.answer.chip.$screen"

    const val CONSENT = "assistant.consent"
    const val CONSENT_CHECKBOX = "assistant.consent.checkbox"
    const val EMPTY = "assistant.chat.empty"
    const val CONSENT_SUBMIT = "assistant.consent.submit"
    const val CONSENT_DOCUMENT = "assistant.consent.document"
    const val CONSENT_LATER = "assistant.consent.later"
}
