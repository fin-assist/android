package ru.finassist.pf.feature.statements.impl.ui

import ru.finassist.pf.core.designsystem.theme.PfInsets
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import ru.finassist.pf.core.api.model.ImportNotice
import ru.finassist.pf.core.api.model.ImportResult
import ru.finassist.pf.core.api.model.ImportTotalsScope
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.common.time.countWithNoun
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.HeroTone
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.components.PfStatTile
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
    onOpenUnread: (uploadId: String) -> Unit,
    onOpenAnalytics: () -> Unit,
    onUploadAnother: () -> Unit,
    vm: ResultViewModel = hiltViewModel(),
) {
    BackHandler(onBack = onDone)
    ResultContent(
        result = vm.result,
        uploadEnabled = vm.uploadEnabled,
        onDone = onDone,
        onOpenSearch = onOpenSearch,
        onOpenUnread = { onOpenUnread(vm.uploadId) },
        onOpenAnalytics = onOpenAnalytics,
        onUploadAnother = onUploadAnother,
    )
}

/** Stateless body of [ResultScreen]; [result] is null after process death. */
@Composable
internal fun ResultContent(
    result: ImportResult?,
    uploadEnabled: Boolean,
    onDone: () -> Unit,
    onOpenSearch: (OperationsFilter) -> Unit,
    onOpenUnread: () -> Unit,
    onOpenAnalytics: () -> Unit,
    onUploadAnother: () -> Unit,
) {
    val d = PfTheme.dimens
    val c = PfTheme.colors
    val r = result
    Column(
        Modifier
            .testTag(StatementsTags.RESULT)
            .fillMaxSize()
            .windowInsetsPadding(PfInsets.statusBars)
            .windowInsetsPadding(PfInsets.navigationBars),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = d.space5, vertical = d.space6),
            verticalArrangement = Arrangement.spacedBy(d.space4),
        ) {
            if (r == null) {
                PfStatusHero(tone = HeroTone.POSITIVE, title = "Выписка загружена", subtitle = "Операции и аналитика уже обновились", centered = false)
                return@Column
            }
            val empty = r.operationCount == 0 || ImportNotice.EMPTY_STATEMENT in r.notices
            PfStatusHero(
                tone = when {
                    empty -> HeroTone.WARNING
                    r.newCount == 0 || r.unreadCount > 0 -> HeroTone.INFO
                    else -> HeroTone.POSITIVE
                },
                title = ResultTexts.title(r),
                subtitle = ResultTexts.subtitle(r),
                centered = false,
                modifier = Modifier.testTag(
                    when {
                        empty -> StatementsTags.RESULT_EMPTY
                        r.newCount == 0 -> StatementsTags.RESULT_NO_NEW
                        else -> StatementsTags.RESULT_NEW
                    },
                ),
            )
            r.totals?.let { t ->
                val scope = when (t.scope) {
                    ImportTotalsScope.ALL -> "Все операции"
                    ImportTotalsScope.NEW -> "Новые операции"
                    ImportTotalsScope.UNKNOWN -> null
                }
                listOfNotNull(scope, ResultTexts.period(r)).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = PfTheme.type.caption, color = c.textMuted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(d.space3)) {
                    PfStatTile("Расходы", Modifier.weight(1f), value = t.expense.format())
                    PfStatTile("Доходы", Modifier.weight(1f), value = t.income.format())
                }
            }
            if (ImportNotice.NO_EXPENSES in r.notices) {
                PfNotice("В выписке нет расходов — возможно, выгружен не тот счёт. Проверьте выбор счетов в Т-Банке", tone = NoticeTone.WARNING)
            }
            if (r.unreadCount > 0) {
                PfNotice(
                    "Не прочитали ${countWithNoun(r.unreadCount, "строку", "строки", "строк")} — суммы могут быть неполными",
                    tone = NoticeTone.WARNING,
                    action = { PfLink("Какие строки", onClick = onOpenUnread, inline = true, modifier = Modifier.testTag(StatementsTags.RESULT_UNREAD)) },
                )
            }
            val details = listOfNotNull(ResultTexts.duplicates(r), ResultTexts.accounts(r.accounts), ResultTexts.categories(r))
            if (details.isNotEmpty() || r.uncategorizedCount > 0) {
                PfCard {
                    details.forEachIndexed { i, line ->
                        if (i > 0) Spacer(Modifier.height(d.space2))
                        Text(line, style = PfTheme.type.body, color = c.text)
                    }
                    ResultTexts.uncategorized(r)?.let { text ->
                        Spacer(Modifier.height(d.space2))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$text — ", style = PfTheme.type.body, color = c.text)
                            r.uncategorizedFilters?.let { f -> PfLink("разобрать", onClick = { onOpenSearch(f) }, inline = true) }
                        }
                    }
                }
            }
            val coverageLines = listOfNotNull(ResultTexts.newlyFull(r.coverage), ResultTexts.openedNow(r.coverage)) +
                r.coverage.incompleteMonths.map(ResultTexts::incomplete)
            if (coverageLines.isNotEmpty()) {
                PfNotice(coverageLines.joinToString("\n"), tone = NoticeTone.POSITIVE.takeIf { r.coverage.newlyFullMonths.isNotEmpty() } ?: NoticeTone.INFO)
            }
            val locked = ResultTexts.locked(r.coverage)
            if (locked.isNotEmpty()) {
                PfNotice(locked.joinToString("\n"), title = "Часть аналитики пока закрыта", tone = NoticeTone.INFO)
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = d.space5, vertical = d.space4), verticalArrangement = Arrangement.spacedBy(d.space2)) {
            if (r != null && (r.operationCount == 0 || r.newCount == 0) && uploadEnabled) {
                PfButton("Загрузить другой файл", onClick = onUploadAnother, variant = ButtonVariant.PRIMARY, block = true, modifier = Modifier.testTag(StatementsTags.RESULT_UPLOAD_ANOTHER))
                PfButton("Готово", onClick = onDone, variant = ButtonVariant.GHOST, block = true, modifier = Modifier.testTag(StatementsTags.RESULT_DONE))
            } else {
                PfButton("Посмотреть аналитику", onClick = onOpenAnalytics, variant = ButtonVariant.PRIMARY, block = true, modifier = Modifier.testTag(StatementsTags.RESULT_ANALYTICS))
                PfButton("К операциям", onClick = onDone, variant = ButtonVariant.GHOST, block = true, modifier = Modifier.testTag(StatementsTags.RESULT_DONE))
            }
        }
    }
}
