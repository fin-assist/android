package ru.finassist.pf.feature.auth.impl.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.HeroTone
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfCheckbox
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfMark
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.components.PfStatusHero
import ru.finassist.pf.core.designsystem.components.PfTextField
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.auth.api.AuthRoutes

/** Screen padding of the sign-in screens: status bar + space-6 on top, gesture bar + space-4 at the bottom. */
@Composable
private fun AuthScaffold(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = PfTheme.dimens.space5, end = PfTheme.dimens.space5, top = PfTheme.dimens.space6, bottom = PfTheme.dimens.space4),
        content = content,
    )
}

@Composable
fun PhoneScreen(reason: String?, onNext: () -> Unit, vm: PhoneViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val event by vm.events.collectAsStateWithLifecycle()
    LaunchedEffect(reason) {
        vm.setNotice(
            when (reason) {
                AuthRoutes.Phone.REASON_LOGGED_OUT -> "Вы вышли из аккаунта. Войдите по номеру телефона"
                AuthRoutes.Phone.REASON_EXPIRED -> "Сессия закончилась — войдите снова по номеру телефона"
                else -> null
            },
        )
    }
    LaunchedEffect(event) {
        if (event == PhoneEvent.GoToCall) { vm.consumeEvent(); onNext() }
    }
    var showDeleted by rememberSaveable { mutableStateOf(reason == AuthRoutes.Phone.REASON_DELETED) }
    if (showDeleted) {
        DeletedScreen(onDone = { showDeleted = false })
        return
    }
    AuthScaffold {
        Column(Modifier.weight(1f).padding(vertical = PfTheme.dimens.space6), verticalArrangement = Arrangement.Center) {
            PfMark(size = PfTheme.dimens.mark)
            Spacer(Modifier.height(PfTheme.dimens.space4))
            Text("Понятные финансы", style = PfTheme.type.title1, color = PfTheme.colors.text)
            Text("Разберите операции по выписке и узнайте о своих финансах", style = PfTheme.type.lead, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = PfTheme.dimens.space1))
        }
        Column(verticalArrangement = Arrangement.spacedBy(PfTheme.dimens.space4)) {
            state.notice?.let { PfNotice(it, tone = NoticeTone.INFO, alert = true) }
            PfTextField(
                value = state.display,
                onValueChange = vm::onInput,
                label = "Номер телефона",
                placeholder = "+7 900 000-00-00",
                hint = "Для подтверждения позвоните нам с этого номера — бесплатно",
                error = state.error,
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done,
                onImeAction = vm::submit,
            )
            PfButton("Продолжить", onClick = vm::submit, variant = ButtonVariant.PRIMARY, block = true, busy = state.busy, busyText = "Отправляем…")
        }
    }
}

@Composable
private fun DeletedScreen(onDone: () -> Unit) {
    AuthScaffold {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            PfStatusHero(tone = HeroTone.POSITIVE, centered = false)
            Spacer(Modifier.height(PfTheme.dimens.space5))
            Text("Аккаунт удалён", style = PfTheme.type.title1, color = PfTheme.colors.text)
            Text(
                "Мы стёрли выписки вместе с файлами, операции, ваши правки категорий и историю вопросов помощнику. Восстановить их нельзя",
                style = PfTheme.type.lead, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = PfTheme.dimens.space2),
            )
        }
        PfButton("Готово", onClick = onDone, variant = ButtonVariant.PRIMARY, block = true)
    }
}

@Composable
fun CallScreen(onBack: () -> Unit, onNewUser: () -> Unit, onRegistrationClosed: () -> Unit, vm: CallViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val event by vm.events.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        vm.watch()
        onPauseOrDispose { vm.stopWatching() }
    }
    LaunchedEffect(event) {
        when (event) {
            CallEvent.NewUser -> { vm.consumeEvent(); onNewUser() }
            CallEvent.RegistrationClosed -> { vm.consumeEvent(); onRegistrationClosed() }
            CallEvent.SignedIn -> vm.consumeEvent() // the app switches graphs on the session state
            null -> Unit
        }
    }
    AuthScaffold {
        PfStatusHero(tone = HeroTone.INFO, icon = PfIcons.PHONE, centered = false)
        Spacer(Modifier.height(PfTheme.dimens.space4))
        Text("Позвоните нам для подтверждения", style = PfTheme.type.title1, color = PfTheme.colors.text)
        Text(
            "Позвоните с номера ${state.phoneDisplay} на наш номер. Звонок бесплатный, мы сразу сбросим его — по нему и поймём, что номер ваш",
            style = PfTheme.type.lead, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = PfTheme.dimens.space1),
        )
        if (state.phase == CallPhase.WAITING) {
            Spacer(Modifier.height(PfTheme.dimens.space6))
            PfCard {
                Text("Номер для звонка", style = PfTheme.type.caption, color = PfTheme.colors.textMuted)
                Text(state.callbackNumber, style = PfTheme.type.amountLg, color = PfTheme.colors.text, modifier = Modifier.padding(top = PfTheme.dimens.space1))
                Spacer(Modifier.height(PfTheme.dimens.space4))
                PfButton(
                    "Позвонить", icon = PfIcons.PHONE, variant = ButtonVariant.PRIMARY, block = true,
                    onClick = {
                        vm.onDialOpened()
                        runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${state.callbackNumber}"))) }
                    },
                )
            }
        }
        Spacer(Modifier.height(PfTheme.dimens.space4))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PfTheme.dimens.space4)) {
            when (state.phase) {
                CallPhase.WAITING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PfIcon(PfIcons.CLOCK, contentDescription = null, size = 16.dp, tint = PfTheme.colors.textMuted)
                        Spacer(Modifier.width(PfTheme.dimens.space2))
                        Text("Ждём звонок 3 минуты — после него вход продолжится сам", style = PfTheme.type.caption, color = PfTheme.colors.textMuted)
                    }
                    Text(
                        "Не проходит? Проверьте, что звоните с ${state.phoneDisplay} и не скрываете свой номер в настройках звонков",
                        style = PfTheme.type.caption, color = PfTheme.colors.textMuted,
                    )
                }
                CallPhase.EXPIRED -> {
                    PfNotice(
                        "Звонка с ${state.phoneDisplay} мы не дождались. Запросите номер ещё раз — звонок по-прежнему бесплатный",
                        title = "Время на звонок вышло", tone = NoticeTone.LIMIT, alert = true,
                    )
                    PfButton("Позвонить ещё раз", onClick = vm::requestAgain, variant = ButtonVariant.PRIMARY, block = true, busy = state.busy, busyText = "Запрашиваем…")
                }
                CallPhase.LOCKED -> PfNotice(
                    "Слишком много попыток. Позвонить можно будет позже" + (state.lockedUntil?.let { ", в $it" } ?: ""),
                    tone = NoticeTone.WARNING, alert = true,
                )
                CallPhase.OFFLINE -> {
                    PfNotice("Нет сети — не можем дождаться звонка. Проверьте интернет", tone = NoticeTone.WARNING, alert = true)
                    PfButton("Повторить", onClick = vm::watch, block = true)
                }
            }
            Row { PfLink("Изменить номер", onClick = onBack) }
        }
    }
}

@Composable
fun ConsentScreen(onBack: () -> Unit, onExpired: () -> Unit, vm: ConsentViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.expired) { if (state.expired) onExpired() }
    Column(Modifier.fillMaxSize()) {
        PfPageHeader("Регистрация", onBack = onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PfTheme.dimens.space5, vertical = PfTheme.dimens.space4),
        ) {
            Text("Номер подтверждён", style = PfTheme.type.title1, color = PfTheme.colors.text)
            Text(
                "На ${state.phoneDisplay} ещё нет аккаунта — создадим его. Для этого нужно ваше согласие на обработку персональных данных",
                style = PfTheme.type.lead, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = PfTheme.dimens.space1),
            )
            Spacer(Modifier.height(PfTheme.dimens.space6))
            PfCard {
                Text("Что будем хранить", style = PfTheme.type.bodyStrong, color = PfTheme.colors.text)
                Spacer(Modifier.height(PfTheme.dimens.space2))
                listOf(
                    "Номер телефона — для входа",
                    "Операции из выписок, которые вы загрузите, — для аналитики",
                    "Сами файлы выписок — зашифрованными, чтобы поддержка могла разобраться с ошибкой разбора",
                ).forEach { line ->
                    Row(Modifier.padding(vertical = PfTheme.dimens.space1), verticalAlignment = Alignment.Top) {
                        PfIcon(PfIcons.CHECK, contentDescription = null, size = PfTheme.dimens.iconMd, tint = PfTheme.colors.accent)
                        Spacer(Modifier.width(PfTheme.dimens.space3))
                        Text(line, style = PfTheme.type.body, color = PfTheme.colors.text)
                    }
                }
                Spacer(Modifier.height(PfTheme.dimens.space4))
                ru.finassist.pf.core.designsystem.components.PfDivider()
                Text(
                    "Данные хранятся в России. Удалить их можно в любой момент — вместе с аккаунтом в профиле",
                    style = PfTheme.type.caption, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = PfTheme.dimens.space4),
                )
            }
            Spacer(Modifier.height(PfTheme.dimens.space1))
            Row {
                PfLink(state.document?.title ?: "Текст согласия", onClick = {
                    state.document?.url?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
                })
            }
            Spacer(Modifier.height(PfTheme.dimens.space4))
            PfCheckbox(checked = state.accepted, onCheckedChange = vm::setAccepted, label = "Даю согласие на обработку персональных данных", error = state.error)
            Spacer(Modifier.height(PfTheme.dimens.space6))
            state.formError?.let { PfNotice(it, tone = NoticeTone.WARNING, alert = true); Spacer(Modifier.height(PfTheme.dimens.space3)) }
            PfButton("Создать аккаунт", onClick = vm::submit, variant = ButtonVariant.PRIMARY, block = true, busy = state.busy, busyText = "Создаём…")
            Text(
                "Нажимая «Создать аккаунт», вы принимаете условия использования и политику конфиденциальности",
                style = PfTheme.type.caption, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = PfTheme.dimens.space3),
            )
            Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

@Composable
fun RegistrationClosedScreen(onBack: () -> Unit) {
    AuthScaffold {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            PfStatusHero(tone = HeroTone.INFO, icon = PfIcons.LOCK, centered = false)
            Spacer(Modifier.height(PfTheme.dimens.space4))
            Text("Регистрация пока закрыта", style = PfTheme.type.title1, color = PfTheme.colors.text)
            Text(
                "Номер подтверждён, но новые аккаунты мы пока не создаём. Попробуйте позже",
                style = PfTheme.type.lead, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = PfTheme.dimens.space1),
            )
        }
        PfButton("Вернуться ко входу", onClick = onBack, variant = ButtonVariant.PRIMARY, block = true)
    }
}
