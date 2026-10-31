package ru.finassist.pf.feature.statements.impl.ui

import ru.finassist.pf.core.designsystem.components.ConnectionFailureState
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.designsystem.components.ErrorMessage
import ru.finassist.pf.feature.statements.impl.domain.uploadPeriodLabel
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.unit.dp
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
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.SseReopenState
import ru.finassist.pf.core.api.reopeningStream
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
    val errorPresentation: ErrorPresentation? = null,
    val requestRetryable: Boolean? = null,
    val retryReady: Boolean = true,
    val resultErrorPresentation: ErrorPresentation? = null,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val api: StatementsApi,
    private val repository: StatementsRepositoryImpl,
    private val tracker: Tracker,
    flags: FeatureFlags,
    private val elapsedClock: ElapsedClock,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState(uploadEnabled = flags.isEnabled(Flag.STATEMENTS_UPLOAD)))
    private val retryGate = RequestRetryGate(elapsedClock)
    init { viewModelScope.launch { retryGate.readiness.collect { ready -> _state.update { it.copy(retryReady = ready) } } } }
    val state: StateFlow<HistoryUiState> = _state
    private var loadGeneration = 0L

    /** Only an observed data change ends the previous list-request context; ordinary retry/resume does not. */
    private fun listDataChanged() {
        loadGeneration++ // invalidate pending old reads before any local event delivery or another suspension
        retryGate.clear()
        _state.update { it.copy(errorPresentation = null, requestRetryable = null, retryReady = true) }
    }

    fun load() {
        if (!retryGate.isReady()) return
        val generation = ++loadGeneration
        viewModelScope.launch {
            if (generation != loadGeneration) return@launch
            _state.update { it.copy(loading = it.list == null, offline = false, errorPresentation = null, requestRetryable = null) }
            try {
                val list = api.listStatements()
                if (generation != loadGeneration) return@launch
                _state.update { it.copy(loading = false, list = list) }
                list.uploads.filter { it.status == UploadStatus.PROCESSING }.forEach {
                    if (generation == loadGeneration) watch(it.uploadId)
                }
            } catch (e: AppError) {
                if (generation != loadGeneration) return@launch
                retryGate.record(e)
                _state.update { it.copy(loading = false, offline = it.list == null, errorPresentation = if (e is AppError.Offline && it.list == null) null else e.presentation, requestRetryable = e.requestRetryable) }
            }
        }
    }

    /** Re-open state of each upload's progress stream for the life of the screen ([SseReopenState]). */
    private val reopenStates = HashMap<String, SseReopenState>()

    private val watching = HashMap<String, Job>()
    private val deleteGates = mutableMapOf<String, RequestRetryGate>()

    /**
     * api.md 3.4: a `processing` upload → open its progress stream; reload the list once it reports the end.
     * A drop (network, 5xx, stream closed without `complete` / `error`) is re-opened by [reopeningStream]; any other
     * error ends watching until the next resume. Streams live only while the screen is started ([pause]).
     */
    private fun watch(uploadId: String) {
        if (watching[uploadId]?.isActive == true) return
        watching[uploadId] = viewModelScope.launch {
            try {
                // Opening it again after the screen came back is a re-open: a 429 is waited for (api.md «SSE»).
                reopeningStream(
                    isTerminal = { it !is ImportEvent.Progress },
                    state = reopenStates.getOrPut(uploadId) { SseReopenState() },
                    elapsedClock = elapsedClock,
                    recoverOnceAfter = { ServiceGate.recoveryDeadline(it, elapsedClock) },
                ) { api.streamProgress(uploadId) }
                    .collect { event -> when (event) {
                        is ImportEvent.Complete -> {
                            listDataChanged()
                            repository.importCompleted(uploadId, event.result)
                        }
                        is ImportEvent.Error -> {
                            listDataChanged()
                            if (event.code != ErrorCodes.CANCELLED) _state.update { it.copy(resultErrorPresentation = event.presentation) }
                        }
                        is ImportEvent.Progress -> Unit
                    } }
            } catch (e: AppError) {
                _state.update { it.copy(errorPresentation = e.presentation.takeUnless { e is AppError.Offline }) }
                return@launch
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
        val deleteGate = deleteGates.getOrPut(upload.uploadId) { RequestRetryGate(elapsedClock) }
        if (!deleteGate.isReady()) return
        viewModelScope.launch {
            _state.update { it.copy(deleting = true, errorPresentation = null, snackbar = null) }
            try {
                api.deleteStatement(upload.uploadId)
                listDataChanged()
                repository.uploadDeleted(upload.uploadId)
                tracker.track(Events.STATEMENTS_UPLOAD_DELETED)
                _state.update { it.copy(deleting = false, confirmDelete = null, snackbar = "Загрузка удалена, аналитика пересчитана") }
                load()
            } catch (e: AppError) {
                deleteGate.record(e)
                _state.update {
                    it.copy(deleting = false, confirmDelete = null, errorPresentation = e.presentation, snackbar = if (e is AppError.Offline) "Загрузка не удалена" else "Не получилось удалить. Попробуйте ещё раз")
                }
            }
        }
    }

    fun consumeSnackbar() = _state.update { it.copy(snackbar = null, errorPresentation = null) }
}

/** Profile → «История загрузок» (api.md 3.4, 3.5). */
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onUpload: () -> Unit,
    onOpenUnread: (uploadId: String, othersLoaded: Boolean) -> Unit,
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
    onOpenUnread: (uploadId: String, othersLoaded: Boolean) -> Unit,
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
            state.resultErrorPresentation?.let {
                ErrorMessage(it, "Не получилось обработать загрузку. Можно выбрать файл снова",
                    modifier = Modifier.padding(d.space4))
            }
            state.errorPresentation?.let { presentation ->
                ErrorMessage(presentation, state.snackbar ?: "Не получилось загрузить историю",
                    modifier = Modifier.padding(d.space4), onRetry = if (!state.retryReady || state.snackbar != null) null else onRetry)
            }
            when {
                state.loading -> Unit
                state.errorPresentation != null && list == null -> Unit
                state.offline -> ConnectionFailureState() {
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
                                UploadRow(u, divider = i < list.uploads.lastIndex, onDelete = { onAskDelete(u) }, onOpenUnread = { onOpenUnread(u.uploadId, u.operationCount > 0) })
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
        state.snackbar?.takeIf { state.errorPresentation == null }?.let { text ->
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
 * a ghost delete button; unread lines get a link under it, lines read by a later upload — «Строки дочитаны в загрузке
 * от 12 октября» (FIN-79).
 */
@Composable
private fun UploadRow(u: Upload, divider: Boolean, onDelete: () -> Unit, onOpenUnread: () -> Unit) {
    val d = PfTheme.dimens
    val c = PfTheme.colors
    val period = uploadPeriodLabel(u)
    Column(Modifier.testTag(StatementsTags.HISTORY_ROW)) {
        // `.pf-card-flush { padding: 0 16px }`: the row and its divider keep 16 dp from the card's frame.
        Row(Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space3), horizontalArrangement = Arrangement.spacedBy(d.space2), verticalAlignment = Alignment.CenterVertically) {
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
                // Lines of this upload that a later upload has read (FIN-79): no longer in the link's count.
                if (u.resolvedUnreadCount > 0 && u.resolvedByUploadedAt != null) {
                    Text(
                        "Строки дочитаны в загрузке от ${RussianDates.day(u.resolvedByUploadedAt!!.toLocalDate())}",
                        style = PfTheme.type.caption, color = c.textMuted,
                        modifier = Modifier.testTag(StatementsTags.HISTORY_RESOLVED),
                    )
                }
                if (u.unreadCount > 0) {
                    PfLink(
                        "Не прочитали ${countWithNoun(u.unreadCount, "строку", "строки", "строк")}", onClick = onOpenUnread,
                        inline = true, modifier = Modifier.padding(top = d.space1).testTag(StatementsTags.HISTORY_UNREAD),
                    )
                }
            }
            // Mockup: `Pf.Button variant="ghost" icon="trash"` — icon 20, `padding: 0 16px` and a 1 dp frame, so the
            // button is 54 dp wide and the icon sits 17 dp from the row's content edge. The width overrides the
            // icon button's 48 dp; the icon stays centred.
            PfIconButton(
                PfIcons.TRASH, contentDescription = "Удалить загрузку ${u.fileName}", onClick = onDelete, tint = c.accent,
                iconSize = d.iconMd,
                modifier = Modifier.width(54.dp).testTag(StatementsTags.HISTORY_DELETE),
            )
        }
        if (divider) PfDivider(Modifier.padding(horizontal = d.space4))
    }
}
