package ru.finassist.pf.feature.statements.impl.ui

import ru.finassist.pf.core.designsystem.theme.PfInsets
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
)

@HiltViewModel
class UnreadLinesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val api: StatementsApi,
    private val tokenStore: TokenStore,
) : ViewModel() {
    private val route = savedState.toRoute<StatementsRoutes.UnreadLines>()
    private val _state = MutableStateFlow(UnreadUiState())
    val state: StateFlow<UnreadUiState> = _state

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = false) }
            try {
                val list = if (route.uploadId != null) {
                    api.listUnreadLines(route.uploadId!!)
                } else {
                    api.listUnreadLines(parseApiDateTime(route.from!!), parseApiDateTime(route.to!!))
                }
                _state.update { it.copy(loading = false, lines = list.lines, userId = tokenStore.current()?.userId) }
            } catch (e: AppError) {
                _state.update { it.copy(loading = false, error = true) }
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
    Column(Modifier.fillMaxSize().windowInsetsPadding(PfInsets.navigationBars)) {
        PfPageHeader("Непрочитанные строки", onBack = onBack)
        when {
            state.loading -> Unit
            state.error -> PfEmptyState(PfIcons.ALERT, "Не получилось загрузить", "Проверьте интернет и попробуйте ещё раз") {
                PfButton("Повторить", onClick = onRetry, variant = ButtonVariant.PRIMARY)
            }
            state.lines.isEmpty() -> PfEmptyState(PfIcons.CHECK_CIRCLE, "Все строки прочитаны")
            else -> {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = d.space5, vertical = d.space2),
                    verticalArrangement = Arrangement.spacedBy(d.space3),
                ) {
                    item {
                        Text(
                            "Эти строки файла не получилось разобрать — их операций нет в приложении. Содержимое строк мы не храним, поэтому показываем только номер, дату и причину",
                            style = PfTheme.type.body, color = PfTheme.colors.textMuted,
                        )
                    }
                    // By upload, not by file name: T-Bank names every export the same way.
                    state.lines.groupBy { it.uploadId }.forEach { (uploadId, lines) ->
                        val file = lines.first().fileName
                        item(key = uploadId) {
                            PfCard {
                                Text(file, style = PfTheme.type.caption, color = PfTheme.colors.textMuted)
                                lines.forEachIndexed { i, l ->
                                    PfDataRow(
                                        label = "Строка ${l.lineNumber}" + (l.date?.let { " · " + RussianDates.day(it.toLocalDate()) } ?: ""),
                                        value = l.reasonName,
                                        divider = i < lines.lastIndex,
                                    )
                                }
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = d.space5, vertical = d.space4)) {
                    PfButton(
                        if (copied) "Данные скопированы" else "Сообщить нам",
                        onClick = onReport,
                        variant = ButtonVariant.PRIMARY,
                        block = true,
                        icon = PfIcons.MAIL,
                    )
                    Text(
                        if (copied) "Почтового приложения нет. Отправьте скопированное на ${Support.EMAIL}"
                        else "Откроется письмо в поддержку с номерами строк — без содержимого. Ответим на вашу почту",
                        style = PfTheme.type.caption, color = PfTheme.colors.textMuted,
                        modifier = Modifier.padding(top = d.space2),
                    )
                }
            }
        }
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
