package ru.finassist.pf.feature.statements.impl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.Divider
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.network.dto.UnreadLineDto
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import ru.finassist.pf.feature.statements.impl.data.StatementsRepositoryImpl
import java.time.OffsetDateTime
import javax.inject.Inject

@HiltViewModel
class UnreadLinesViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: StatementsRepositoryImpl,
    private val session: SessionRepository,
) : ViewModel() {
    val route: StatementsRoutes.UnreadLines = savedState.toRoute()

    data class UiState(val loading: Boolean = true, val lines: List<UnreadLineDto> = emptyList(), val error: AppError? = null)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { load() }

    fun load() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        try {
            val lines = repo.unreadLines(route.uploadId, route.from, route.to)
            _state.update { it.copy(loading = false, lines = lines) }
        } catch (e: AppError) { _state.update { it.copy(loading = false, error = e) } }
    }

    /** Body of the «Сообщить нам» mail: ids and line numbers only — line contents are never stored (api.md 3.6). */
    fun mailBody(): String = buildString {
        appendLine("user_id: ${session.userId ?: "—"}")
        _state.value.lines.groupBy { it.uploadId }.forEach { (uploadId, lines) ->
            appendLine("upload_id: $uploadId")
            appendLine("file: ${lines.first().fileName}")
            lines.forEach { appendLine("  строка ${it.lineNumber}: ${it.reason}") }
        }
    }
}

/** Sheet «Непрочитанные строки» (mockup ImportResultUnread). */
@Composable
fun UnreadLinesSheet(state: UnreadLinesViewModel.UiState, vm: UnreadLinesViewModel, onDismiss: () -> Unit) {
    val c = PfTheme.colors
    val context = LocalContext.current
    val fileNames = state.lines.map { it.fileName }.distinct()
    PfBottomSheet(
        title = "Непрочитанные строки",
        onDismiss = onDismiss,
        footer = {
            Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                PfButton("Сообщить нам", onClick = { openSupportMail(context, "Непрочитанные строки в выписке", vm.mailBody()) }, variant = ButtonVariant.Primary, block = true, enabled = state.lines.isNotEmpty())
                Text("Откроется письмо в поддержку — номер загрузки и этот список уже в нём. Ответим на вашу почту", style = PfTheme.type.hint, color = c.textMuted)
            }
        },
    ) {
        Spacer(Modifier.height(PfSpace.s2))
        when {
            state.error != null -> Notice(if (state.error is AppError.Offline) "Нет сети. Проверьте интернет и повторите" else "Не получилось загрузить список", tone = NoticeTone.Warning, action = { PfLink("Повторить", onClick = vm::load, inline = true) })
            state.loading -> Text("Загружаем…", style = PfTheme.type.body, color = c.textMuted)
            state.lines.isEmpty() -> Text("Все строки прочитаны", style = PfTheme.type.body, color = c.textMuted)
            else -> {
                val intro = if (fileNames.size == 1) "В файле ${fileNames[0]} эти строки не прочитали — их нет в суммах. Остальные операции загружены."
                else "Эти строки не прочитали — их нет в суммах. Остальные операции загружены."
                Text(intro, style = PfTheme.type.body, color = c.textMuted)
                Spacer(Modifier.height(PfSpace.s3))
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    state.lines.forEachIndexed { i, line ->
                        val date = line.date?.let { runCatching { OffsetDateTime.parse(it).toLocalDate() }.getOrNull() }
                        Column(Modifier.fillMaxWidth().padding(vertical = PfSpace.s2)) {
                            Text("Строка ${line.lineNumber} · ${date?.let { RussianDates.dayMonth(it) } ?: "дата не прочитана"}" + if (fileNames.size > 1) " · ${line.fileName}" else "", style = PfTheme.type.captionStrong, color = c.text)
                            Text(line.reasonName, style = PfTheme.type.caption, color = c.textMuted)
                        }
                        if (i < state.lines.lastIndex) Divider()
                    }
                }
            }
        }
    }
}
