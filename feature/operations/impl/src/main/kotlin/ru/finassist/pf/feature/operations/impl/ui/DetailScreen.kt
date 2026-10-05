package ru.finassist.pf.feature.operations.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.Coachmark
import ru.finassist.pf.core.designsystem.components.DataRow
import ru.finassist.pf.core.designsystem.components.IconTile
import ru.finassist.pf.core.designsystem.components.ListRow
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.OptionGroup
import ru.finassist.pf.core.designsystem.components.OptionRow
import ru.finassist.pf.core.designsystem.components.PageHeader
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.components.SearchField
import ru.finassist.pf.core.designsystem.components.SectionTitle
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Operation details (mockups Detail / DetailRefund / DetailTransfer / DetailHint) and the category picker sheet. */
@Composable
fun DetailScreen(state: DetailViewModel.UiState, vm: DetailViewModel, onBack: () -> Unit) {
    val c = PfTheme.colors
    val op = state.operation
    LaunchedEffect(state.replaced) { if (state.replaced) onBack() }
    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageHeader(title = op?.title ?: "Операция", onBack = onBack)
        when {
            op == null && state.error != null -> Column(Modifier.padding(ScreenPadding)) {
                Notice(if (state.error is AppError.NotFound) "Операции больше нет — возможно, загрузка удалена" else "Не получилось загрузить операцию", tone = NoticeTone.Warning, alert = true, action = { PfLink("Повторить", onClick = vm::load) })
            }
            op == null -> Unit
            else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding)) {
                Column(Modifier.fillMaxWidth().padding(vertical = PfSpace.s4), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconTile(op.categoryIcon)
                    Spacer(Modifier.height(PfSpace.s3))
                    Text(op.kindName, style = PfTheme.type.caption, color = c.textMuted)
                    Text(op.amountText, style = PfTheme.type.amountLg, color = if (op.isIncome) c.positive else c.text)
                    Text(op.occurredAt.atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")), style = PfTheme.type.caption, color = c.textMuted)
                }
                PfCard(flush = true) {
                    ListRow(title = "Категория", value = op.categoryName, valueAccent = true, description = op.noteDetails, onClick = vm::openPicker, divider = false)
                }
                if (state.showHint) {
                    Spacer(Modifier.height(PfSpace.s2))
                    Coachmark("Категорию проставили мы. Если она неверная — нажмите на строку, чтобы поменять", onClose = { vm.dismissHint(false) }, onNever = { vm.dismissHint(true) })
                }
                Spacer(Modifier.height(PfSpace.s6))
                SectionTitle("Из выписки")
                Spacer(Modifier.height(PfSpace.s2))
                PfCard {
                    if (op.isPair) {
                        op.fromAccount?.let { DataRow("Счёт списания", it.label, valueLabel = it.spoken) }
                        op.toAccount?.let { DataRow("Счёт зачисления", it.label, valueLabel = it.spoken) }
                        DataRow("В выписке", "Между своими счетами", divider = false)
                    } else {
                        op.account?.let { DataRow("Счёт", it.label, valueLabel = it.spoken) }
                        op.bankCategory?.let { DataRow("Категория банка", it, sublabel = "как в выписке") }
                        op.description?.takeIf { it != op.title }?.let { DataRow("Описание", it) }
                        op.postedAt?.let { DataRow("Проведена", it.atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm"))) }
                        op.mcc?.let { DataRow("MCC", it) }
                        DataRow("Источник", op.sourceName ?: "Выписка", divider = false)
                    }
                }
                Spacer(Modifier.height(PfSpace.s6))
            }
        }
    }
    if (state.pickerOpen) CategoryPicker(state, vm)
}

@Composable
private fun CategoryPicker(state: DetailViewModel.UiState, vm: DetailViewModel) {
    val c = PfTheme.colors
    PfBottomSheet(title = "Категория", onDismiss = vm::closePicker, footer = {
        Column {
            if (state.saveError != null) {
                Notice(if (state.saveError is AppError.CategoryNotAssignable) "Эта категория не подходит к операции" else "Не получилось сохранить. Повторите", tone = NoticeTone.Warning, alert = true)
                Spacer(Modifier.height(PfSpace.s2))
            }
            PfButton("Сохранить", onClick = vm::save, variant = ButtonVariant.Primary, block = true, busy = state.saving, busyText = "Сохраняем…")
        }
    }) {
        Spacer(Modifier.height(PfSpace.s2))
        SearchField(value = state.pickerQuery, onValueChange = vm::onPickerQuery, placeholder = "Найти категорию")
        Spacer(Modifier.height(PfSpace.s2))
        val groups = state.pickerGroups
        if (groups.isEmpty()) Text("Ничего не нашли", style = PfTheme.type.caption, color = c.textMuted, modifier = Modifier.padding(vertical = PfSpace.s4))
        LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
            groups.forEach { (title, cats) ->
                item(key = "h-$title") { OptionGroup(title) {} }
                items(cats.size, key = { "c-" + cats[it].id }) { i ->
                    val cat = cats[i]
                    OptionRow(cat.name, selected = state.pickerSelected == cat.id, onClick = { vm.onPickerSelect(cat.id) }, description = cat.note)
                }
            }
        }
    }
}
