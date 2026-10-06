package ru.finassist.pf.feature.assistant.impl.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    val d = PfTheme.dimens
    val zone = remember { ZoneId.systemDefault() }
    val listState = rememberLazyListState()

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
    // Keep the newest message in view as the thread grows or an answer streams in.
    LaunchedEffect(state.items.size, (state.items.lastOrNull() as? ChatItem.Answer)?.blocks?.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount.coerceAtLeast(1) - 1)
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.send(voiceText = it) }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        val limit = state.limit
        PfPageHeader(
            "Помощник",
            onBack = onBack,
            subtitle = when {
                limit == null -> "Отвечает по вашим операциям"
                limit.remaining <= 0 -> "Вопросы на сегодня закончились"
                else -> "Отвечает по вашим операциям · осталось ${limit.remaining} из ${limit.dailyMax}"
            },
        )
        when {
            state.loading -> Column(Modifier.weight(1f)) {}
            state.loadFailed -> Column(Modifier.weight(1f)) {
                PfEmptyState(PfIcons.ALERT, "Не получилось загрузить диалог", "Проверьте интернет и попробуйте ещё раз") {
                    PfLink("Повторить", onClick = vm::load)
                }
            }
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = d.space5, vertical = d.space3),
                verticalArrangement = Arrangement.spacedBy(d.space4),
            ) {
                if (state.hasOlder) {
                    item(key = "older") { PfLink(if (state.loadingOlder) "Загружаем…" else "Показать раньше", onClick = vm::loadOlder) }
                }
                if (state.items.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "Спросите о своих расходах и доходах — помощник посчитает по загруженным операциям. Например: «Сколько я трачу на такси?»",
                            style = PfTheme.type.body, color = PfTheme.colors.textMuted,
                        )
                    }
                }
                var lastDay: LocalDate? = null
                state.items.forEach { item ->
                    val day = item.createdAt.atZoneSameInstant(zone).toLocalDate()
                    if (day != lastDay) {
                        lastDay = day
                        item(key = "day-$day-${item.id}") { PfChatDay(dayLabel(day, LocalDate.now(zone))) }
                    }
                    item(key = item.id) {
                        when (item) {
                            is ChatItem.Question -> PfUserMessage(item.text)
                            is ChatItem.Answer -> Answer(item, zone, onRetry = { vm.retry(item.id) }, onOpenSearch = onOpenSearch, onOpenAnalytics = onOpenAnalytics)
                        }
                    }
                }
            }
        }
        PfChatComposer(
            value = state.draft,
            onValueChange = vm::setDraft,
            onSend = { vm.send() },
            onVoice = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    // Recognition on the device: no audio leaves the phone (mvp-scope «Помощник»).
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                runCatching { voice.launch(intent) }
            },
            state = when {
                state.limitExhausted -> ComposerState.LIMIT
                state.offline -> ComposerState.OFFLINE
                state.sending || state.generating -> ComposerState.BUSY
                else -> ComposerState.IDLE
            },
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
                PfChatStatus("Не хватает данных или вопрос непонятен — переформулируйте его. Вопрос не потрачен", tone = ChatStatusTone.ERROR)
            } else {
                PfChatStatus("Не получилось ответить. Вопрос не потрачен", tone = ChatStatusTone.ERROR, action = { PfLink("Повторить", onClick = onRetry) })
            }
        }
        item.blocks.isEmpty() && item.status == AnswerStatus.GENERATING -> PfChatStatus("Считаю по выписке…")
        else -> PfAssistantMessage(
            source = item.source?.let { AnswerFormat.source(it, zone) },
            staleText = item.source?.let { AnswerFormat.changedSince(it.changedSince) },
        ) {
            item.blocks.forEachIndexed { i, block -> BlockView(block, chartId = "assistant.${item.id}.$i") }
            val chips = item.chips.filter { it.screen != ChipScreen.UNKNOWN }
            if (chips.isNotEmpty() && item.status == AnswerStatus.COMPLETE) {
                PfChipRow {
                    chips.forEach { chip ->
                        PfChip(chip.label, transition = true, onClick = {
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

@Composable
private fun BlockView(block: Block, chartId: String) {
    val c = PfTheme.colors
    when (block.type) {
        BlockType.TEXT -> Text(markdown(block.text.orEmpty()), style = PfTheme.type.body, color = c.text)
        BlockType.CHART -> {
            val points = block.points
            if (block.kind != ChartKind.BAR || block.valueKind == null || block.valueKind == ChartValueKind.UNKNOWN || points.isNullOrEmpty()) {
                Text(block.altText.orEmpty(), style = PfTheme.type.body, color = c.text)
            } else {
                val max = points.mapNotNull { it.value?.let(::abs) }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
                PfCard {
                    block.title?.let { Text(it, style = PfTheme.type.bodyStrong, color = c.text) }
                    PfBarChart(
                        chartId = chartId,
                        points = points.map { p ->
                            val ym = YearMonth.from(p.range.from.atZoneSameInstant(ZoneId.systemDefault()))
                            val display = p.value?.let { v ->
                                if (block.valueKind == ChartValueKind.PERCENT) "${(v * 100).roundToInt()}%" else Money(v.toLong()).format()
                            } ?: "нет данных"
                            BarPoint(
                                key = p.range.from.toString(),
                                label = RussianDates.monthShort(ym.month),
                                spokenLabel = RussianDates.monthTitle(ym),
                                value = p.value?.let { (it / max).toFloat().coerceAtLeast(0f) },
                                display = display,
                                partial = p.coverage.effective == Coverage.PARTIAL,
                                note = p.dataTo?.takeIf { p.coverage.effective == Coverage.PARTIAL }?.let { "по ${it.dayOfMonth}-е" },
                            )
                        },
                        highlight = block.highlightIndex ?: points.lastIndex,
                    )
                }
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
        BlockType.UNKNOWN -> block.altText?.let { Text(it, style = PfTheme.type.body, color = c.text) }
    }
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
            if (s.bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.text) } else append(s.text)
        }
        if (part is TextPart.Paragraph) append("\n")
    }
}.let { if (it.text.endsWith("\n")) it.subSequence(0, it.length - 1) else it }
