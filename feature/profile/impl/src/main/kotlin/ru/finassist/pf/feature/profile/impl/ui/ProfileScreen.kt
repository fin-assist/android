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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
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
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfListRow
import ru.finassist.pf.core.designsystem.components.PfOptionRow
import ru.finassist.pf.core.designsystem.components.PfSectionTitle
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
    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose { }
    }
    ProfileContent(
        state = state, actions = actions,
        onRetry = vm::load, onRevokeConsent = vm::revokeConsent, onThemeSheet = vm::openThemeSheet, onTheme = vm::setTheme,
        onSupport = { userId -> writeSupport(context, userId) }, onAskLogout = vm::askLogout, onLogout = vm::logout,
        onSnackbarShown = vm::consumeSnackbar,
        versionName = remember { appVersion(context) },
    )
}

private fun appVersion(context: Context): String? =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PfTheme.dimens.space2)) {
        PfSectionTitle(title)
        content()
    }
}

/** The profile drawn from a ready state (design-check snapshots render it without a ViewModel). */
@Composable
internal fun ProfileContent(
    state: ProfileUiState,
    actions: ProfileActions,
    onRetry: () -> Unit,
    onRevokeConsent: () -> Unit,
    onThemeSheet: (Boolean) -> Unit,
    onTheme: (Theme) -> Unit,
    onSupport: (userId: String) -> Unit,
    onAskLogout: (Boolean) -> Unit,
    onLogout: () -> Unit,
    onSnackbarShown: () -> Unit,
    versionName: String? = null,
) {
    val d = PfTheme.dimens
    Column(Modifier.fillMaxSize()) {
        PfTabHeader("Профиль")
        val p = state.profile
        when {
            state.loading -> Unit
            state.offline || p == null -> PfEmptyState(PfIcons.ALERT, "Нет сети", "Проверьте интернет и попробуйте ещё раз") {
                PfButton("Повторить", onClick = onRetry, variant = ButtonVariant.PRIMARY)
            }
            else -> Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = d.space5).padding(top = d.space4, bottom = d.space6),
                verticalArrangement = Arrangement.spacedBy(d.space6),
            ) {
                PfCard {
                    Text("Номер телефона", style = PfTheme.type.caption, color = PfTheme.colors.textMuted)
                    Text(ProfileTexts.phone(p.phone), style = PfTheme.type.title2, color = PfTheme.colors.text, maxLines = 1, modifier = Modifier.padding(top = d.space1))
                }
                Section("Источник данных") {
                    PfCard(flush = true) {
                        PfListRow(
                            "Выписка",
                            icon = PfIcons.LANDMARK,
                            description = if (state.statementsLoaded) ProfileTexts.statement(state.statements) else null,
                            onClick = if (state.statements == null && state.flags.upload) actions.openUpload else actions.openHistory,
                            divider = false,
                        )
                    }
                }
                if (state.flags.assistant) {
                    Section("Помощник") {
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
                                icon = PfIcons.SEND,
                                description = "Без этого помощник не отвечает, прошлые ответы остаются",
                                switchChecked = granted,
                                onSwitch = { on -> if (state.consentBusy) Unit else if (on) actions.openConsent() else onRevokeConsent() },
                                divider = false,
                            )
                        }
                    }
                }
                Section("Настройки") {
                    PfCard(flush = true) {
                        PfListRow("Код-пароль и биометрия", icon = PfIcons.FINGERPRINT, value = ProfileTexts.biometric(state.biometric), onClick = actions.openSecurity)
                        PfListRow("Тема", icon = PfIcons.MOON, value = ProfileTexts.theme(state.theme), onClick = { onThemeSheet(true) }, divider = false)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(d.space2)) {
                    PfCard(flush = true) {
                        PfListRow("Написать в поддержку", icon = PfIcons.LIFE_BUOY, onClick = { onSupport(p.userId) })
                        PfListRow("Выйти", icon = PfIcons.LOG_OUT, tone = RowTone.ACCENT, onClick = { onAskLogout(true) }, divider = false)
                    }
                    if (state.flags.deleteAccount) PfLink("Удалить аккаунт и все данные", onClick = actions.openDeleteAccount)
                    Text(
                        ProfileTexts.footer(versionName),
                        style = PfTheme.type.hint, color = PfTheme.colors.textMuted, textAlign = TextAlign.Center,
                    )
                }
            }
        }
        state.snackbar?.let { text ->
            LaunchedEffect(text) {
                delay(3000)
                onSnackbarShown()
            }
            PfSnackbar(text, Modifier.padding(d.space4))
        }
    }
    if (state.themeSheet) {
        PfBottomSheet("Тема", onDismiss = { onThemeSheet(false) }) {
            Theme.entries.forEach { t -> PfOptionRow(ProfileTexts.theme(t), selected = t == state.theme, onClick = { onTheme(t) }) }
        }
    }
    if (state.askLogout) {
        PfDialog(
            title = "Выйти из аккаунта?",
            description = "Чтобы войти снова, понадобится номер телефона и звонок. Данные останутся в аккаунте.",
            confirmText = "Выйти",
            onConfirm = onLogout,
            onDismiss = { onAskLogout(false) },
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
