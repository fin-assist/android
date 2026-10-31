package ru.finassist.pf.feature.applock.impl.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.finassist.pf.core.designsystem.components.BiometricKind
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.HeroTone
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfDialog
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfListRow
import ru.finassist.pf.core.designsystem.components.PfMark
import ru.finassist.pf.core.designsystem.components.PfNotice
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
 * Common layout of all passcode screens (mockups `Passcode*`, `PasscodeLogin*`, `DeleteAccount*`): optional
 * header, then from the top — the app mark (unlock) or the lock hero (first run), title, subtitle 4 dp below, dots
 * 32 dp below; the keypad at the bottom with an optional footer link under it. Short viewports use smaller
 * gaps and 56 dp keys. Only the introduction can scroll at larger font scales; the keypad stays put.
 *
 * [alignStart] puts the texts at the start (first run, change), otherwise they are centred.
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
    hero: Boolean = false,
    alignStart: Boolean = false,
    titleStyle: TextStyle = PfTheme.type.title1,
    biometric: BiometricKind = BiometricKind.NONE,
    onBiometric: () -> Unit = {},
    footer: (@Composable () -> Unit)? = null,
) {
    val d = PfTheme.dimens
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxHeight < 720.dp
        Column(
            Modifier
                .fillMaxSize()
                .background(PfTheme.colors.bg)
                .then(if (header == null) Modifier.windowInsetsPadding(PfInsets.statusBars) else Modifier)
                .windowInsetsPadding(PfInsets.navigationBars),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // PfPageHeader pads the status bar itself.
            header?.invoke()
            Spacer(Modifier.height(if (compact) d.space2 else d.space6))
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = d.space5),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (mark) {
                    PfMark(size = d.mark)
                    Spacer(Modifier.height(if (compact) d.space2 else d.space5))
                }
                val textAlign = if (alignStart) TextAlign.Start else TextAlign.Center
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = if (alignStart) Alignment.Start else Alignment.CenterHorizontally,
                ) {
                    if (hero) {
                        PfStatusHero(tone = HeroTone.INFO, icon = PfIcons.LOCK, centered = false)
                        Spacer(Modifier.height(d.space4))
                    }
                    Text(title, style = titleStyle, color = PfTheme.colors.text, textAlign = textAlign)
                    if (subtitle != null) {
                        Spacer(Modifier.height(d.space1))
                        Text(subtitle, style = PfTheme.type.lead, color = PfTheme.colors.textMuted, textAlign = textAlign)
                    }
                }
                Spacer(Modifier.height(if (compact) d.space4 else d.space8))
                PfPasscodeDots(filled = entered, length = PASSCODE_LENGTH, error = error)
            }
            PfNumPad(
                onDigit = onDigit, onDelete = onDelete, biometric = biometric, onBiometric = onBiometric,
                keySize = if (compact) 56.dp else 72.dp,
                verticalSpacing = if (compact) d.space2 else d.space4,
            )
            if (footer != null) {
                // Mockup: the link 16 dp under the keypad (48 dp touch height), 16 dp to the bottom edge.
                Spacer(Modifier.height(if (compact) d.space2 else d.space4))
                Box(Modifier.defaultMinSize(minHeight = d.touch), contentAlignment = Alignment.Center) { footer() }
            }
            Spacer(Modifier.height(if (compact) d.space2 else d.space4))
        }
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

    PasscodeSetupContent(
        state = state, onDigit = vm::digit, onDelete = vm::delete,
        onEnableBiometric = vm::enableBiometric, onSkipBiometric = vm::skipBiometric,
    )
}

/** First-run passcode steps drawn from a ready state (design-check snapshots render it without a ViewModel). */
@Composable
internal fun PasscodeSetupContent(
    state: PasscodeEntryUiState,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onEnableBiometric: () -> Unit,
    onSkipBiometric: () -> Unit,
) {
    when (state.step) {
        EnterStep.BIOMETRIC -> BiometricOffer(
            icon = if (state.biometric == BiometricAvailability.FACE) PfIcons.SCAN_FACE else PfIcons.FINGERPRINT,
            onEnable = onEnableBiometric,
            onSkip = onSkipBiometric,
        )
        else -> PasscodePad(
            modifier = Modifier.testTag(if (state.step == EnterStep.REPEAT) AppLockTags.SETUP_REPEAT else AppLockTags.SETUP),
            hero = true,
            alignStart = true,
            title = if (state.step == EnterStep.REPEAT) "Повторите код-пароль" else "Придумайте код-пароль",
            subtitle = if (state.step == EnterStep.REPEAT) "Введите те же 4 цифры" else PASSCODE_LEAD,
            entered = state.entered.length,
            error = state.error,
            onDigit = onDigit,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun BiometricOffer(icon: String, onEnable: () -> Unit, onSkip: () -> Unit) {
    val d = PfTheme.dimens
    BackHandler { onSkip() }
    Column(
        Modifier
            .testTag(AppLockTags.BIOMETRIC_OFFER)
            .fillMaxSize()
            .background(PfTheme.colors.bg)
            .windowInsetsPadding(PfInsets.statusBars)
            .windowInsetsPadding(PfInsets.navigationBars)
            .padding(start = d.space5, end = d.space5, top = d.space6, bottom = d.space4),
    ) {
        PfStatusHero(tone = HeroTone.INFO, icon = icon, centered = false)
        Spacer(Modifier.height(d.space4))
        Text("Входить по биометрии?", style = PfTheme.type.title1, color = PfTheme.colors.text)
        Spacer(Modifier.height(d.space1))
        Text(
            "Отпечаток или лицо — что настроено на телефоне. Код-пароль останется запасным способом",
            style = PfTheme.type.lead, color = PfTheme.colors.textMuted,
        )
        Spacer(Modifier.weight(1f).heightIn(min = d.space6))
        PfButton("Включить", onClick = onEnable, variant = ButtonVariant.PRIMARY, block = true, modifier = Modifier.testTag(AppLockTags.BIOMETRIC_ENABLE))
        Spacer(Modifier.height(d.space2))
        PfButton("Не сейчас", onClick = onSkip, variant = ButtonVariant.GHOST, block = true, modifier = Modifier.testTag(AppLockTags.BIOMETRIC_SKIP))
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
fun UnlockContent(
    state: UnlockUiState,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onBiometric: () -> Unit,
    onAskForgot: (Boolean) -> Unit,
    onForgot: () -> Unit,
) {
    PasscodePad(
        modifier = Modifier.testTag(AppLockTags.UNLOCK),
        mark = true,
        title = "Введите код-пароль",
        subtitle = null,
        entered = state.entered.length,
        error = state.error,
        onDigit = onDigit,
        onDelete = onDelete,
        biometric = if (state.biometricEnabled) state.biometric.toKind() else BiometricKind.NONE,
        onBiometric = onBiometric,
        footer = { PfLink("Забыли код?", onClick = { onAskForgot(true) }, modifier = Modifier.testTag(AppLockTags.UNLOCK_FORGOT)) },
    )
    if (state.askForgot) ForgotDialog(busy = state.busy, onConfirm = onForgot, onDismiss = { onAskForgot(false) })
}

/** Profile → «Код-пароль и биометрия». */
@Composable
fun SecurityScreen(onBack: () -> Unit, onChangePasscode: () -> Unit, vm: SecurityViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt = rememberBiometricPrompt(title = "Включить вход по биометрии", onSuccess = { vm.setBiometric(true) })
    SecurityContent(
        state = state, onBack = onBack, onChangePasscode = onChangePasscode,
        onBiometricSwitch = { on -> if (on) prompt() else vm.setBiometric(false) },
    )
}

/** Security settings drawn from a ready state (design-check snapshots render it without a ViewModel). */
@Composable
internal fun SecurityContent(
    state: SecurityUiState,
    onBack: () -> Unit,
    onChangePasscode: () -> Unit,
    onBiometricSwitch: (Boolean) -> Unit,
) {
    val d = PfTheme.dimens
    Column(
        Modifier
            .testTag(AppLockTags.SECURITY)
            .fillMaxSize()
            .background(PfTheme.colors.bg)
            .windowInsetsPadding(PfInsets.navigationBars),
    ) {
        PfPageHeader(title = "Код-пароль и биометрия", onBack = onBack)
        Column(Modifier.padding(horizontal = d.space5).padding(top = d.space4)) {
            PfCard(flush = true) {
                if (state.biometric != BiometricAvailability.NONE) {
                    PfListRow(
                        title = "Вход по биометрии",
                        icon = if (state.biometric == BiometricAvailability.FACE) PfIcons.SCAN_FACE else PfIcons.FINGERPRINT,
                        description = "Отпечаток или лицо — что настроено на телефоне",
                        switchChecked = state.biometricEnabled,
                        onSwitch = onBiometricSwitch,
                    )
                }
                PfListRow(title = "Сменить код-пароль", icon = PfIcons.LOCK, onClick = onChangePasscode, divider = false, modifier = Modifier.testTag(AppLockTags.SECURITY_CHANGE))
            }
            Spacer(Modifier.height(d.space3))
            PfNotice(
                "Код спрашиваем при запуске и после 5 минут в фоне. Если биометрия включена — сначала она",
                tone = NoticeTone.INFO,
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
    ChangePasscodeContent(state, onBack = onBack, onDigit = vm::digit, onDelete = vm::delete)
}

/** Change-passcode steps drawn from a ready state (design-check snapshots render it without a ViewModel). */
@Composable
internal fun ChangePasscodeContent(
    state: PasscodeEntryUiState,
    onBack: () -> Unit,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    PasscodePad(
        modifier = Modifier.testTag(AppLockTags.CHANGE),
        header = { PfPageHeader(title = "Смена код-пароля", onBack = onBack) },
        alignStart = true,
        titleStyle = PfTheme.type.title2,
        title = when (state.step) {
            EnterStep.OLD -> "Введите текущий код-пароль"
            EnterStep.REPEAT -> "Повторите новый код-пароль"
            else -> "Введите новый код-пароль"
        },
        subtitle = when (state.step) {
            EnterStep.REPEAT -> "Введите те же 4 цифры"
            else -> PASSCODE_LEAD
        },
        entered = state.entered.length,
        error = state.error,
        onDigit = onDigit,
        onDelete = onDelete,
    )
}

/** Confirms the code (or biometrics, when enabled) before a dangerous action; the caller gets the result through the navigator. */
@Composable
fun ConfirmPasscodeScreen(onBack: () -> Unit, onConfirmed: () -> Unit, vm: ConfirmPasscodeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt = rememberBiometricPrompt(title = "Подтвердите удаление", onSuccess = vm::onBiometricSuccess)
    LaunchedEffect(state.confirmed) { if (state.confirmed) onConfirmed() }
    LaunchedEffect(Unit) { if (state.biometricEnabled) prompt() }
    ConfirmPasscodeContent(
        state, onBack = onBack, onDigit = vm::digit, onDelete = vm::delete, onBiometric = prompt,
        onAskForgot = vm::askForgot, onForgot = vm::forgot,
    )
}

/**
 * The code step of account deletion (mockups `DeleteAccount*`), its only use: «Забыли код?» signs out, as on the
 * unlock screen. A wrong code keeps the four dots filled in the warning colour until the next digit.
 */
@Composable
fun ConfirmPasscodeContent(
    state: ConfirmUiState,
    onBack: () -> Unit,
    onDigit: (Int) -> Unit,
    onDelete: () -> Unit,
    onBiometric: () -> Unit,
    onAskForgot: (Boolean) -> Unit,
    onForgot: () -> Unit,
) {
    PasscodePad(
        modifier = Modifier.testTag(AppLockTags.CONFIRM),
        header = { PfPageHeader(title = "Удаление аккаунта", onBack = onBack) },
        title = "Введите код-пароль",
        subtitle = "Чтобы удалить аккаунт, подтвердите, что это вы",
        entered = state.entered.length,
        error = state.error,
        onDigit = onDigit,
        onDelete = onDelete,
        biometric = if (state.biometricEnabled) state.biometric.toKind() else BiometricKind.NONE,
        onBiometric = onBiometric,
        footer = { PfLink("Забыли код?", onClick = { onAskForgot(true) }, modifier = Modifier.testTag(AppLockTags.CONFIRM_FORGOT)) },
    )
    if (state.askForgot) ForgotDialog(busy = state.busy, onConfirm = onForgot, onDismiss = { onAskForgot(false) })
}

/** «Забыли код?»: the code can be reset only by signing in again. */
@Composable
private fun ForgotDialog(busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    PfDialog(
        title = "Выйти из аккаунта?",
        description = "Сбросить код-пароль можно только новым входом: номер телефона, звонок и новый код. Данные не пропадут.",
        confirmText = "Выйти",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        busy = busy,
    )
}

/** Lead of the steps that ask for a new code. */
private const val PASSCODE_LEAD = "4 цифры — для входа в приложение"

/** Test tags of the passcode screens (UI tests in `.maestro/`, convention in docs/e2e.md). */
internal object AppLockTags {
    const val SETUP = "applock.setup"
    const val SETUP_REPEAT = "applock.setup.repeat"
    const val BIOMETRIC_OFFER = "applock.biometric_offer"
    const val BIOMETRIC_ENABLE = "applock.biometric_offer.enable"
    const val BIOMETRIC_SKIP = "applock.biometric_offer.skip"
    const val UNLOCK = "applock.unlock"
    const val UNLOCK_FORGOT = "applock.unlock.forgot"
    const val SECURITY = "applock.security"
    const val SECURITY_CHANGE = "applock.security.change"
    const val CHANGE = "applock.change"
    const val CONFIRM = "applock.confirm"
    const val CONFIRM_FORGOT = "applock.confirm.forgot"
}
