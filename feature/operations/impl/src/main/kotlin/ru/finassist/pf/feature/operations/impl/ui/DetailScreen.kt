package ru.finassist.pf.feature.operations.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import ru.finassist.pf.core.designsystem.components.PfBadge
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfDataRow
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfIconTile
import ru.finassist.pf.core.designsystem.components.PfListRow
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
    val d = PfTheme.dimens
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        PfPageHeader("Операция", onBack = onBack)
        val op = state.operation
        when {
            state.loading -> Unit
            state.notFound -> PfEmptyState(PfIcons.FILE_TEXT, "Операции больше нет", "Возможно, удалили загрузку, в которой она была")
            state.error || op == null -> PfEmptyState(PfIcons.ALERT, "Не получилось загрузить", "Проверьте интернет и попробуйте ещё раз") {
                PfButton("Повторить", onClick = vm::load, variant = ButtonVariant.PRIMARY)
            }
            else -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = d.space5, vertical = d.space2),
                verticalArrangement = Arrangement.spacedBy(d.space4),
            ) {
                Hero(op)
                PfCard(flush = true) {
                    PfListRow(
                        title = op.categoryName,
                        icon = op.categoryIcon,
                        description = op.noteDetails ?: if (op.isCategoryManual) "Вы выбрали сами" else null,
                        value = "Изменить",
                        valueAccent = true,
                        onClick = vm::openPicker,
                        divider = false,
                    )
                }
                PfCard {
                    val zone = ZoneId.systemDefault()
                    PfDataRow("Дата и время", dateTime(op.occurredAt, zone))
                    op.postedAt?.let { PfDataRow("Проведена банком", dateTime(it, zone)) }
                    if (op.kind == OperationKind.OWN_TRANSFER) {
                        AccountRow("Списание", op.fromAccount)
                        AccountRow("Зачисление", op.toAccount)
                    } else {
                        AccountRow("Счёт", op.account)
                    }
                    op.description?.let { PfDataRow("Описание в выписке", it) }
                    op.bankCategory?.let { PfDataRow("Категория банка", it, sublabel = "как в выписке") }
                    op.mcc?.let { PfDataRow("MCC", it) }
                    PfDataRow("Источник", op.sourceName, divider = false)
                }
                if (op.status == OperationStatus.PENDING) {
                    PfNotice("Операция ещё не проведена банком — сумма может измениться", tone = NoticeTone.INFO)
                }
            }
        }
        state.snackbar?.let { text ->
            LaunchedEffect(text) {
                delay(3000)
                vm.consumeSnackbar()
            }
            PfSnackbar(text, Modifier.padding(d.space4))
        }
    }

    val op = state.operation
    if (state.picker && op != null) {
        PfBottomSheet(
            "Категория",
            onDismiss = vm::closePicker,
            footer = {
                state.pickerError?.let {
                    PfNotice(it, tone = NoticeTone.WARNING, alert = true)
                    Spacer(Modifier.height(PfTheme.dimens.space3))
                }
                PfButton(
                    "Сохранить",
                    onClick = vm::save,
                    variant = ButtonVariant.PRIMARY,
                    block = true,
                    enabled = state.draftCategoryId != null && state.draftCategoryId != op.categoryId,
                    busy = state.saving,
                    busyText = "Сохраняем…",
                )
            },
        ) {
            if (op.kind == OperationKind.OWN_TRANSFER) {
                Text(
                    "Если это не перевод себе, разделим его на списание и зачисление: выбранная категория — подходящей стороне, другой — категория банка",
                    style = PfTheme.type.caption, color = PfTheme.colors.textMuted,
                    modifier = Modifier.padding(horizontal = PfTheme.dimens.space5),
                )
            }
            CategoryList(
                categories = state.categories,
                kinds = state.pickerKinds,
                selectedId = state.draftCategoryId,
                onSelect = { vm.choose(it.id) },
                onlyAssignable = true,
            )
        }
    }
}

@Composable
private fun Hero(op: OperationDetails) {
    val c = PfTheme.colors
    val amount = when (op.kind) {
        OperationKind.EXPENSE -> OperationFormat.money(-op.amount, op.currency, Money.Sign.AUTO)
        OperationKind.INCOME -> OperationFormat.money(op.amount, op.currency, Money.Sign.ALWAYS)
        else -> OperationFormat.money(op.amount, op.currency, Money.Sign.NONE)
    }
    Column {
        PfIconTile(op.categoryIcon)
        Spacer(Modifier.height(PfTheme.dimens.space3))
        Text(op.title, style = PfTheme.type.title2, color = c.text)
        Text(amount, style = PfTheme.type.amountHero, color = if (op.kind == OperationKind.INCOME) c.positive else c.text)
        op.note?.let {
            Spacer(Modifier.height(PfTheme.dimens.space1))
            PfBadge(it)
        }
    }
}

@Composable
private fun AccountRow(label: String, account: Account?) {
    if (account == null) return
    val text = listOfNotNull(account.typeName, account.mask).joinToString(" ")
    PfDataRow(label, text, modifier = Modifier.semantics { account.spoken?.let { contentDescription = "$label: $it" } })
}

private fun dateTime(at: OffsetDateTime, zone: ZoneId): String {
    val local = at.atZoneSameInstant(zone)
    return "${RussianDates.day(local.toLocalDate(), LocalDate.now(zone).year)}, %02d:%02d".format(local.hour, local.minute)
}
