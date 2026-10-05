package ru.finassist.pf.feature.statements.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.EmptyState
import ru.finassist.pf.core.designsystem.components.ListRow
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PageHeader
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfDialog
import ru.finassist.pf.core.designsystem.components.PfIconButton
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** «История загрузок» (mockups UploadHistory / UploadHistoryEmpty / UploadHistoryDelete*). */
@Composable
fun UploadHistoryScreen(
    state: UploadHistoryViewModel.UiState,
    vm: UploadHistoryViewModel,
    onBack: () -> Unit,
    onUpload: () -> Unit,
    onUnreadLines: (uploadId: String) -> Unit,
) {
    val c = PfTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageHeader(title = "История загрузок", onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = PfSpace.s2),
            verticalArrangement = Arrangement.spacedBy(PfSpace.s4),
        ) {
            if (state.deletedNotice) Notice("Загрузка удалена, аналитика пересчитана", tone = NoticeTone.Positive, alert = true)
            when {
                state.error != null && state.rows.isEmpty() -> {
                    Notice(if (state.error is AppError.Offline) "Нет сети. Проверьте интернет и повторите" else "Не получилось загрузить историю", tone = NoticeTone.Warning, action = { PfLink("Повторить", onClick = vm::load, inline = true) })
                }
                state.loading -> Text("Загружаем…", style = PfTheme.type.body, color = c.textMuted)
                state.rows.isEmpty() -> {
                    EmptyState(icon = "file-text", title = "Выписок пока нет", text = "Загрузите выписку Т-Банка — разберём операции и посчитаем аналитику")
                    if (state.uploadEnabled) PfButton("Загрузить выписку", onClick = onUpload, variant = ButtonVariant.Primary, block = true)
                }
                else -> {
                    PfCard(flush = true) {
                        state.rows.forEachIndexed { i, row ->
                            ListRow(
                                icon = "file-text",
                                title = row.fileName,
                                description = row.period + "\n" + row.uploaded + if (row.unreadCount > 0) " · ${row.unreadCount} непрочит." else "",
                                onClick = if (row.unreadCount > 0) ({ onUnreadLines(row.id) }) else null,
                                showChevron = false,
                                trailing = { PfIconButton("trash", contentDescription = "Удалить загрузку ${row.fileName}", onClick = { vm.askDelete(row) }, tint = c.textMuted) },
                                divider = i < state.rows.lastIndex,
                            )
                        }
                    }
                    Text("Удаление загрузки убирает только операции, которых нет в других загрузках", style = PfTheme.type.caption, color = c.textMuted)
                    if (state.uploadEnabled) PfButton("Загрузить новую выписку", onClick = onUpload, variant = ButtonVariant.Primary, block = true)
                }
            }
            Spacer(Modifier.height(PfSpace.s6))
        }
    }

    state.deleteCandidate?.let { row ->
        PfDialog(
            title = if (row.processing) "Прервать разбор ${row.fileName}?" else "Удалить загрузку ${row.fileName}?",
            description = if (row.processing) "Операции из файла не сохранятся." else "Уберём операции, которых нет в других загрузках, и пересчитаем аналитику. Ручные правки категорий у остальных операций сохранятся.",
            onDismiss = { vm.askDelete(null) },
            confirmText = if (row.processing) "Прервать" else "Удалить загрузку",
            onConfirm = vm::confirmDelete,
            busy = state.deleting,
            busyText = "Удаляем…",
            extra = state.deleteError?.let { e -> { Notice(if (e is AppError.Offline) "Нет сети. Проверьте интернет и повторите" else "Не получилось удалить. Повторите позже", tone = NoticeTone.Warning, alert = true) } },
        )
    }
}
