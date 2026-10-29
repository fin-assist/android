package ru.finassist.pf.feature.operations.impl.ui

import ru.finassist.pf.core.designsystem.components.ErrorMessage
import ru.finassist.pf.core.designsystem.theme.LocalPfClock
import ru.finassist.pf.core.designsystem.theme.PfInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.finassist.pf.core.api.model.Account
import ru.finassist.pf.core.api.model.OperationDetails
import ru.finassist.pf.core.api.model.OperationKind
import ru.finassist.pf.core.api.model.OperationStatus
import ru.finassist.pf.core.common.money.Money
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfDataRow
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfIconTile
import ru.finassist.pf.core.designsystem.components.PfListRow
import ru.finassist.pf.core.designsystem.components.PfSectionTitle
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.components.PfSnackbar
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.operations.impl.domain.OperationFormat
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

@Composable
fun DetailScreen(onBack: () -> Unit, vm: DetailViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clock = LocalPfClock.current
    DetailContent(
        state = state, zone = clock.zone, today = clock.today(), onBack = onBack, onRetry = vm::load,
        onOpenPicker = vm::openPicker, onClosePicker = vm::closePicker, onChoose = vm::choose, onSave = vm::save,
        onSnackbarShown = vm::consumeSnackbar,
    )
}

/**
 * Operation details drawn from a ready state: no ViewModel, so design-check snapshots render it with mockup data.
 * [pickerQuery] is the initial text of the category search in the picker.
 */
@Composable
internal fun DetailContent(
    state: DetailUiState,
    zone: ZoneId,
    today: LocalDate,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenPicker: () -> Unit,
    onClosePicker: () -> Unit,
    onChoose: (categoryId: String) -> Unit,
    onSave: () -> Unit,
    onSnackbarShown: () -> Unit,
    pickerQuery: String = "",
) {
    val d = PfTheme.dimens
    Column(Modifier.fillMaxSize().windowInsetsPadding(PfInsets.navigationBars).testTag(OperationsTags.DETAIL)) {
        val op = state.operation
        // The operation's name is the title (mockup `Detail`); «Операция» while it loads or fails.
        PfPageHeader(
            op?.let { OperationFormat.title(it.title) } ?: "Операция", onBack = onBack,
            modifier = if (op != null) Modifier.testTag(OperationsTags.DETAIL_TITLE) else Modifier,
        )
        state.errorPresentation?.let { presentation ->
            ErrorMessage(
                presentation,
                if (state.notFound) "Операции больше нет. Возможно, удалили загрузку, в которой она была" else "Не получилось загрузить. Попробуйте ещё раз",
                modifier = Modifier.padding(d.space4),
                onRetry = if (state.notFound || !state.retryReady) null else onRetry,
            )
        }
        when {
            state.loading -> Unit
            state.errorPresentation != null && (state.notFound || op == null) -> Unit
            state.notFound -> PfEmptyState(PfIcons.FILE_TEXT, "Операции больше нет", "Возможно, удалили загрузку, в которой она была")
            op == null -> PfEmptyState(PfIcons.ALERT, "Не получилось загрузить", "Проверьте интернет и попробуйте ещё раз") {
                PfButton("Повторить", onClick = onRetry, variant = ButtonVariant.PRIMARY)
            }
            else -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = d.space5, end = d.space5, top = d.space4, bottom = d.space4),
                verticalArrangement = Arrangement.spacedBy(d.space5),
            ) {
                Hero(op, zone)
                PfCard(flush = true) {
                    PfListRow(
                        title = "Категория",
                        description = op.noteDetails ?: if (op.isCategoryManual) "Вы выбрали сами" else null,
                        value = op.categoryName,
                        valueAccent = true,
                        onClick = onOpenPicker,
                        divider = false,
                        modifier = Modifier.testTag(OperationsTags.DETAIL_CATEGORY),
                    )
                }
                Column {
                    PfSectionTitle("Из выписки")
                    Spacer(Modifier.height(d.space2))
                    PfCard {
                        val rows = OperationFormat.statementRows(op) { dateTime(it, zone, today) }
                        rows.forEachIndexed { i, row ->
                            PfDataRow(
                                row.label, row.value, sublabel = row.sublabel, divider = i < rows.lastIndex,
                                modifier = row.spoken?.let { spoken -> Modifier.semantics { contentDescription = "${row.label}: $spoken" } } ?: Modifier,
                            )
                        }
                    }
                }
                if (op.status == OperationStatus.PENDING) {
                    PfNotice("Операция ещё не проведена банком — сумма может измениться", tone = NoticeTone.INFO)
                }
            }
        }
        state.snackbar?.let { text ->
            LaunchedEffect(text) {
                delay(3000)
                onSnackbarShown()
            }
            PfSnackbar(text, Modifier.padding(d.space4))
        }
    }

    val op = state.operation
    if (state.picker && op != null) {
        PfBottomSheet(
            "Категория",
            onDismiss = onClosePicker,
            scrollable = false,
            footer = {
                state.pickerError?.let {
                    val presentation = state.pickerErrorPresentation
                    if (presentation != null) ErrorMessage(presentation, it)
                    else PfNotice(it, tone = NoticeTone.WARNING, alert = true)
                    Spacer(Modifier.height(PfTheme.dimens.space3))
                }
                PfButton(
                    "Сохранить",
                    onClick = onSave,
                    variant = ButtonVariant.PRIMARY,
                    block = true,
                    enabled = state.draftCategoryId != null && state.draftCategoryId != op.categoryId,
                    busy = state.saving,
                    busyText = "Сохраняем…",
                    modifier = Modifier.testTag(OperationsTags.DETAIL_SAVE),
                )
            },
        ) {
            if (op.kind == OperationKind.OWN_TRANSFER) {
                Text(
                    "Если это не перевод себе, разделим его на списание и зачисление: выбранная категория — подходящей стороне, другой — категория банка",
                    style = PfTheme.type.caption, color = PfTheme.colors.textMuted,
                )
            }
            CategoryList(
                categories = state.categories,
                kinds = state.pickerKinds,
                selectedId = state.draftCategoryId,
                onSelect = { onChoose(it.id) },
                onlyAssignable = true,
                initialQuery = pickerQuery,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

/**
 * Mockup `Detail`: the category tile and, beside it, the kind («Списание» / «Зачисление» / «Перевод»), the amount
 * and «25.09.2026, 19:11».
 */
@Composable
private fun Hero(op: OperationDetails, zone: ZoneId) {
    val c = PfTheme.colors
    val amount = when (op.kind) {
        OperationKind.EXPENSE -> OperationFormat.money(-op.amount, op.currency, Money.Sign.AUTO)
        OperationKind.INCOME -> OperationFormat.money(op.amount, op.currency, Money.Sign.ALWAYS)
        else -> OperationFormat.money(op.amount, op.currency, Money.Sign.NONE)
    }
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(PfTheme.dimens.space3)) {
        PfIconTile(op.categoryIcon)
        Column {
            Text(OperationFormat.kindLabel(op.kind), style = PfTheme.type.caption, color = c.textMuted)
            Text(amount, style = PfTheme.type.amountLg, color = if (op.kind == OperationKind.INCOME) c.positive else c.text, maxLines = 1)
            Text(OperationFormat.numericDateTime(op.occurredAt, zone), style = PfTheme.type.caption.copy(fontFeatureSettings = "tnum"), color = c.textMuted)
        }
    }
}

private fun dateTime(at: OffsetDateTime, zone: ZoneId, today: LocalDate): String {
    val local = at.atZoneSameInstant(zone)
    return "${RussianDates.day(local.toLocalDate(), today.year)}, %02d:%02d".format(local.hour, local.minute)
}
