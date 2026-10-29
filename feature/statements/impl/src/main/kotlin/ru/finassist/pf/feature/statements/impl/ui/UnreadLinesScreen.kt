package ru.finassist.pf.feature.statements.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.error.RequestRetryGate
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.designsystem.components.ErrorMessage
import ru.finassist.pf.core.designsystem.theme.PfInsets
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import ru.finassist.pf.feature.statements.impl.domain.UnreadTexts
import ru.finassist.pf.core.designsystem.components.PfDivider
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.model.UnreadLine
import ru.finassist.pf.core.common.Support
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.parseApiDateTime
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfDataRow
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import javax.inject.Inject

data class UnreadUiState(
    val loading: Boolean = true,
    val error: Boolean = false,
    val lines: List<UnreadLine> = emptyList(),
    val userId: String? = null,
    /** False when the upload read no operation (route): the intro drops «остальные операции загружены». */
    val othersLoaded: Boolean = true,
    val errorPresentation: ErrorPresentation? = null,
    val requestRetryable: Boolean? = null,
    val retryReady: Boolean = true,
)

@HiltViewModel
class UnreadLinesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val api: StatementsApi,
    private val tokenStore: TokenStore,
    private val elapsedClock: ElapsedClock,
) : ViewModel() {
    private val route = savedState.toRoute<StatementsRoutes.UnreadLines>()
    private val _state = MutableStateFlow(UnreadUiState(othersLoaded = route.othersLoaded))
    private val retryGate = RequestRetryGate(elapsedClock)
    init { viewModelScope.launch { retryGate.readiness.collect { ready -> _state.update { it.copy(retryReady = ready) } } } }
    val state: StateFlow<UnreadUiState> = _state

    init {
        load()
    }

    fun load() {
        if (!retryGate.isReady()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false, errorPresentation = null, requestRetryable = null) }
            try {
                val list = if (route.uploadId != null) {
                    api.listUnreadLines(route.uploadId!!)
                } else {
                    api.listUnreadLines(parseApiDateTime(route.from!!), parseApiDateTime(route.to!!))
                }
                _state.update { it.copy(loading = false, lines = list.lines, userId = tokenStore.current()?.userId) }
            } catch (e: AppError) {
                retryGate.record(e)
                _state.update { it.copy(loading = false, error = true, errorPresentation = e.presentation.takeUnless { e is AppError.Offline }, requestRetryable = e.requestRetryable) }
            }
        }
    }
}

/**
 * «Непрочитанные строки»: line number, date (if read) and reason — never the content (api.md 3.6).
 * «Сообщить нам» opens a pre-filled e-mail in the user's mail app; without one — copy the data.
 */
@Composable
fun UnreadLinesScreen(onBack: () -> Unit, vm: UnreadLinesViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    UnreadLinesContent(
        state = state,
        copied = copied,
        onBack = onBack,
        onRetry = vm::load,
        onReport = { if (!sendReport(context, state)) copied = copyReport(context, state) },
    )
}

/** Stateless body of [UnreadLinesScreen]; [copied] — no mail app, the report went to the clipboard. */
@Composable
internal fun UnreadLinesContent(
    state: UnreadUiState,
    copied: Boolean,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onReport: () -> Unit,
) {
    val d = PfTheme.dimens
    Column(Modifier.fillMaxSize().windowInsetsPadding(PfInsets.navigationBars).testTag(StatementsTags.UNREAD)) {
        PfPageHeader("Непрочитанные строки", onBack = onBack)
        state.errorPresentation?.let {
            ErrorMessage(it, "Не получилось загрузить. Попробуйте ещё раз", modifier = Modifier.padding(d.space4),
                onRetry = if (!state.retryReady) null else onRetry)
        }
        when {
            state.loading -> Unit
            state.errorPresentation != null && state.lines.isEmpty() -> Unit
            state.error && state.lines.isEmpty() -> PfEmptyState(PfIcons.ALERT, "Не получилось загрузить", "Проверьте интернет и попробуйте ещё раз") {
                PfButton("Повторить", onClick = onRetry, variant = ButtonVariant.PRIMARY)
            }
            state.lines.isEmpty() -> PfEmptyState(PfIcons.CHECK_CIRCLE, "Все строки прочитаны")
            else -> {
                // Mockup `UnreadLines` (a screen since FIN-59): the intro, the lines as two-line rows
                // without a card, «Сообщить нам» and what the letter holds.
                val byUpload = state.lines.groupBy { it.uploadId }
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = d.space5, end = d.space5, top = d.space2, bottom = d.space4),
                ) {
                    item(key = "intro") {
                        Text(
                            UnreadTexts.intro(byUpload.values.map { it.first().fileName }, state.othersLoaded),
                            style = PfTheme.type.body, color = PfTheme.colors.textMuted,
                            modifier = Modifier.padding(top = d.space1, bottom = d.space2),
                        )
                    }
                    // By upload, not by file name: T-Bank names every export the same way.
                    byUpload.entries.forEachIndexed { index, (uploadId, lines) ->
                        if (byUpload.size > 1) {
                            item(key = "file-$uploadId") {
                                Text(
                                    lines.first().fileName, style = PfTheme.type.caption, color = PfTheme.colors.textMuted,
                                    modifier = Modifier.padding(top = if (index == 0) 0.dp else d.space4),
                                )
                            }
                        }
                        // Compact OFX records can share both physical line and reason; their occurrence is local.
                        lines.forEachIndexed { i, l ->
                            item(key = "line:$uploadId:$i") {
                                UnreadLineRow(UnreadTexts.line(l.lineNumber, l.date?.toLocalDate()), l.reasonName, divider = i < lines.lastIndex)
                            }
                        }
                    }
                    item(key = "report") {
                        Column(Modifier.padding(top = d.space3)) {
                            PfButton(
                                if (copied) "Данные скопированы" else "Сообщить нам",
                                onClick = onReport,
                                variant = ButtonVariant.SECONDARY,
                                block = true,
                                icon = PfIcons.SEND,
                                modifier = Modifier.testTag(StatementsTags.UNREAD_REPORT),
                            )
                            Text(
                                if (copied) "Почтового приложения нет. Отправьте скопированное на ${Support.EMAIL}"
                                else "Откроется письмо в поддержку — номер загрузки и этот список уже в нём. Ответим на вашу почту",
                                style = PfTheme.type.hint, color = PfTheme.colors.textMuted,
                                modifier = Modifier.padding(top = d.space2),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** «Строка 214 · 3 сентября» over the reason, 12 dp padding, a line under all but the last of a file. */
@Composable
private fun UnreadLineRow(title: String, reason: String, divider: Boolean) {
    Column {
        Column(Modifier.fillMaxWidth().padding(vertical = PfTheme.dimens.space3)) {
            Text(title, style = PfTheme.type.body.copy(fontFeatureSettings = "tnum"), color = PfTheme.colors.text)
            Text(reason, style = PfTheme.type.caption, color = PfTheme.colors.textMuted)
        }
        if (divider) PfDivider()
    }
}

private fun reportBody(state: UnreadUiState): String = buildString {
    appendLine("Не прочитались строки выписки.")
    appendLine()
    appendLine("ID пользователя: ${state.userId ?: "—"}")
    state.lines.groupBy { it.uploadId to it.fileName }.forEach { (key, lines) ->
        appendLine("Загрузка: ${key.first}")
        appendLine("Файл: ${key.second}")
        lines.forEach { appendLine("Строка ${it.lineNumber}: ${it.reason.code}") }
        appendLine()
    }
}

/** Returns false when there is no mail app. */
private fun sendReport(context: Context, state: UnreadUiState): Boolean {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(Support.EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "Непрочитанные строки выписки")
        putExtra(Intent.EXTRA_TEXT, reportBody(state))
    }
    return runCatching { context.startActivity(intent) }.isSuccess
}

private fun copyReport(context: Context, state: UnreadUiState): Boolean {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Данные для поддержки", reportBody(state)))
    return true
}
