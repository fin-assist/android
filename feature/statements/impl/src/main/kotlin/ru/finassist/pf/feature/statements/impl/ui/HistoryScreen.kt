package ru.finassist.pf.feature.statements.impl.ui

import ru.finassist.pf.core.designsystem.theme.PfInsets
import androidx.compose.foundation.layout.Arrangement
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfDivider
import androidx.compose.ui.Alignment
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val watching = HashMap<String, Job>()

    /**
     * api.md 3.4: a `processing` upload → open its progress stream; reload the list once it reports the end.
     * A drop (network, 5xx, stream closed without `complete` / `error`) is re-opened with backoff; any other
     * error ends watching until the next resume. Streams live only while the screen is started ([pause]).
     */
    private fun watch(uploadId: String) {
        if (watching[uploadId]?.isActive == true) return
        watching[uploadId] = viewModelScope.launch {
            var backoff = 1_000L
            while (true) {
                var terminal = false
                try {
                    api.streamProgress(uploadId).collect { event ->
                        backoff = 1_000L
                        if (event is ImportEvent.Complete) repository.importCompleted(uploadId, event.result)
                        if (event !is ImportEvent.Progress) terminal = true
                    }
                } catch (e: AppError) {
                    if (e !is AppError.Offline && e !is AppError.Server) return@launch
                }
                if (terminal) break
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
            watching.remove(uploadId)
            load()
        }
    }

    fun pause() {
        watching.values.forEach { it.cancel() }
        watching.clear()
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
                _state.update { it.copy(deleting = false, confirmDelete = null, snackbar = "Загрузка удалена, аналитика пересчитана") }
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
    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose { vm.pause() }
    }
    HistoryContent(
        state = state,
        onBack = onBack,
        onUpload = onUpload,
        onOpenUnread = onOpenUnread,
        onRetry = vm::load,
        onAskDelete = vm::askDelete,
        onDelete = vm::delete,
        onSnackbarShown = vm::consumeSnackbar,
    )
}

/**
 * Stateless body of [HistoryScreen] (mockups `UploadHistory*`), the delete dialog and the snackbar included: the
 * uploads in one card, what deleting does, «Загрузить новую выписку» at the bottom.
 */
@Composable
internal fun HistoryContent(
    state: HistoryUiState,
    onBack: () -> Unit,
    onUpload: () -> Unit,
    onOpenUnread: (uploadId: String) -> Unit,
    onRetry: () -> Unit,
    onAskDelete: (Upload?) -> Unit,
    onDelete: () -> Unit,
    onSnackbarShown: () -> Unit,
) {
    val d = PfTheme.dimens
    val c = PfTheme.colors
    Box(Modifier.fillMaxSize().windowInsetsPadding(PfInsets.navigationBars).testTag(StatementsTags.HISTORY)) {
        Column(Modifier.fillMaxSize()) {
            PfPageHeader("История загрузок", onBack = onBack)
            val list = state.list
            when {
                state.loading -> Unit
                state.offline -> PfEmptyState(PfIcons.ALERT, "Нет сети", "Проверьте интернет и попробуйте ещё раз") {
                    PfButton("Повторить", onClick = onRetry, variant = ButtonVariant.PRIMARY)
                }
                list == null || list.uploads.isEmpty() -> Column(
                    Modifier.weight(1f).fillMaxWidth().padding(start = d.space5, end = d.space5, top = d.space4, bottom = d.space4).testTag(StatementsTags.HISTORY_EMPTY),
                    verticalArrangement = Arrangement.Center,
                ) {
                    PfEmptyState(PfIcons.FILE_TEXT, "Загрузок пока нет", "Загрузите выписку Т-Банка — разберём операции и посчитаем аналитику")
                    if (state.uploadEnabled) {
                        PfButton("Загрузить выписку", onClick = onUpload, variant = ButtonVariant.PRIMARY, block = true, modifier = Modifier.testTag(StatementsTags.HISTORY_UPLOAD))
                    }
                }
                else -> BoxWithConstraints(Modifier.weight(1f)) {
                    val viewport = maxHeight
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = viewport)
                            .padding(start = d.space5, end = d.space5, top = d.space4, bottom = d.space4),
                    ) {
                        list.summary?.gaps?.firstOrNull()?.let { g ->
                            PfNotice(
                                "Нет данных за ${RussianDates.dayRange(g.from.toLocalDate(), RussianDates.lastDayOf(g))} — загрузите выписку за эти дни",
                                tone = NoticeTone.WARNING,
                            )
                            Spacer(Modifier.height(d.space3))
                        }
                        PfCard(flush = true) {
                            list.uploads.forEachIndexed { i, u ->
                                UploadRow(u, divider = i < list.uploads.lastIndex, onDelete = { onAskDelete(u) }, onOpenUnread = { onOpenUnread(u.uploadId) })
                            }
                        }
                        Text(
                            "Удаление загрузки убирает только операции, которых нет в других загрузках",
                            style = PfTheme.type.hint, color = c.textMuted, modifier = Modifier.padding(top = d.space2),
                        )
                        if (state.uploadEnabled) {
                            Spacer(Modifier.weight(1f).heightIn(min = d.space6))
                            PfButton("Загрузить новую выписку", onClick = onUpload, variant = ButtonVariant.PRIMARY, block = true, modifier = Modifier.testTag(StatementsTags.HISTORY_UPLOAD))
                        }
                    }
                }
            }
        }
        state.snackbar?.let { text ->
            androidx.compose.runtime.LaunchedEffect(text) {
                kotlinx.coroutines.delay(3000)
                onSnackbarShown()
            }
            // Over the bottom button, 8 dp above it (mockup `UploadHistoryDeleted`).
            PfSnackbar(
                text,
                Modifier.align(Alignment.BottomCenter).padding(start = d.space5, end = d.space5, bottom = d.space4 + d.control + d.space2),
            )
        }
    }
    state.confirmDelete?.let { u ->
        PfDialog(
            title = "Удалить загрузку ${u.fileName}?",
            description = "Удалим операции, которых нет в других загрузках. Операции, которые есть и в других файлах, останутся вместе с вашими правками категорий. Аналитика пересчитается. Отменить нельзя",
            confirmText = "Удалить загрузку",
            onConfirm = onDelete,
            onDismiss = { onAskDelete(null) },
            busy = state.deleting,
            busyText = "Удаляем…",
        )
    }
}

/**
 * A row of the uploads card: the file, «1 апреля — 25 сентября · 1 086 операций», «Загружено 25 сентября 2026»,
 * a ghost delete button; unread lines get a link under it.
 */
@Composable
private fun UploadRow(u: Upload, divider: Boolean, onDelete: () -> Unit, onOpenUnread: () -> Unit) {
    val d = PfTheme.dimens
    val c = PfTheme.colors
    val period = if (u.firstOperationAt != null && u.lastOperationAt != null) {
        RussianDates.dayRange(u.firstOperationAt!!.toLocalDate(), u.lastOperationAt!!.toLocalDate())
    } else {
        null
    }
    Column(Modifier.testTag(StatementsTags.HISTORY_ROW)) {
        Row(Modifier.fillMaxWidth().padding(vertical = d.space3), horizontalArrangement = Arrangement.spacedBy(d.space2), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(u.fileName, style = PfTheme.type.bodyStrong, color = c.text)
                Text(
                    listOfNotNull(period, countWithNoun(u.operationCount, "операция", "операции", "операций")).joinToString(" · "),
                    style = PfTheme.type.caption.copy(fontFeatureSettings = "tnum"), color = c.textMuted,
                )
                Text(
                    if (u.status == UploadStatus.PROCESSING) "Разбираем…" else "Загружено ${RussianDates.day(u.uploadedAt.toLocalDate())}\u00A0${u.uploadedAt.year}",
                    style = PfTheme.type.caption, color = c.textMuted,
                )
                if (u.unreadCount > 0) {
                    PfLink(
                        "Не прочитали ${countWithNoun(u.unreadCount, "строку", "строки", "строк")}", onClick = onOpenUnread,
                        inline = true, modifier = Modifier.padding(top = d.space1).testTag(StatementsTags.HISTORY_UNREAD),
                    )
                }
            }
            PfIconButton(
                PfIcons.TRASH, contentDescription = "Удалить загрузку ${u.fileName}", onClick = onDelete, tint = c.accent,
                modifier = Modifier.testTag(StatementsTags.HISTORY_DELETE),
            )
        }
        if (divider) PfDivider()
    }
}
