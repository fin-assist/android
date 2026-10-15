package ru.finassist.pf.feature.statements.impl.ui

import ru.finassist.pf.core.designsystem.theme.PfInsets
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.components.TextPart
import ru.finassist.pf.core.designsystem.components.PfLinkedText
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfDivider
import ru.finassist.pf.core.designsystem.components.PfAmountText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import ru.finassist.pf.core.api.model.ImportNoticeCode
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.ImportTotalsScope
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.HeroTone
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfNoticeLink
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.components.PfStatusHero
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.feature.statements.api.StatementsRepository
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import ru.finassist.pf.feature.statements.impl.domain.ResultTexts
import javax.inject.Inject

@HiltViewModel
class ResultViewModel @Inject constructor(
    savedState: SavedStateHandle,
    repository: StatementsRepository,
    flags: FeatureFlags,
) : ViewModel() {
    val uploadEnabled: Boolean = flags.isEnabled(Flag.STATEMENTS_UPLOAD)

    val uploadId: String = savedState.toRoute<StatementsRoutes.Result>().uploadId

    /** Null after process death: the screen then shows a short «loaded» state without numbers. */
    val result: ImportResult? = repository.lastResult(uploadId)
}

/** «Итог загрузки» (api.md 3.3 `complete`). */
@Composable
fun ResultScreen(
    onDone: () -> Unit,
    onOpenSearch: (OperationsFilter) -> Unit,
    onOpenUnread: (uploadId: String, othersLoaded: Boolean) -> Unit,
    onOpenAnalytics: () -> Unit,
    onUploadAnother: () -> Unit,
    onOpenHistory: () -> Unit,
    vm: ResultViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDone)
    ResultContent(
        result = vm.result,
        uploadEnabled = vm.uploadEnabled,
        onDone = onDone,
        onOpenSearch = onOpenSearch,
        onOpenUnread = { onOpenUnread(vm.uploadId, (vm.result?.operationCount ?: 0) > 0) },
        onOpenAnalytics = onOpenAnalytics,
        onUploadAnother = onUploadAnother,
        onOpenHistory = onOpenHistory,
    )
}

/**
 * Stateless body of [ResultScreen] (mockups `ImportResult*`); [result] is null after process death. An empty
 * statement is an empty state in the middle; otherwise the hero, the totals, «Что мы нашли», a coverage notice
 * and the way on at the bottom.
 */
@Composable
internal fun ResultContent(
    result: ImportResult?,
    uploadEnabled: Boolean,
    onDone: () -> Unit,
    onOpenSearch: (OperationsFilter) -> Unit,
    onOpenUnread: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onUploadAnother: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val d = PfTheme.dimens
    val c = PfTheme.colors
    val r = result
    BoxWithConstraints(
        Modifier
            .testTag(StatementsTags.RESULT)
            .fillMaxSize()
            .windowInsetsPadding(PfInsets.statusBars)
            .windowInsetsPadding(PfInsets.navigationBars),
    ) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(start = d.space5, end = d.space5, top = d.space6, bottom = d.space4),
        ) {
            // Special cases this client does not know (FIN-83): above everything, so an empty statement or a file with
            // nothing new does not lose them.
            r?.notices?.filter { it.code == ImportNoticeCode.UNKNOWN }?.mapNotNull { it.text?.takeIf(String::isNotBlank) }?.forEach { text ->
                PfNotice(text, tone = NoticeTone.WARNING, modifier = Modifier.padding(bottom = d.space4).testTag(StatementsTags.RESULT_NOTICE))
            }
            if (r != null && (r.operationCount == 0 || r.has(ImportNoticeCode.EMPTY_STATEMENT))) {
                Column(Modifier.weight(1f).fillMaxWidth().testTag(StatementsTags.RESULT_EMPTY), verticalArrangement = Arrangement.Center) {
                    PfEmptyState(
                        PfIcons.FILE_TEXT, "В выписке нет операций",
                        "За выбранный период операций не нашлось — выгрузите выписку за другой период",
                    )
                    // A file the parser could not read at all still has its unread lines: the reasons stay reachable.
                    if (r.unreadCount > 0) {
                        UnreadNotice(r, onOpenUnread)
                        Spacer(Modifier.height(d.space4))
                    }
                    if (uploadEnabled) {
                        PfButton("Выбрать другой файл", onClick = onUploadAnother, variant = ButtonVariant.PRIMARY, block = true, modifier = Modifier.testTag(StatementsTags.RESULT_UPLOAD_ANOTHER))
                        Box(Modifier.fillMaxWidth().padding(top = d.space2), contentAlignment = Alignment.Center) {
                            PfLink("Как скачать выписку", onClick = onUploadAnother)
                        }
                    } else {
                        PfButton("Готово", onClick = onDone, variant = ButtonVariant.PRIMARY, block = true, modifier = Modifier.testTag(StatementsTags.RESULT_DONE))
                    }
                }
                return@Column
            }
            Summary(r, onOpenSearch, onOpenUnread, onUploadAnother = if (uploadEnabled) onUploadAnother else null)
            Spacer(Modifier.weight(1f).heightIn(min = d.space6))
            PfButton("Посмотреть аналитику", onClick = onOpenAnalytics, variant = ButtonVariant.PRIMARY, block = true, modifier = Modifier.testTag(StatementsTags.RESULT_ANALYTICS))
            Row(
                Modifier.fillMaxWidth().padding(top = d.space2),
                horizontalArrangement = Arrangement.spacedBy(d.space6, Alignment.CenterHorizontally),
            ) {
                PfLink("Перейти к операциям", onClick = onDone, modifier = Modifier.testTag(StatementsTags.RESULT_DONE))
                PfLink("История загрузок", onClick = onOpenHistory, modifier = Modifier.testTag(StatementsTags.RESULT_HISTORY))
            }
        }
    }
}

/** «3 строки не прочитали — их нет в суммах. Подробнее»: shown with a summary and in the empty state alike. */
@Composable
private fun UnreadNotice(r: ImportResult, onOpenUnread: () -> Unit) {
    val text = ResultTexts.unread(r) ?: return
    Spacer(Modifier.height(PfTheme.dimens.space4))
    PfNotice(
        text, tone = NoticeTone.WARNING,
        parts = listOf(TextPart.Plain("$text "), TextPart.Link("Подробнее", StatementsTags.RESULT_UNREAD, onOpenUnread)),
        modifier = Modifier.testTag(StatementsTags.RESULT_UNREAD),
    )
}

@Composable
private fun ColumnScope.Summary(
    r: ImportResult?,
    onOpenSearch: (OperationsFilter) -> Unit,
    onOpenUnread: () -> Unit,
    onUploadAnother: (() -> Unit)?,
) {
    val d = PfTheme.dimens
    val c = PfTheme.colors
    if (r == null) {
        PfStatusHero(tone = HeroTone.POSITIVE, title = "Выписка загружена", subtitle = "Операции и аналитика уже обновились", centered = false)
        return
    }
    val nothingNew = r.newCount == 0
    PfStatusHero(
        tone = if (r.unreadCount > 0 && !nothingNew) HeroTone.WARNING else HeroTone.POSITIVE,
        icon = if (r.unreadCount > 0 && !nothingNew) PfIcons.ALERT else PfIcons.CHECK_CIRCLE,
        title = ResultTexts.title(r),
        subtitle = ResultTexts.subtitle(r),
        centered = false,
        modifier = Modifier.testTag(if (nothingNew) StatementsTags.RESULT_NO_NEW else StatementsTags.RESULT_NEW),
    )
    if (nothingNew) {
        Text(ResultTexts.nothingNew(r), style = PfTheme.type.lead, color = c.textMuted, modifier = Modifier.padding(top = d.space2))
        return
    }
    UnreadNotice(r, onOpenUnread)
    r.totals?.let { t ->
        val scope = when (t.scope) {
            ImportTotalsScope.ALL -> "Все операции"
            ImportTotalsScope.NEW -> "Новые операции"
            ImportTotalsScope.UNKNOWN -> null
        }
        listOfNotNull(scope, ResultTexts.period(r)).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
            Text(it, style = PfTheme.type.hint, color = c.textMuted, modifier = Modifier.padding(top = d.space5))
        }
        Row(Modifier.padding(top = d.space2).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(d.space4)) {
            TotalTile("Расходы", (Money.ZERO - t.expense).format(r.currency), positive = false, Modifier.weight(1f).fillMaxHeight())
            TotalTile("Доходы", t.income.format(r.currency, Money.Sign.ALWAYS), positive = true, Modifier.weight(1f).fillMaxHeight())
        }
    }
    Text(
        "Что мы нашли", style = PfTheme.type.bodyStrong, color = c.text,
        modifier = Modifier.padding(top = d.space6, bottom = d.space2).semantics { heading() },
    )
    val rows = buildList {
        ResultTexts.duplicates(r)?.let { add(Finding(PfIcons.REPEAT, FindingTint.MUTED, it)) }
        ResultTexts.accounts(r.accounts)?.let { add(Finding(PfIcons.LANDMARK, FindingTint.ACCENT, it)) }
        ResultTexts.categories(r)?.let { add(Finding(PfIcons.CHECK_CIRCLE, FindingTint.POSITIVE, it)) }
        ResultTexts.ownTransfers(r)?.let { add(Finding(PfIcons.TRANSFER, FindingTint.ACCENT, it)) }
        ResultTexts.uncategorized(r)?.let { add(Finding(PfIcons.TAG, FindingTint.ACCENT, it, uncategorized = true)) }
    }
    PfCard(flush = true) {
        rows.forEachIndexed { i, f -> FindingRow(f, divider = i < rows.lastIndex, onSort = r.uncategorizedFilters?.let { filters -> { onOpenSearch(filters) } }) }
    }
    if (r.has(ImportNoticeCode.NO_EXPENSES)) {
        Spacer(Modifier.height(d.space4))
        PfNotice(
            "В выписке нет расходов — возможно, выгружен не тот счёт",
            tone = NoticeTone.WARNING,
            action = onUploadAnother?.let { upload -> { PfNoticeLink("Выбрать другой файл", onClick = upload, modifier = Modifier.testTag(StatementsTags.RESULT_UPLOAD_ANOTHER)) } },
        )
    }
    ResultTexts.coverageNotice(r)?.let { n ->
        Spacer(Modifier.height(d.space4))
        PfNotice(
            n.text,
            title = n.title,
            tone = if (n.positive) NoticeTone.POSITIVE else NoticeTone.INFO,
            action = onUploadAnother?.let { upload -> { PfNoticeLink("Загрузить прошлые месяцы", onClick = upload, modifier = Modifier.testTag(StatementsTags.RESULT_UPLOAD_MORE)) } },
        )
    }
}

/** Totals tile of the result (mockup markup, not `Pf.StatTile`): 12/16 padding, caption label, `amount-md` value. */
@Composable
private fun TotalTile(label: String, value: String, positive: Boolean, modifier: Modifier) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    val shape = RoundedCornerShape(d.radiusXl)
    Column(
        modifier
            .background(c.surface, shape)
            .border(1.dp, c.border, shape)
            // The 1 px frame sits outside `padding: 12px 16px`, as in `PfCard`.
            .padding(1.dp)
            .padding(horizontal = d.space4, vertical = d.space3),
    ) {
        Text(label, style = PfTheme.type.caption, color = c.textMuted)
        PfAmountText(value, style = PfTheme.type.amountMd, color = if (positive) c.positive else c.text, modifier = Modifier.padding(top = d.space1))
    }
}

private enum class FindingTint { ACCENT, POSITIVE, MUTED }

private data class Finding(val icon: String, val tint: FindingTint, val text: String, val uncategorized: Boolean = false)

/** A row of «Что мы нашли»: a 20 dp icon and the text; the uncategorized row links to them and explains the totals. */
@Composable
private fun FindingRow(f: Finding, divider: Boolean, onSort: (() -> Unit)?) {
    val c = PfTheme.colors
    val d = PfTheme.dimens
    val tint = when (f.tint) {
        FindingTint.ACCENT -> c.accent
        FindingTint.POSITIVE -> c.positive
        FindingTint.MUTED -> c.textMuted
    }
    Column {
        // `.pf-card-flush { padding: 0 16px }`: the row and its divider keep 16 dp from the card's frame.
        Row(Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space3), horizontalArrangement = Arrangement.spacedBy(d.space3), verticalAlignment = Alignment.Top) {
            PfIcon(f.icon, contentDescription = null, size = d.iconMd, tint = tint)
            Column(Modifier.weight(1f)) {
                if (f.uncategorized && onSort != null) {
                    PfLinkedText(
                        listOf(TextPart.Plain("${f.text} — "), TextPart.Link("разобрать", StatementsTags.RESULT_UNCATEGORIZED, onSort)),
                        style = PfTheme.type.body, color = c.text,
                    )
                } else {
                    Text(f.text, style = PfTheme.type.body, color = c.text)
                }
                if (f.uncategorized) {
                    Text("Операции без категории входят в суммы", style = PfTheme.type.caption, color = c.textMuted, modifier = Modifier.padding(top = d.space1))
                }
            }
        }
        if (divider) PfDivider(Modifier.padding(horizontal = d.space4))
    }
}
