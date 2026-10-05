package ru.finassist.pf.feature.assistant.impl.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.money.MoneyFormat
import ru.finassist.pf.core.common.time.RussianDates.plural
import ru.finassist.pf.core.designsystem.charts.Bar
import ru.finassist.pf.core.designsystem.charts.BarChart
import ru.finassist.pf.core.designsystem.components.ChatAssistantMessage
import ru.finassist.pf.core.designsystem.components.ChatComposer
import ru.finassist.pf.core.designsystem.components.ChatDay
import ru.finassist.pf.core.designsystem.components.ChatStatus
import ru.finassist.pf.core.designsystem.components.ChatStatusTone
import ru.finassist.pf.core.designsystem.components.ChatUserMessage
import ru.finassist.pf.core.designsystem.components.ChipRow
import ru.finassist.pf.core.designsystem.components.ComposerState
import ru.finassist.pf.core.designsystem.components.DataRow
import ru.finassist.pf.core.designsystem.components.LimitMeter
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PageHeader
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfChip
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.navigation.AnalyticsParams
import ru.finassist.pf.core.navigation.OperationsFilter
import ru.finassist.pf.core.network.codes.AnswerErrorCode
import ru.finassist.pf.core.network.codes.AnswerStatus
import ru.finassist.pf.core.network.codes.BlockType
import ru.finassist.pf.core.network.codes.ChipScreen
import ru.finassist.pf.core.network.codes.Coverage
import ru.finassist.pf.core.network.dto.BlockDto
import ru.finassist.pf.core.network.dto.ChipDto
import ru.finassist.pf.feature.assistant.impl.domain.ChatMessage
import ru.finassist.pf.feature.assistant.impl.domain.axisLabel
import ru.finassist.pf.feature.assistant.impl.domain.axisSpoken
import ru.finassist.pf.feature.assistant.impl.domain.changedSinceText
import ru.finassist.pf.feature.assistant.impl.domain.dayLabel
import ru.finassist.pf.feature.assistant.impl.domain.dayOfMonth
import ru.finassist.pf.feature.assistant.impl.domain.monthLength
import ru.finassist.pf.feature.assistant.impl.domain.parseMarkdown
import ru.finassist.pf.feature.assistant.impl.domain.resetText
import ru.finassist.pf.feature.assistant.impl.domain.sourceText
import ru.finassist.pf.feature.assistant.impl.domain.spans
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** «Помощник» (mockups Chat*): thread of questions and streamed answers, limit meter, composer with voice input. */
@Composable
fun ChatScreen(
    state: ChatViewModel.UiState,
    vm: ChatViewModel,
    onBack: () -> Unit,
    onOperations: (OperationsFilter) -> Unit,
    onAnalytics: (AnalyticsParams) -> Unit,
) {
    val c = PfTheme.colors
    val listState = rememberLazyListState()
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.onInput(it); vm.send(it) }
        }
    }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)

    // Keep the newest message in view while the answer streams in.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.let { (it as? ChatMessage.Assistant)?.blocks?.size }) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size + 1)
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageHeader(title = "Помощник", onBack = onBack)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = PfSpace.s2),
            verticalArrangement = Arrangement.spacedBy(PfSpace.s4),
        ) {
            item(key = "intro") {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                    Text(
                        "Помощник отвечает по вашим операциям. " + (state.remaining?.let { r -> if (r == state.dailyMax) "${plural(state.dailyMax.toLong(), "вопрос", "вопроса", "вопросов")} в день" else "Осталось $r из ${state.dailyMax}" } ?: "${state.dailyMax} вопросов в день"),
                        style = PfTheme.type.caption, color = c.textMuted,
                    )
                    state.remaining?.let { LimitMeter(used = state.dailyMax - it, total = state.dailyMax) }
                    if (state.hasOlder) PfLink(if (state.loadingOlder) "Загружаем…" else "Показать раньше", onClick = vm::loadOlder)
                    if (state.historyError != null && state.messages.isEmpty()) Notice("Не получилось загрузить историю", tone = NoticeTone.Warning, action = { PfLink("Повторить", onClick = vm::loadHistory, inline = true) })
                }
            }
            val withDays = state.messages.withIndex().toList()
            items(withDays, key = { it.value.id }) { (i, m) ->
                val day = m.createdAt.atZoneSameInstant(zone).toLocalDate()
                val prevDay = withDays.getOrNull(i - 1)?.value?.createdAt?.atZoneSameInstant(zone)?.toLocalDate()
                Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s3)) {
                    if (day != prevDay) ChatDay(dayLabel(day, today))
                    when (m) {
                        is ChatMessage.User -> ChatUserMessage(m.text)
                        is ChatMessage.Assistant -> AssistantMessage(m, vm, onOperations, onAnalytics)
                    }
                }
            }
            item(key = "tail") {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                    state.sendError?.let { Notice(it, tone = NoticeTone.Warning, alert = true) }
                    Spacer(Modifier.height(PfSpace.s2))
                }
            }
        }
        ChatComposer(
            value = state.input,
            onValueChange = vm::onInput,
            onSend = { vm.send() },
            onVoice = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Спросите о своих финансах")
                runCatching { voice.launch(intent) }
            },
            state = when {
                !state.enabled -> ComposerState.Limit
                state.offline -> ComposerState.Offline
                state.remaining == 0 -> ComposerState.Limit
                state.sending || state.generatingId != null -> ComposerState.Busy
                else -> ComposerState.Idle
            },
            limitText = if (!state.enabled) "Помощник временно недоступен" else "Вопросы на сегодня закончились. ${state.resetsAt?.let { resetText(it) } ?: "Новые — в 00:00 по Москве"}",
        )
    }
}

@Composable
private fun AssistantMessage(m: ChatMessage.Assistant, vm: ChatViewModel, onOperations: (OperationsFilter) -> Unit, onAnalytics: (AnalyticsParams) -> Unit) {
    val c = PfTheme.colors
    when (m.status) {
        AnswerStatus.Failed -> ChatStatus(
            title = if (m.errorCode == AnswerErrorCode.CannotAnswer) "Не получилось ответить: недостаточно данных или вопрос непонятен — переформулируйте. Вопрос не потрачен" else "Не получилось ответить. Вопрос не потрачен",
            tone = ChatStatusTone.Error,
            action = if (m.errorCode != AnswerErrorCode.CannotAnswer) ({ PfLink("Повторить", onClick = { vm.retry(m.id) }, inline = true) }) else null,
        )
        AnswerStatus.Generating -> if (m.blocks.isEmpty()) ChatStatus("Считаю по выписке…", ChatStatusTone.Busy) else AnswerBody(m, onOperations, onAnalytics)
        AnswerStatus.Complete -> AnswerBody(m, onOperations, onAnalytics)
    }
    if (m.status == AnswerStatus.Complete && m.charged == false) {
        Text("Вопрос не потрачен — данных для полного ответа не хватило", style = PfTheme.type.hint, color = c.textMuted)
    }
}

@Composable
private fun AnswerBody(m: ChatMessage.Assistant, onOperations: (OperationsFilter) -> Unit, onAnalytics: (AnalyticsParams) -> Unit) {
    ChatAssistantMessage(
        source = m.source?.let { sourceText(it) },
        changedSince = m.source?.let { changedSinceText(it.changedSince) },
    ) {
        m.blocks.forEachIndexed { i, b ->
            if (i > 0) Spacer(Modifier.height(PfSpace.s3))
            Block(b, "${m.id}.$i")
        }
        if (m.chips.isNotEmpty()) {
            Spacer(Modifier.height(PfSpace.s3))
            ChipRow {
                m.chips.forEach { chip -> Chip(chip, onOperations, onAnalytics) }
            }
        }
    }
}

@Composable
private fun Chip(chip: ChipDto, onOperations: (OperationsFilter) -> Unit, onAnalytics: (AnalyticsParams) -> Unit) {
    when (ChipScreen.fromWire(chip.screen)) {
        ChipScreen.Operations -> chip.filters?.let { f ->
            PfChip(chip.label, link = true, onClick = {
                onOperations(OperationsFilter(f.from, f.to, f.q, f.categoryId, f.kind?.name, f.amountFrom, f.amountTo, f.transferMode, f.selection, f.selectionName))
            })
        }
        ChipScreen.Analytics -> chip.params?.let { p -> PfChip(chip.label, link = true, onClick = { onAnalytics(AnalyticsParams(p.period, p.date, p.transferMode)) }) }
        ChipScreen.Unknown -> Unit
    }
}

/** Answer block: text (narrow Markdown), bar chart, or label–value rows; unknown types fall back to `alt_text`. */
@Composable
private fun Block(b: BlockDto, introId: String) {
    val c = PfTheme.colors
    when (BlockType.fromWire(b.type)) {
        BlockType.Text -> MarkdownText(b.text.orEmpty())
        BlockType.Chart -> {
            val points = b.points
            val percent = b.valueKind == "percent"
            if (b.kind != "bar" || points == null || (b.valueKind != "amount" && !percent)) {
                Text(b.altText ?: b.title.orEmpty(), style = PfTheme.type.body, color = c.text)
            } else PfCard {
                b.title?.let { Text(it, style = PfTheme.type.captionStrong, color = c.textMuted); Spacer(Modifier.height(PfSpace.s2)) }
                val bars = points.map { p ->
                    val partial = Coverage.fromWire(p.coverage) == Coverage.Partial && p.value != null
                    val to = dayOfMonth(p.dataTo)
                    val value = p.value?.let { if (percent) it * 100 else kotlin.math.abs(it) }
                    Bar(
                        label = axisLabel(p.range.from), spokenName = axisSpoken(p.range.from) + (if (partial && to != null) ", по $to-е" else ""),
                        value = value,
                        display = value?.let { v -> if (percent) "${v.roundToInt()}%" else MoneyFormat.rub(Money(v.toLong())) } ?: "нет данных",
                        partialFill = if (partial && to != null) to.toFloat() / monthLength(p.range.from) else null,
                        partialNote = if (partial && to != null) "по $to-е" else null,
                    )
                }
                BarChart(bars = bars, introId = "chat.$introId", highlight = b.highlightIndex ?: bars.lastIndex, height = 100.dp, formatValue = { v -> if (percent) "${v.roundToInt()}%" else MoneyFormat.rub(Money(v.toLong())) })
            }
        }
        BlockType.Rows -> PfCard {
            b.rows.orEmpty().forEachIndexed { i, r ->
                val value = when {
                    r.amount != null -> MoneyFormat.rub(Money(r.amount))
                    r.count != null -> r.count.toString()
                    r.percent != null -> "${(r.percent * 100).roundToInt()}%"
                    r.text != null -> r.text
                    else -> return@forEachIndexed
                }
                DataRow(label = r.label, sublabel = r.sublabel, value = value, divider = i < b.rows!!.lastIndex)
            }
        }
        BlockType.Unknown -> Text(b.altText ?: "", style = PfTheme.type.body, color = c.text)
    }
}

@Composable
private fun MarkdownText(text: String) {
    val c = PfTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
        parseMarkdown(text).forEach { para ->
            para.forEach { line ->
                Row {
                    if (line.bullet) { Text("•", style = PfTheme.type.body, color = c.text); Spacer(Modifier.width(PfSpace.s2)) }
                    Text(
                        buildAnnotatedString {
                            spans(line.text).forEach { s -> if (s.bold) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(s.text) } else append(s.text) }
                        },
                        style = PfTheme.type.body, color = c.text,
                    )
                }
            }
        }
    }
}
