package ru.finassist.pf.feature.statements.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.model.StatementsList
import ru.finassist.pf.core.api.model.Upload
import ru.finassist.pf.core.api.model.UploadStatus
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.countWithNoun
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfDialog
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfIconButton
import ru.finassist.pf.core.designsystem.components.PfListRow
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.components.PfSnackbar
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.statements.impl.data.StatementsRepositoryImpl
import java.time.LocalDate
import javax.inject.Inject

data class HistoryUiState(
    val loading: Boolean = true,
    val offline: Boolean = false,
    val list: StatementsList? = null,
    val confirmDelete: Upload? = null,
    val deleting: Boolean = false,
    val snackbar: String? = null,
    val uploadEnabled: Boolean = true,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val api: StatementsApi,
    private val repository: StatementsRepositoryImpl,
    private val tracker: Tracker,
    flags: FeatureFlags,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState(uploadEnabled = flags.isEnabled(Flag.STATEMENTS_UPLOAD)))
    val state: StateFlow<HistoryUiState> = _state

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.list == null, offline = false) }
            try {
                val list = api.listStatements()
                _state.update { it.copy(loading = false, list = list) }
                list.uploads.filter { it.status == UploadStatus.PROCESSING }.forEach { watch(it.uploadId) }
            } catch (e: AppError) {
                _state.update { it.copy(loading = false, offline = it.list == null) }
            }
        }
    }

    private val watching = HashSet<String>()

    /** api.md 3.4: a `processing` upload → open its progress stream; reload the list when it ends. */
    private fun watch(uploadId: String) {
        if (!watching.add(uploadId)) return
        viewModelScope.launch {
            try {
                api.streamProgress(uploadId).collect { event ->
                    if (event is ImportEvent.Complete) repository.importCompleted(uploadId, event.result)
                }
            } catch (e: AppError) {
                // Dropped stream: the list is reloaded on the next resume anyway.
            } finally {
                watching.remove(uploadId)
            }
            load()
        }
    }

    fun askDelete(upload: Upload?) = _state.update { it.copy(confirmDelete = upload) }

    fun delete() {
        val upload = _state.value.confirmDelete ?: return
        viewModelScope.launch {
            _state.update { it.copy(deleting = true) }
            try {
                api.deleteStatement(upload.uploadId)
                repository.uploadDeleted(upload.uploadId)
                tracker.track(Events.STATEMENTS_UPLOAD_DELETED)
                _state.update { it.copy(deleting = false, confirmDelete = null, snackbar = "Загрузка удалена") }
                load()
            } catch (e: AppError) {
                _state.update {
                    it.copy(deleting = false, confirmDelete = null, snackbar = if (e is AppError.Offline) "Нет сети — загрузка не удалена" else "Не получилось удалить. Попробуйте ещё раз")
                }
            }
        }
    }

    fun consumeSnackbar() = _state.update { it.copy(snackbar = null) }
}

/** Profile → «История загрузок» (api.md 3.4, 3.5). */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onUpload: () -> Unit,
    onOpenUnread: (uploadId: String) -> Unit,
    vm: HistoryViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val d = PfTheme.dimens
    val c = PfTheme.colors
    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        PfPageHeader("История загрузок", onBack = onBack)
        val list = state.list
        when {
            state.loading -> Unit
            state.offline -> PfEmptyState(PfIcons.ALERT, "Нет сети", "Проверьте интернет и попробуйте ещё раз") {
                PfButton("Повторить", onClick = vm::load, variant = ButtonVariant.PRIMARY)
            }
            list == null || list.uploads.isEmpty() -> PfEmptyState(PfIcons.FILE_TEXT, "Выписок пока нет", "Загрузите выписку Т-Банка — разберём операции и покажем аналитику") {
                if (state.uploadEnabled) PfButton("Загрузить выписку", onClick = onUpload, variant = ButtonVariant.PRIMARY)
            }
            else -> LazyColumn(
                Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = d.space5, vertical = d.space2),
                verticalArrangement = Arrangement.spacedBy(d.space3),
            ) {
                list.summary?.let { s ->
                    item {
                        PfCard {
                            Text("${countWithNoun(s.uploadCount, "файл", "файла", "файлов")} · ${countWithNoun(s.operationCount, "операция", "операции", "операций")}", style = PfTheme.type.bodyStrong, color = c.text)
                            Text(RussianDates.dayRange(s.firstOperationAt.toLocalDate(), s.lastOperationAt.toLocalDate()), style = PfTheme.type.caption, color = c.textMuted)
                        }
                    }
                    s.gaps.firstOrNull()?.let { g ->
                        item {
                            PfNotice(
                                "Нет данных за ${RussianDates.dayRange(g.from.toLocalDate(), RussianDates.lastDayOf(g))} — загрузите выписку за эти дни",
                                tone = NoticeTone.WARNING,
                            )
                        }
                    }
                }
                items(list.uploads, key = { it.uploadId }) { u -> UploadRow(u, onDelete = { vm.askDelete(u) }, onOpenUnread = { onOpenUnread(u.uploadId) }) }
                if (state.uploadEnabled) {
                    item {
                        Spacer(Modifier.height(d.space2))
                        PfButton("Загрузить выписку", onClick = onUpload, block = true, icon = PfIcons.UPLOAD)
                    }
                }
            }
        }
        state.snackbar?.let { text ->
            androidx.compose.runtime.LaunchedEffect(text) {
                kotlinx.coroutines.delay(3000)
                vm.consumeSnackbar()
            }
            PfSnackbar(text, Modifier.padding(d.space4))
        }
    }
    state.confirmDelete?.let { u ->
        PfDialog(
            title = "Удалить загрузку?",
            description = "Удалим файл «${u.fileName}» и операции, которых нет в других загрузках. Ваши правки категорий у оставшихся операций сохранятся. Аналитика пересчитается.",
            confirmText = "Удалить",
            onConfirm = vm::delete,
            onDismiss = { vm.askDelete(null) },
            busy = state.deleting,
            busyText = "Удаляем…",
        )
    }
}

@Composable
private fun UploadRow(u: Upload, onDelete: () -> Unit, onOpenUnread: () -> Unit) {
    val period = if (u.firstOperationAt != null && u.lastOperationAt != null) {
        RussianDates.dayRange(u.firstOperationAt!!.toLocalDate(), u.lastOperationAt!!.toLocalDate())
    } else {
        null
    }
    PfCard(flush = true) {
        PfListRow(
            title = u.fileName,
            icon = PfIcons.FILE_TEXT,
            description = listOfNotNull(
                if (u.status == UploadStatus.PROCESSING) "Разбираем…" else "Загружено ${RussianDates.day(u.uploadedAt.toLocalDate(), LocalDate.now().year)}",
                period,
                countWithNoun(u.operationCount, "операция", "операции", "операций"),
            ).joinToString(" · "),
            trailing = { PfIconButton(PfIcons.TRASH, contentDescription = "Удалить загрузку ${u.fileName}", onClick = onDelete) },
        )
        if (u.unreadCount > 0) {
            PfListRow(
                title = "Не прочитали ${countWithNoun(u.unreadCount, "строку", "строки", "строк")}",
                icon = PfIcons.ALERT,
                onClick = onOpenUnread,
            )
        }
    }
}
