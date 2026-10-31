package ru.finassist.pf.feature.analytics.impl.ui

import ru.finassist.pf.core.designsystem.components.ConnectionFailureState
import ru.finassist.pf.core.designsystem.components.ErrorMessage
import androidx.compose.runtime.getValue
import ru.finassist.pf.core.api.model.ActionType
import ru.finassist.pf.core.api.model.Block
import ru.finassist.pf.core.api.model.BlockAction
import ru.finassist.pf.core.api.model.BlockType
import ru.finassist.pf.core.blocks.BlockContext
import ru.finassist.pf.core.blocks.BlockLayout
import ru.finassist.pf.core.blocks.BlockTags
import ru.finassist.pf.core.blocks.BlocksColumn
import ru.finassist.pf.core.blocks.CatalogV1
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.delay
import ru.finassist.pf.core.api.model.Analytics
import ru.finassist.pf.core.api.model.AnalyticsParams
import ru.finassist.pf.core.api.model.Coverage
import ru.finassist.pf.core.api.model.OperationsFilter
import ru.finassist.pf.core.api.model.PeriodTypeCode
import ru.finassist.pf.core.api.model.TransferMode
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.StaleData
import ru.finassist.pf.core.common.time.toApiString
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfAskCard
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfChip
import ru.finassist.pf.core.designsystem.components.PfCoachmark
import ru.finassist.pf.core.designsystem.components.TextPart
import ru.finassist.pf.core.designsystem.components.PfLinkedText
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfNoticeLink
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.components.PfOptionRow
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.components.PfPeriodNav
import ru.finassist.pf.core.designsystem.components.PfSegmentedControl
import ru.finassist.pf.core.designsystem.components.PfSnackbar
import ru.finassist.pf.core.designsystem.components.PfStatTile
import ru.finassist.pf.core.designsystem.components.PfTabHeader
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.LocalPfClock
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.analytics.impl.domain.AnalyticsTexts

/** Navigation callbacks of the Analytics screen. */
class AnalyticsActions(
    val openSearch: (OperationsFilter) -> Unit,
    val openChat: (TransferMode) -> Unit,
    val openUpload: () -> Unit,
    val openUnreadLines: (from: String, to: String) -> Unit,
    val back: (() -> Unit)?,
)

private val PERIOD_TYPES = listOf(PeriodTypeCode.MONTH, PeriodTypeCode.QUARTER, PeriodTypeCode.YEAR)

@Composable
fun AnalyticsScreen(params: AnalyticsParams?, actions: AnalyticsActions, vm: AnalyticsViewModel = hiltViewModel()) {
    LifecycleResumeEffect(params) {
        vm.onResume(params)
        onPauseOrDispose {}
    }
    val state by vm.state.collectAsStateWithLifecycle()
    AnalyticsContent(state, actions, vm)
}

/**
 * User actions of the analytics screen. Implemented by [AnalyticsViewModel]; design-check snapshots pass a
 * no-op to render [AnalyticsContent] from a ready state.
 */
interface AnalyticsHandlers {
    fun load(quiet: Boolean = false)
    fun retryPeriodChange()
    fun consumeSnackbar()
    fun setPeriodType(type: PeriodTypeCode)
    fun goTo(key: PeriodKey)
    fun openSheet(sheet: AnalyticsSheet?)
    fun nextHint(stop: Boolean)
    fun setTransferMode(mode: TransferMode)
    fun currentTransferMode(): TransferMode
    fun toggleExpanded(id: String)
    fun onAction(block: Block, action: BlockAction, navigate: (BlockAction) -> Unit)
    fun onBlockShown(block: Block)
}

@Composable
internal fun AnalyticsContent(state: AnalyticsUiState, actions: AnalyticsActions, vm: AnalyticsHandlers) {
    val d = PfTheme.dimens
    Column(Modifier.fillMaxSize().testTag(AnalyticsTags.SCREEN)) {
        if (actions.back != null) PfPageHeader("Аналитика", onBack = actions.back) else PfTabHeader("Аналитика")
        val a = state.data
        state.limitErrorPresentation?.let {
            ErrorMessage(it, "Не получилось обновить лимит вопросов", modifier = Modifier.padding(PfTheme.dimens.space4))
        }
        state.periodChangeErrorPresentation?.let { presentation ->
            ErrorMessage(presentation, "Не удалось сменить период", modifier = Modifier.padding(d.space4),
                onRetry = if (state.periodChangeRetryReady) ({ vm.retryPeriodChange() }) else null,
                retryModifier = Modifier.testTag(AnalyticsTags.PERIOD_RETRY))
        }
        state.errorPresentation?.let { presentation ->
            ErrorMessage(presentation, state.snackbar ?: "Не получилось загрузить аналитику",
                modifier = Modifier.padding(d.space4), onRetry = if (!state.retryReady || state.snackbar != null) null else ({ vm.load() }))
        }
        when {
            state.loading || (a?.hasData == false && a.state?.recalculating == true) -> Column(
                Modifier.weight(1f).fillMaxWidth().padding(d.space5).testTag(AnalyticsTags.UPDATING),
                verticalArrangement = Arrangement.Center,
            ) {
                PfEmptyState(PfIcons.BAR_CHART, "Обновляем аналитику", "Подготавливаем показатели по вашим выпискам")
            }
            state.errorPresentation != null && a == null -> Unit
            state.offline -> ConnectionFailureState(modifier = Modifier.testTag(AnalyticsTags.OFFLINE)) {
                PfButton("Повторить", onClick = { vm.load() }, variant = ButtonVariant.PRIMARY, modifier = Modifier.testTag(AnalyticsTags.RETRY))
            }
            state.preparationTimedOut -> PfEmptyState(
                PfIcons.BAR_CHART, "Аналитика ещё готовится", "Выписка загружена. Попробуйте обновить показатели чуть позже",
                modifier = Modifier.testTag(AnalyticsTags.PREPARATION_TIMEOUT),
            ) {
                PfButton("Повторить", onClick = { vm.load() }, enabled = state.retryReady,
                    variant = ButtonVariant.PRIMARY, modifier = Modifier.testTag(AnalyticsTags.RETRY))
            }
            a == null || !a.hasData -> Column(
                Modifier.weight(1f).fillMaxWidth().padding(start = d.space5, end = d.space5, bottom = d.space6).testTag(AnalyticsTags.EMPTY),
                verticalArrangement = Arrangement.Center,
            ) {
                PfEmptyState(
                    PfIcons.BAR_CHART,
                    "Аналитики пока нет",
                    if (state.blocks.upload) {
                        "Загрузите выписку — покажем расходы по категориям, доходы и разницу между ними, и помощник сможет ответить на вопросы о ваших деньгах"
                    } else {
                        null
                    },
                    padding = PaddingValues(start = d.space8, top = d.space8, end = d.space8, bottom = d.space6),
                )
                if (state.blocks.upload) PfButton("Загрузить выписку", onClick = actions.openUpload, variant = ButtonVariant.PRIMARY, block = true)
            }
            else -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = d.space5).padding(top = d.space2, bottom = d.space6),
            ) {
                Content(a, state, vm, actions)
            }
        }
        state.snackbar?.takeIf { state.errorPresentation == null }?.let { text ->
            LaunchedEffect(text) {
                delay(3000)
                vm.consumeSnackbar()
            }
            PfSnackbar(text, Modifier.padding(d.space4))
        }
    }
    Sheets(state, vm)
}

/**
 * Screen body in the mockup order (FIN-32): assistant card → period → period type → transfers chip → tiles →
 * expense chart → categories → income → insights → «Доходы минус расходы по месяцам».
 */
@Composable
private fun ColumnScope.Content(a: Analytics, state: AnalyticsUiState, vm: AnalyticsHandlers, actions: AnalyticsActions) {
    val params = a.params ?: return
    val period = a.period ?: return
    val blocks = state.blocks
    val d = PfTheme.dimens

    if (blocks.assistant) {
        val limit = state.limit
        PfAskCard(
            modifier = Modifier.testTag(AnalyticsTags.ASK),
            onClick = { actions.openChat(params.transferMode.effective) },
            remaining = limit?.remaining ?: 5,
            total = limit?.dailyMax ?: 5,
            exhausted = limit != null && limit.remaining <= 0,
        )
    }
    val early = AnalyticsTexts.isEarly(params.period, period, hasPrevious = a.navigation?.previous != null)
    // The coachmark lies over the controls under the card (mockup `AnalyticsHintAsk`) and does not move them.
    WithCoachmark(
        show = state.hint == AnalyticsHint.ASK,
        text = "Помощник отвечает по вашим операциям, 5 вопросов в день",
        vm = vm,
    ) {
        // Stale: where the statement data ends (`state.data_to`), not the last operation; days in the profile zone (FIN-125).
        val clock = LocalPfClock.current
        a.state?.let { s -> StaleData.banner(s.stale, s.dataTo, s.lastOperationAt, clock.zone, clock.today()) }?.let { text ->
            Gap(d.space3)
            PfNotice(
                text,
                modifier = Modifier.testTag(AnalyticsTags.STALE),
                tone = NoticeTone.INFO,
                action = if (blocks.upload) ({ PfNoticeLink("Загрузить новую выписку", onClick = actions.openUpload) }) else null,
            )
        }
        Gap(d.space3)
        PfPeriodNav(
            label = AnalyticsTexts.periodLabel(params.date, period, params.period, early),
            onPrev = a.navigation?.previous?.let { key -> { vm.goTo(key) } },
            onNext = a.navigation?.next?.let { key -> { vm.goTo(key) } },
            onPick = { vm.openSheet(AnalyticsSheet.PERIODS) },
            unitName = AnalyticsTexts.unitName(params.period),
        )
        Gap(d.space2)
        PfSegmentedControl(
            options = listOf("Месяц", "Квартал", "Год"),
            selected = PERIOD_TYPES.indexOf(params.period).coerceAtLeast(0),
            onSelect = { vm.setPeriodType(PERIOD_TYPES[it]) },
        )
        if (blocks.transfersFilter) {
            Gap(d.space3)
            Row {
                val without = params.transferMode.effective == TransferMode.WITHOUT
                PfChip(
                    if (without) "Без переводов" else "С переводами", onClick = { vm.openSheet(AnalyticsSheet.TRANSFERS) }, selected = without, dropdown = true,
                    modifier = Modifier.testTag(AnalyticsTags.TRANSFERS),
                )
            }
        }
    }
    a.state?.let { s ->
        if (s.unreadLinesCount > 0) {
            // A caption with an inline link, not a notice (mockup `AnalyticsPartial`).
            Gap(d.space3)
            PfLinkedText(
                listOf(
                    TextPart.Plain(AnalyticsTexts.unreadLines(s.unreadLinesCount, params.period, period) + " · "),
                    TextPart.Link("Подробнее", AnalyticsTags.UNREAD) {
                        actions.openUnreadLines(period.range.from.toApiString(), period.range.to.toApiString())
                    },
                ),
                modifier = Modifier.testTag(AnalyticsTags.UNREAD),
            )
        }
        if (s.recalculating) {
            Gap(d.space3)
            PfNotice(
                if (state.recalculationPaused) "Не удалось обновить показатели. Показаны данные до изменений"
                else "Пересчитываем после изменений — цифры обновятся через несколько секунд",
                tone = NoticeTone.INFO,
                action = if (state.recalculationPaused && state.retryReady) ({
                    PfNoticeLink("Повторить", onClick = { vm.load() }, modifier = Modifier.testTag(AnalyticsTags.RETRY))
                }) else null,
            )
        }
    }
    // A past month or an incomplete quarter/year says which part of it the numbers cover; the tiles follow 8 dp
    // under it (mockup `AnalyticsYear`: `margin-bottom: space-2 − space-4`).
    val coverageCaption = AnalyticsTexts.partialPeriod(period, params.period) ?: AnalyticsTexts.coverage(period).takeIf { !period.isCurrent }
    coverageCaption?.let {
        Gap(d.space4)
        Text(it, style = PfTheme.type.caption, color = PfTheme.colors.textMuted)
    }

    Blocks(a, state, vm, actions, afterCaption = coverageCaption != null)
}

/** Registry of the renderers (FIN-7): the catalog version this client draws. */
private val registry = CatalogV1.registry()

/** Test tags of the blocks — the ones the UI tests have used since before FIN-4. */
private object AnalyticsBlockTags : BlockTags("analytics") {
    override fun tile(id: String) = if (id == "daily_expense") AnalyticsTags.TILE_DAILY else "analytics.tile.$id"
    override fun item(blockId: String) = when (blockId) {
        "expense_categories" -> AnalyticsTags.EXPENSE_CATEGORY
        "income_categories" -> AnalyticsTags.INCOME_CATEGORY
        else -> "analytics.$blockId.item"
    }
    override fun block(id: String) = AnalyticsTags.block(id)
    override fun more(blockId: String) = if (blockId == "expense_categories") AnalyticsTags.ALL_CATEGORIES else "analytics.$blockId.more"
    override fun chart(blockId: String) = when (blockId) {
        "monthly_chart" -> "analytics.expense.monthly"
        "balance" -> "analytics.balance.monthly"
        "expense_categories" -> "analytics.expense.categories"
        else -> "analytics.insight.$blockId"
    }
}

/**
 * The blocks of the response in the server's order (FIN-4): without the ones switched off by `analytics.block.<id>`,
 * drawn by the registry; a block it cannot draw is skipped. Spacing follows the mockups by the kind of block.
 */
@Composable
private fun Blocks(a: Analytics, state: AnalyticsUiState, vm: AnalyticsHandlers, actions: AnalyticsActions, afterCaption: Boolean) {
    val d = PfTheme.dimens
    val hidden = state.blocks.hidden
    val prepared = remember(a.blocks, hidden) {
        // Prepared first: a heading over blocks that did not decode goes as well.
        BlockLayout.visible(registry.prepareAll(a.blocks), { it.block }) { p -> p.block.id != null && p.block.id in hidden }
    }
    val navigate: (BlockAction) -> Unit = { action ->
        when (action.type) {
            ActionType.OPEN_OPERATIONS -> action.filter?.let(actions.openSearch)
            ActionType.OPEN_ASSISTANT -> actions.openChat(vm.currentTransferMode())
            else -> Unit
        }
    }
    val ctx = BlockContext(
        currency = a.currency,
        tags = AnalyticsBlockTags,
        onAction = { block, action -> vm.onAction(block, action, navigate) },
        expanded = state.expanded,
        onToggleExpanded = vm::toggleExpanded,
        decorate = { block, content ->
            // The coachmark lies over the card (mockup `AnalyticsHintCategories`).
            if (block.id == "expense_categories") {
                WithCoachmark(show = state.hint == AnalyticsHint.CATEGORIES, text = "Категории проставили мы — любую можно поправить в операции", vm = vm) { content() }
            } else {
                content()
            }
        },
    )
    BlocksColumn(
        prepared, ctx,
        spacing = { previous, next ->
            when {
                previous == null -> if (afterCaption) d.space2 else d.space4
                next.type == BlockType.INSIGHT_CARD && previous.type == BlockType.HEADING -> d.space2
                next.type == BlockType.INSIGHT_CARD && previous.type == BlockType.INSIGHT_CARD -> d.space3
                next.type == BlockType.RANKED_LIST && previous.type == BlockType.RANKED_LIST -> d.space4
                else -> d.space6
            }
        },
        exposureKey = state.response,
        onShown = vm::onBlockShown,
    )
}

@Composable
private fun Gap(height: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(height))

/**
 * [content] with a coachmark laid over its top, 12 dp down (mockups: `position: absolute; top: space-3`): the
 * hint covers what lies under it instead of pushing the screen down.
 */
@Composable
private fun WithCoachmark(show: Boolean, text: String, vm: AnalyticsHandlers, content: @Composable ColumnScope.() -> Unit) {
    if (!show) {
        Column(Modifier.fillMaxWidth(), content = content)
        return
    }
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), content = content)
        PfCoachmark(
            text,
            onClose = { vm.nextHint(stop = false) },
            onNever = { vm.nextHint(stop = true) },
            modifier = Modifier.padding(top = PfTheme.dimens.space3).zIndex(1f),
        )
    }
}

@Composable
private fun Sheets(state: AnalyticsUiState, vm: AnalyticsHandlers) {
    when (state.sheet) {
        AnalyticsSheet.PERIODS -> PfBottomSheet("Период", onDismiss = { vm.openSheet(null) }) {
            val current = state.data?.params?.date
            val periods = state.periods
            if (state.periodsFailed) {
                Column(Modifier.padding(vertical = PfTheme.dimens.space5)) {
                    val retry = if (!state.periodsRetryReady) null else ({ vm.openSheet(AnalyticsSheet.PERIODS) })
                    state.periodsErrorPresentation?.let {
                        ErrorMessage(it, "Не получилось загрузить периоды", onRetry = retry)
                    } ?: run {
                        Text("Не получилось загрузить периоды", style = PfTheme.type.body, color = PfTheme.colors.textMuted)
                        retry?.let { PfLink("Повторить", onClick = it) }
                    }
                }
            } else if (periods == null) {
                Text("Загружаем…", style = PfTheme.type.body, color = PfTheme.colors.textMuted, modifier = Modifier.padding(vertical = PfTheme.dimens.space5))
            } else {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    periods.forEach { p ->
                        PfOptionRow(
                            AnalyticsTexts.periodTitle(p.key),
                            selected = p.key == current,
                            onClick = { vm.goTo(p.key) },
                            description = if (p.coverage.effective != Coverage.COMPLETE) "неполный" else null,
                            modifier = Modifier.testTag(AnalyticsTags.PERIOD_OPTION),
                        )
                    }
                }
            }
        }
        AnalyticsSheet.TRANSFERS -> PfBottomSheet("Переводы людям", onDismiss = { vm.openSheet(null) }) {
            val mode = vm.currentTransferMode()
            Text(
                "Переводы по номеру телефона и на карту — часто не покупки и не зарплата, а долги, подарки или общие траты",
                style = PfTheme.type.body, color = PfTheme.colors.textMuted,
                modifier = Modifier.padding(top = PfTheme.dimens.space1, bottom = PfTheme.dimens.space3),
            )
            PfOptionRow(
                "С переводами", selected = mode == TransferMode.WITH, onClick = { vm.setTransferMode(TransferMode.WITH) },
                icon = "users", description = "Учитываем в расходах и доходах",
                modifier = Modifier.testTag(AnalyticsTags.TRANSFERS_WITH),
            )
            PfOptionRow(
                "Без переводов", selected = mode == TransferMode.WITHOUT, onClick = { vm.setTransferMode(TransferMode.WITHOUT) },
                icon = PfIcons.BAR_CHART, description = "Убираем и входящие, и исходящие переводы",
                modifier = Modifier.testTag(AnalyticsTags.TRANSFERS_WITHOUT),
            )
            Text(
                "Переводы между своими счетами не считаем ни в одном режиме. Выбор действует на всю «Аналитику», цифры ведут в Поиск с тем же правилом",
                style = PfTheme.type.hint, color = PfTheme.colors.textMuted,
                modifier = Modifier.padding(top = PfTheme.dimens.space3),
            )
        }
        null -> Unit
    }
}


/** Test tags of the analytics screen (UI tests in `.maestro/`, convention in docs/e2e.md). */
internal object AnalyticsTags {
    const val UPDATING = "analytics.updating"
    const val PREPARATION_TIMEOUT = "analytics.preparation_timeout"
    /** A block of the screen by its id (`analytics.block.tiles`): the ids are the server's (FIN-4). */
    fun block(id: String) = "analytics.block.$id"
    const val SCREEN = "analytics.screen"
    const val EMPTY = "analytics.empty"
    const val OFFLINE = "analytics.offline"
    const val RETRY = "analytics.retry"
    const val PERIOD_RETRY = "analytics.period.retry"
    const val ASK = "analytics.ask"
    const val UNREAD = "analytics.unread"
    /** The stale statement banner: «Данные в приложении по … — с тех пор прошло больше двух недель». */
    const val STALE = "analytics.stale"
    const val TRANSFERS = "analytics.transfers"
    const val TRANSFERS_WITH = "analytics.transfers.with"
    const val TRANSFERS_WITHOUT = "analytics.transfers.without"
    const val PERIOD_OPTION = "analytics.period.option"
    const val EXPENSE_CATEGORY = "analytics.expense_category"
    const val INCOME_CATEGORY = "analytics.income_category"
    const val ALL_CATEGORIES = "analytics.all_categories"
    // Tiles: the chart id of a tile is also its test tag (`Tile` → `PfStatTile(modifier.testTag(chartId))`).
    const val TILE_BALANCE = "analytics.tile.balance"
    const val TILE_FORECAST = "analytics.tile.forecast"
    const val TILE_DAILY = "analytics.tile.daily"
    const val TILE_INCOME = "analytics.tile.income"
    const val TILE_EXPENSE = "analytics.tile.expense"

    // Same names as the flags in docs/flags.md that hide these blocks.
    const val BLOCK_TILES = "analytics.block.tiles"
    const val BLOCK_MONTHLY_CHART = "analytics.block.monthly_chart"
    const val BLOCK_EXPENSE_CATEGORIES = "analytics.block.expense_categories"
    const val BLOCK_INCOME_CATEGORIES = "analytics.block.income_categories"
    const val BLOCK_REGULAR_PAYMENTS = "analytics.block.regular_payments"
    const val BLOCK_NOTABLE_SPENDING = "analytics.block.notable_spending"
    const val BLOCK_BANK_FEES = "analytics.block.bank_fees"
    const val BLOCK_SMALL_FREQUENT = "analytics.block.small_frequent"
}
