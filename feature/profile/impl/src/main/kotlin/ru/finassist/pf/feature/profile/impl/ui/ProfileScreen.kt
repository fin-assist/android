package ru.finassist.pf.feature.profile.impl.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.finassist.pf.core.api.model.Theme
import ru.finassist.pf.core.common.Support
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfDialog
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfListRow
import ru.finassist.pf.core.designsystem.components.PfOptionRow
import ru.finassist.pf.core.designsystem.components.PfSnackbar
import ru.finassist.pf.core.designsystem.components.PfTabHeader
import ru.finassist.pf.core.designsystem.components.RowTone
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.profile.impl.domain.ProfileTexts

class ProfileActions(
    val openHistory: () -> Unit,
    val openUpload: () -> Unit,
    val openChat: () -> Unit,
    val openConsent: () -> Unit,
    val openSecurity: () -> Unit,
    val openDeleteAccount: () -> Unit,
)

/** «Профиль» (mvp-scope «Профиль»): phone, statement, assistant, theme, security, support, sign-out, deletion. */
@Composable
fun ProfileScreen(actions: ProfileActions, vm: ProfileViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val d = PfTheme.dimens
    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose { }
    }
    Column(Modifier.fillMaxSize()) {
        PfTabHeader("Профиль")
        val p = state.profile
        when {
            state.loading -> Unit
            state.offline || p == null -> PfEmptyState(PfIcons.ALERT, "Нет сети", "Проверьте интернет и попробуйте ещё раз") {
                PfButton("Повторить", onClick = vm::load, variant = ButtonVariant.PRIMARY)
            }
            else -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = d.space5).padding(bottom = d.space8),
                verticalArrangement = Arrangement.spacedBy(d.space4),
            ) {
                PfCard(flush = true) {
                    PfListRow("Телефон", icon = PfIcons.PHONE, value = ProfileTexts.phone(p.phone), divider = false)
                }
                PfCard(flush = true) {
                    PfListRow(
                        "Выписка",
                        icon = PfIcons.FILE_TEXT,
                        description = if (state.statementsLoaded) ProfileTexts.statement(state.statements) else null,
                        onClick = if (state.statements == null && state.flags.upload) actions.openUpload else actions.openHistory,
                        divider = false,
                    )
                }
                if (state.flags.assistant) {
                    PfCard(flush = true) {
                        val limit = state.limit
                        PfListRow(
                            "Помощник",
                            icon = PfIcons.MESSAGE,
                            value = limit?.let { if (it.remaining == it.dailyMax) "${it.dailyMax} вопросов в день" else "осталось ${it.remaining} из ${it.dailyMax}" },
                            onClick = actions.openChat,
                        )
                        val granted = p.assistantConsent.granted && !p.assistantConsent.needsRenewal
                        PfListRow(
                            "Передавать данные помощнику",
                            description = "Без согласия помощник не отвечает. История ответов сохранится",
                            switchChecked = granted,
                            onSwitch = { on -> if (state.consentBusy) Unit else if (on) actions.openConsent() else vm.revokeConsent() },
                            divider = false,
                        )
                    }
                }
                PfCard(flush = true) {
                    PfListRow("Тема", icon = if (state.theme == Theme.DARK) PfIcons.MOON else PfIcons.SUN, value = ProfileTexts.theme(state.theme), onClick = { vm.openThemeSheet(true) })
                    PfListRow("Код-пароль и биометрия", icon = PfIcons.LOCK, onClick = actions.openSecurity)
                    PfListRow("Написать в поддержку", icon = PfIcons.LIFE_BUOY, onClick = { writeSupport(context, p.userId) }, divider = false)
                }
                PfCard(flush = true) {
                    PfListRow("Выйти из аккаунта", icon = PfIcons.LOG_OUT, tone = RowTone.ACCENT, onClick = { vm.askLogout(true) }, divider = state.flags.deleteAccount)
                    if (state.flags.deleteAccount) {
                        PfListRow("Удалить аккаунт", icon = PfIcons.TRASH, tone = RowTone.ACCENT, onClick = actions.openDeleteAccount, divider = false)
                    }
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
    if (state.themeSheet) {
        PfBottomSheet("Тема", onDismiss = { vm.openThemeSheet(false) }) {
            Theme.entries.forEach { t -> PfOptionRow(ProfileTexts.theme(t), selected = t == state.theme, onClick = { vm.setTheme(t) }) }
        }
    }
    if (state.askLogout) {
        PfDialog(
            title = "Выйти из аккаунта?",
            description = "Чтобы войти снова, понадобится номер телефона и звонок. Данные останутся в аккаунте.",
            confirmText = "Выйти",
            onConfirm = vm::logout,
            onDismiss = { vm.askLogout(false) },
            busy = state.loggingOut,
            busyText = "Выходим…",
        )
    }
}

private fun writeSupport(context: Context, userId: String) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(Support.EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "Понятные финансы — вопрос")
        putExtra(Intent.EXTRA_TEXT, "\n\n—\nID пользователя: $userId")
    }
    runCatching { context.startActivity(intent) }
}
