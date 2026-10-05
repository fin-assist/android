package ru.finassist.pf.feature.profile.impl.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.support.Support
import ru.finassist.pf.core.designsystem.components.ListRow
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.OptionList
import ru.finassist.pf.core.designsystem.components.OptionRow
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfDialog
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfSwitch
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.components.SectionTitle
import ru.finassist.pf.core.designsystem.components.TabHeader
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.profile.api.AppTheme


/** «Профиль» tab (mockups Profile / ProfileThemeSheet / ProfileNoData / DeleteAccount*). */
@Composable
fun ProfileScreen(
    state: ProfileViewModel.UiState,
    onHistory: () -> Unit,
    onChat: () -> Unit,
    onAssistantConsent: (Boolean) -> Unit,
    onSecurity: () -> Unit,
    onThemeSheet: (Boolean) -> Unit,
    onTheme: (AppTheme) -> Unit,
    onLogout: () -> Unit,
    onDelete: () -> Unit,
    onDeleteDismiss: () -> Unit,
    onDeleteConfirm: () -> Unit,
    onDebug: (() -> Unit)? = null,
) {
    val c = PfTheme.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(c.bg)) {
        TabHeader(title = "Профиль")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = PfSpace.s2), verticalArrangement = Arrangement.spacedBy(PfSpace.s6)) {
            PfCard {
                Text("Номер телефона", style = PfTheme.type.caption, color = c.textMuted)
                Text(state.profile?.phone?.let(::formatPhone) ?: "…", style = PfTheme.type.title2, color = c.text)
            }
            if (state.loadError is AppError.Offline) Notice("Нет сети — показываем то, что есть", tone = NoticeTone.Info)

            Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                SectionTitle("Источник данных")
                PfCard(flush = true) { ListRow(icon = "landmark", title = "Выписка", description = state.statementText, onClick = onHistory, divider = false) }
            }
            if (state.assistantEnabled) Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                SectionTitle("Помощник")
                PfCard(flush = true) {
                    ListRow(icon = "message", title = "Помощник", value = state.assistantText, onClick = onChat)
                    ListRow(
                        icon = "send", title = "Передавать данные помощнику", description = "Без этого помощник не отвечает, прошлые ответы остаются",
                        trailing = { PfSwitch(checked = state.assistantConsent, onCheckedChange = { if (it) onChat() else onAssistantConsent(false) }) }, divider = false,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                SectionTitle("Настройки")
                PfCard(flush = true) {
                    ListRow(icon = "fingerprint", title = "Код-пароль и биометрия", value = if (state.biometricEnabled) "Биометрия включена" else "Код-пароль", onClick = onSecurity)
                    ListRow(icon = "moon", title = "Тема", value = state.theme.label(), onClick = { onThemeSheet(true) }, divider = false)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                PfCard(flush = true) {
                    ListRow(icon = "life-buoy", title = "Написать в поддержку", onClick = {
                        context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Support.EMAIL}")).apply { putExtra(Intent.EXTRA_SUBJECT, "Понятные финансы · ${state.profile?.userId ?: ""}") })
                    })
                    if (onDebug != null) ListRow(icon = "sliders", title = "Флаги (mock)", description = "Переключатели функций для ручной проверки", onClick = onDebug)
                    ListRow(icon = "log-out", title = "Выйти", accentTitle = true, onClick = onLogout, divider = false)
                }
                if (state.deleteEnabled) PfLink("Удалить аккаунт и все данные", onClick = onDelete)
                Text("Версия 0.1 · Данные хранятся в России", style = PfTheme.type.hint, color = c.textMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(PfSpace.s4))
        }
    }
    if (state.showThemeSheet) {
        PfBottomSheet(title = "Тема", onDismiss = { onThemeSheet(false) }) {
            OptionList(label = "Тема") {
                OptionRow("Как в системе", selected = state.theme == AppTheme.System, onClick = { onTheme(AppTheme.System) }, icon = "smartphone")
                OptionRow("Светлая", selected = state.theme == AppTheme.Light, onClick = { onTheme(AppTheme.Light) }, icon = "sun")
                OptionRow("Тёмная", selected = state.theme == AppTheme.Dark, onClick = { onTheme(AppTheme.Dark) }, icon = "moon", divider = false)
            }
        }
    }
    if (state.showDeleteDialog) {
        PfDialog(
            title = "Удалить аккаунт?",
            description = "Удалим аккаунт, выписки вместе с файлами, операции и ваши правки категорий, историю вопросов помощнику. Восстановить их будет нельзя.",
            onDismiss = onDeleteDismiss,
            confirmText = "Удалить навсегда",
            onConfirm = onDeleteConfirm,
            busy = state.deleting,
            busyText = "Удаляем…",
            extra = {
                Text("Копию данных можно запросить в поддержке до удаления.", style = PfTheme.type.caption, color = c.textMuted)
                if (state.deleteError != null) {
                    Spacer(Modifier.height(PfSpace.s3))
                    Notice(
                        if (state.deleteError is AppError.Offline) "Нет сети — удалить аккаунт не получилось. Проверьте интернет и повторите" else "Не получилось удалить аккаунт. Повторите позже",
                        tone = NoticeTone.Warning, alert = true,
                    )
                }
            },
        )
    }
}

private fun AppTheme.label() = when (this) { AppTheme.System -> "Как в системе"; AppTheme.Light -> "Светлая"; AppTheme.Dark -> "Тёмная" }

/** «+79161234567» → «+7 916 123-45-67». */
fun formatPhone(e164: String): String {
    val d = e164.filter { it.isDigit() }
    if (d.length != 11) return e164
    return "+${d[0]} ${d.substring(1, 4)} ${d.substring(4, 7)}-${d.substring(7, 9)}-${d.substring(9, 11)}"
}
