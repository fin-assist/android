package ru.finassist.pf.feature.applock.impl.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.finassist.pf.core.designsystem.components.BiometricKind
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.HeroTone
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfDialog
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfListRow
import ru.finassist.pf.core.designsystem.components.PfMark
import ru.finassist.pf.core.designsystem.components.PfNumPad
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.components.PfPasscodeDots
import ru.finassist.pf.core.designsystem.components.PfStatusHero
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfInsets
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.applock.api.BiometricAvailability
import ru.finassist.pf.feature.applock.api.PASSCODE_LENGTH

/**
 * Common layout of all passcode screens: optional header, title + subtitle, dots in the middle, keypad at
 * the bottom with a footer link under it. Fills the screen; no scrolling (the keypad must stay put).
 */
@Composable
private fun PasscodePad(
    title: String,
    subtitle: String?,
    entered: Int,
    error: String?,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    header: (@Composable () -> Unit)? = null,
    mark: Boolean = false,
    biometric: BiometricKind = BiometricKind.NONE,
    onBiometric: () -> Unit = {},
    footer: (@Composable () -> Unit)? = null,
) {
    val d = PfTheme.dimens
    Column(
        modifier
            .fillMaxSize()
            .background(PfTheme.colors.bg)
            .then(if (header == null) Modifier.windowInsetsPadding(PfInsets.statusBars) else Modifier)
            .windowInsetsPadding(PfInsets.navigationBars),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // PfPageHeader pads the status bar itself.
        if (header != null) header() else Spacer(Modifier.height(d.space6))
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = d.space5),
            horizontalAlignment = Alignment.CenterHorizontally,
            // With the mark (unlock) the block sits right under the status bar, as on `PasscodeLogin` (FIN-32).
            verticalArrangement = if (mark) Arrangement.Top else Arrangement.Center,
        ) {
            if (mark) {
                PfMark(size = d.mark)
                Spacer(Modifier.height(d.space5))
            }
            Text(title, style = PfTheme.type.title1, color = PfTheme.colors.text, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Spacer(Modifier.height(d.space2))
                Text(subtitle, style = PfTheme.type.lead, color = PfTheme.colors.textMuted, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(d.space8))
            PfPasscodeDots(filled = entered, length = PASSCODE_LENGTH, error = error)
        }
        PfNumPad(onDigit = onDigit, onDelete = onDelete, biometric = biometric, onBiometric = onBiometric)
        Box(Modifier.height(d.control + d.space4), contentAlignment = Alignment.Center) { footer?.invoke() }
        Spacer(Modifier.height(d.space2))
    }
}

/** First run, step 3 of 4: set the code, then (if the device can) offer biometrics. */
@Composable
fun PasscodeSetupScreen(onDone: () -> Unit, vm: PasscodeEntryViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.configure(changeExisting = false) }
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    LaunchedEffect(state.done) { if (state.done) onDone() }
    // Back from the repeat step returns to the first entry; from the first entry it leaves the app (the code is mandatory).
    BackHandler { if (!vm.backToFirstEntry()) activity?.finish() }

    when (state.step) {
        EnterStep.BIOMETRIC -> BiometricOffer(
            noun = state.biometric.noun(),
            icon = if (state.biometric == BiometricAvailability.FACE) PfIcons.SCAN_FACE else PfIcons.FINGERPRINT,
            onEnable = vm::enableBiometric,
            onSkip = vm::skipBiometric,
        )
        else -> PasscodePad(
            header = { PfPageHeader(title = "Шаг 3 из 4", onBack = null) },
            title = if (state.step == EnterStep.REPEAT) "Повторите код-пароль" else "Придумайте код-пароль",
            subtitle = if (state.step == EnterStep.REPEAT) null else "4 цифры — чтобы открывать приложение на этом телефоне без звонка",
            entered = state.entered.length,
            error = state.error,
            onDigit = vm::digit,
            onDelete = vm::delete,
        )
    }
}

@Composable
private fun BiometricOffer(noun: String, icon: String, onEnable: () -> Unit, onSkip: () -> Unit) {
    val d = PfTheme.dimens
    BackHandler { onSkip() }
    Column(
        Modifier
            .fillMaxSize()
            .background(PfTheme.colors.bg)
            .windowInsetsPadding(PfInsets.navigationBars),
    ) {
        PfPageHeader(title = "Шаг 3 из 4", onBack = null)
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = d.space5), verticalArrangement = Arrangement.Center) {
            PfStatusHero(
                tone = HeroTone.INFO,
                icon = icon,
                title = "Входить по $noun?",
                subtitle = "Быстрее, чем набирать код. Код-пароль останется запасным способом",
            )
        }
        Column(Modifier.padding(horizontal = d.space5)) {
            PfButton("Включить", onClick = onEnable, variant = ButtonVariant.PRIMARY, block = true)
            Spacer(Modifier.height(d.space2))
            PfButton("Не сейчас", onClick = onSkip, variant = ButtonVariant.GHOST, block = true)
            Spacer(Modifier.height(d.space4))
        }
    }
}

/** Full-screen overlay shown while [ru.finassist.pf.feature.applock.api.LockState.Locked]. */
@Composable
fun UnlockScreen(vm: UnlockViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity
    val prompt = rememberBiometricPrompt(title = "Понятные финансы", onSuccess = vm::onBiometricSuccess)
    // Biometrics first (decision auth-owner-2); the keypad stays underneath for cancel / failure.
    LaunchedEffect(state.biometricEnabled) { if (state.biometricEnabled) prompt() }
    BackHandler { activity?.finish() }
    UnlockContent(
        state = state, onDigit = vm::digit, onDelete = vm::delete, onBiometric = prompt,
        onAskForgot = vm::askForgot, onForgot = vm::forgot,
    )
}

/** The unlock overlay drawn from a ready state (design-check snapshots render it without a ViewModel). */
@Composable
internal fun UnlockContent(
    state: UnlockUiState,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onBiometric: () -> Unit,
    onAskForgot: (Boolean) -> Unit,
    onForgot: () -> Unit,
) {
    PasscodePad(
        mark = true,
        title = "Введите код-пароль",
        subtitle = null,
        entered = state.entered.length,
        error = state.error,
        onDigit = onDigit,
        onDelete = onDelete,
        biometric = if (state.biometricEnabled) state.biometric.toKind() else BiometricKind.NONE,
        onBiometric = onBiometric,
        footer = { PfLink("Забыли код?", onClick = { onAskForgot(true) }) },
    )
    if (state.askForgot) {
        PfDialog(
            title = "Выйти из аккаунта?",
            description = "Сбросить код-пароль можно только новым входом: номер телефона, звонок и новый код. Данные не пропадут.",
            confirmText = "Выйти",
            onConfirm = onForgot,
            onDismiss = { onAskForgot(false) },
            busy = state.busy,
        )
    }
}

/** Profile → «Код-пароль и биометрия». */
@Composable
fun SecurityScreen(onBack: () -> Unit, onChangePasscode: () -> Unit, vm: SecurityViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val d = PfTheme.dimens
    val prompt = rememberBiometricPrompt(title = "Включить вход по биометрии", onSuccess = { vm.setBiometric(true) })
    Column(
        Modifier
            .fillMaxSize()
            .background(PfTheme.colors.bg)
            .windowInsetsPadding(PfInsets.navigationBars),
    ) {
        PfPageHeader(title = "Код-пароль и биометрия", onBack = onBack)
        Column(Modifier.padding(horizontal = d.space5)) {
            PfCard(flush = true) {
                PfListRow(title = "Сменить код-пароль", icon = PfIcons.LOCK, onClick = onChangePasscode)
                if (state.biometric != BiometricAvailability.NONE) {
                    PfListRow(
                        title = "Вход по ${state.biometric.noun()}",
                        icon = if (state.biometric == BiometricAvailability.FACE) PfIcons.SCAN_FACE else PfIcons.FINGERPRINT,
                        description = "Код-пароль остаётся запасным способом",
                        switchChecked = state.biometricEnabled,
                        onSwitch = { on -> if (on) prompt() else vm.setBiometric(false) },
                    )
                }
            }
            Spacer(Modifier.height(d.space3))
            Text(
                "Если забыть код, войти заново можно по номеру телефона и звонку — данные сохранятся",
                style = PfTheme.type.caption, color = PfTheme.colors.textMuted,
                modifier = Modifier.padding(horizontal = d.space1),
            )
        }
    }
}

/** Change passcode: current → new → repeat. */
@Composable
fun ChangePasscodeScreen(onBack: () -> Unit, onDone: () -> Unit, vm: PasscodeEntryViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { vm.configure(changeExisting = true) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    BackHandler { if (!vm.backToFirstEntry()) onBack() }
    PasscodePad(
        header = { PfPageHeader(title = "Смена код-пароля", onBack = onBack) },
        title = when (state.step) {
            EnterStep.OLD -> "Введите текущий код"
            EnterStep.REPEAT -> "Повторите новый код"
            else -> "Придумайте новый код"
        },
        subtitle = null,
        entered = state.entered.length,
        error = state.error,
        onDigit = vm::digit,
        onDelete = vm::delete,
    )
}

/** Confirms the code (or biometrics, when enabled) before a dangerous action; the caller gets the result through the navigator. */
@Composable
fun ConfirmPasscodeScreen(onBack: () -> Unit, onConfirmed: () -> Unit, vm: ConfirmPasscodeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt = rememberBiometricPrompt(title = "Подтвердите удаление", onSuccess = vm::onBiometricSuccess)
    LaunchedEffect(state.confirmed) { if (state.confirmed) onConfirmed() }
    LaunchedEffect(Unit) { if (state.biometricEnabled) prompt() }
    PasscodePad(
        header = { PfPageHeader(title = "Подтверждение", onBack = onBack) },
        title = "Введите код-пароль",
        subtitle = "Чтобы подтвердить, что это вы",
        entered = state.entered.length,
        error = state.error,
        onDigit = vm::digit,
        onDelete = vm::delete,
        biometric = if (state.biometricEnabled) state.biometric.toKind() else BiometricKind.NONE,
        onBiometric = prompt,
    )
}
