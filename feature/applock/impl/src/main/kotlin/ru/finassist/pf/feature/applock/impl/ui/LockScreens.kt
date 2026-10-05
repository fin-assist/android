package ru.finassist.pf.feature.applock.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.finassist.pf.core.designsystem.components.BiometricKind
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.ListRow
import ru.finassist.pf.core.designsystem.components.PageHeader
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfSwitch
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.components.StatusHero
import ru.finassist.pf.core.designsystem.components.StatusTone
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Lock gate shown over the app on launch / after 5 min in background (mockups PasscodeLogin*). */
@Composable
fun LockScreen(vm: AppLockViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val biometricEnabled by vm.biometricEnabled.collectAsStateWithLifecycle()
    val kind = if (biometricEnabled) Biometrics.kind(context) else BiometricKind.None
    val promptBiometric = {
        (context as? FragmentActivity)?.let { Biometrics.prompt(it, "Вход в «Понятные финансы»", onSuccess = vm::unlockedByBiometric) }
    }
    LaunchedEffect(kind) { if (kind != BiometricKind.None) promptBiometric() }
    PasscodeEntry(
        title = "Введите код-пароль",
        dotsLabel = "Код-пароль",
        onComplete = { code -> vm.tryUnlock(code)?.let(vm::wrongCodeText) },
        biometric = kind,
        onBiometric = { promptBiometric() },
        footerLinkText = "Забыли код?",
        onFooterLink = vm::forgot,
    )
}

/** First setup after login: create → repeat → biometrics offer (mockups Passcode*, PasscodeBiometric). */
@Composable
fun SetupScreen(vm: AppLockViewModel = hiltViewModel()) {
    val context = LocalContext.current
    var first by remember { mutableStateOf<String?>(null) }
    var step by remember { mutableStateOf(0) }   // 0 create, 1 repeat, 2 biometrics
    when (step) {
        0 -> PasscodeEntry(
            title = "Придумайте код-пароль",
            subtitle = "4 цифры — для входа в приложение",
            dotsLabel = "Код-пароль",
            onComplete = { code -> first = code; step = 1; null },
            resetKey = step,
        )
        1 -> PasscodeEntry(
            title = "Повторите код",
            dotsLabel = "Повторите код",
            onComplete = { code ->
                if (code == first) {
                    vm.setPasscode(code)
                    if (Biometrics.available(context)) { step = 2; null } else null
                } else "Коды не совпадают — введите ещё раз"
            },
            headerTitle = "Код-пароль",
            onBack = { first = null; step = 0 },
            resetKey = step,
        )
        else -> BiometricOffer(
            onEnable = {
                (context as? FragmentActivity)?.let { a -> Biometrics.prompt(a, "Вход по биометрии", onSuccess = { vm.setBiometric(true); vm.unlockedByBiometric() }, onFailure = { vm.unlockedByBiometric() }) }
            },
            onSkip = { vm.setBiometric(false); vm.unlockedByBiometric() },
        )
    }
}

@Composable
private fun BiometricOffer(onEnable: () -> Unit, onSkip: () -> Unit) {
    val c = PfTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg).padding(horizontal = ScreenPadding).windowInsetsPadding(WindowInsets.navigationBars), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        StatusHero("fingerprint", StatusTone.Info, "Входить по биометрии?", "Отпечаток или лицо вместо кода-пароля. Код останется запасным способом")
        Spacer(Modifier.height(PfSpace.s6))
        PfButton("Включить", onClick = onEnable, variant = ButtonVariant.Primary, block = true)
        Spacer(Modifier.height(PfSpace.s2))
        PfButton("Не сейчас", onClick = onSkip, variant = ButtonVariant.Ghost, block = true)
    }
}

/** «Код-пароль и биометрия» settings (mockups Security / SecurityBiometricOff). */
@Composable
fun SecurityScreen(onBack: () -> Unit, onChangeCode: () -> Unit, vm: AppLockViewModel = hiltViewModel()) {
    val c = PfTheme.colors
    val context = LocalContext.current
    val biometricEnabled by vm.biometricEnabled.collectAsStateWithLifecycle()
    val available = Biometrics.available(context)
    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageHeader(title = "Код-пароль и биометрия", onBack = onBack)
        Column(Modifier.padding(horizontal = ScreenPadding, vertical = PfSpace.s2)) {
            PfCard(flush = true) {
                ListRow(
                    icon = "fingerprint",
                    title = "Вход по биометрии",
                    description = if (available) "Отпечаток или лицо вместо кода-пароля" else "На этом устройстве биометрия недоступна",
                    trailing = {
                        PfSwitch(checked = biometricEnabled && available, onCheckedChange = { enable ->
                            if (!available) return@PfSwitch
                            if (enable) (context as? FragmentActivity)?.let { a -> Biometrics.prompt(a, "Вход по биометрии", onSuccess = { vm.setBiometric(true) }) }
                            else vm.setBiometric(false)
                        })
                    },
                )
                ListRow(icon = "lock", title = "Сменить код", onClick = onChangeCode, divider = false)
            }
        }
    }
}

/** Change passcode: current → new → repeat (mockup PasscodeChange). */
@Composable
fun ChangePasscodeScreen(onDone: () -> Unit, onBack: () -> Unit, vm: AppLockViewModel = hiltViewModel()) {
    var step by remember { mutableStateOf(0) }
    var first by remember { mutableStateOf<String?>(null) }
    when (step) {
        0 -> PasscodeEntry(title = "Введите текущий код", dotsLabel = "Текущий код", headerTitle = "Смена кода", onBack = onBack,
            onComplete = { code -> vm.verify(code)?.let(vm::wrongCodeText) ?: run { step = 1; null } }, resetKey = step)
        1 -> PasscodeEntry(title = "Придумайте новый код", dotsLabel = "Новый код", headerTitle = "Смена кода", onBack = { step = 0 },
            onComplete = { code -> first = code; step = 2; null }, resetKey = step)
        else -> PasscodeEntry(title = "Повторите новый код", dotsLabel = "Повторите код", headerTitle = "Смена кода", onBack = { step = 1 },
            onComplete = { code -> if (code == first) { vm.setPasscode(code); onDone(); null } else "Коды не совпадают — введите ещё раз" }, resetKey = step)
    }
}

/** Identity confirmation before a dangerous action (README «Необратимые действия»): biometrics first, else passcode. */
@Composable
fun ConfirmScreen(reason: String, title: String, onBack: () -> Unit, onConfirmed: () -> Unit, vm: AppLockViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val biometricEnabled by vm.biometricEnabled.collectAsStateWithLifecycle()
    val kind = if (biometricEnabled) Biometrics.kind(context) else BiometricKind.None
    val confirm = { vm.confirmed(reason); onConfirmed() }
    val promptBiometric = { (context as? FragmentActivity)?.let { Biometrics.prompt(it, title, onSuccess = { confirm() }) } }
    LaunchedEffect(kind) { if (kind != BiometricKind.None) promptBiometric() }
    PasscodeEntry(
        title = "Введите код-пароль",
        dotsLabel = "Код-пароль",
        headerTitle = title,
        onBack = onBack,
        onComplete = { code -> vm.verify(code)?.let(vm::wrongCodeText) ?: run { confirm(); null } },
        biometric = kind,
        onBiometric = { promptBiometric() },
        footerLinkText = "Забыли код?",
        onFooterLink = vm::forgot,
    )
}
