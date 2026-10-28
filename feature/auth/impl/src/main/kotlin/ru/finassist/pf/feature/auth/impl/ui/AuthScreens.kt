package ru.finassist.pf.feature.auth.impl.ui

import ru.finassist.pf.core.designsystem.components.ErrorMessage
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.finassist.pf.core.common.LegalLinks
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.HeroTone
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfLinkedText
import ru.finassist.pf.core.designsystem.components.TextPart
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
import ru.finassist.pf.core.designsystem.theme.PfInsets
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.impl.domain.PhoneFormat

/** Screen padding of the sign-in screens: status bar + space-6 on top, gesture bar + space-4 at the bottom. */
@Composable
private fun AuthScaffold(tag: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .testTag(tag)
            .fillMaxSize()
            .windowInsetsPadding(PfInsets.statusBars)
            .windowInsetsPadding(PfInsets.navigationBars)
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
    LifecycleResumeEffect(Unit) { vm.onResume(); onPauseOrDispose {} }
    val context = LocalContext.current
    PhoneContent(state, onInput = vm::onInput, onSubmit = vm::submit,
        onAgeConfirmed = vm::setAgeConfirmed, onConsentAccepted = vm::setConsentAccepted,
        onRefreshDocument = vm::refreshDocument,
        onOpenDocument = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } })
}

@Composable
internal fun PhoneContent(
    state: PhoneUiState, onInput: (TextFieldValue) -> Unit, onSubmit: () -> Unit,
    onAgeConfirmed: (Boolean) -> Unit = {}, onConsentAccepted: (Boolean) -> Unit = {},
    onRefreshDocument: () -> Unit = {}, onOpenDocument: (String) -> Unit = {},
) {
    AuthScaffold(AuthTags.PHONE) {
        Column(Modifier.weight(1f).padding(vertical = PfTheme.dimens.space6), verticalArrangement = Arrangement.Center) {
            PfMark(size = PfTheme.dimens.mark)
            Spacer(Modifier.height(PfTheme.dimens.space4))
            Text("Понятные финансы", style = PfTheme.type.title1, color = PfTheme.colors.text)
            Text("Разберите операции по выписке и узнайте о своих финансах", style = PfTheme.type.lead, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = PfTheme.dimens.space1))
        }
        Column(verticalArrangement = Arrangement.spacedBy(PfTheme.dimens.space4)) {
            state.notice?.let { PfNotice(it, tone = NoticeTone.INFO, alert = true, modifier = Modifier.testTag(AuthTags.PHONE_NOTICE)) }
            PfCheckbox(state.ageConfirmed, onAgeConfirmed, label = "Мне исполнилось 14 лет",
                modifier = Modifier.testTag(AuthTags.PHONE_AGE))
            PfTextField(
                enabled = state.ageConfirmed,
                value = state.input,
                onValueChange = onInput,
                label = "Номер телефона",
                placeholder = "+7 900 000-00-00",
                visualTransformation = PhoneMaskTransformation,
                hint = "Для подтверждения позвоните нам с этого номера",
                error = state.error.takeIf { state.errorPresentation == null },
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done,
                onImeAction = onSubmit,
                testTag = AuthTags.PHONE_INPUT,
            )
            PfCheckbox(state.pdConsentAccepted, onConsentAccepted, label = "Даю согласие на обработку персональных данных",
                modifier = Modifier.testTag(AuthTags.PHONE_CONSENT))
            state.document?.let { document ->
                PfLink(document.title, onClick = { onOpenDocument(document.url) }, modifier = Modifier.testTag(AuthTags.PHONE_DOCUMENT))
            }
            state.documentError?.let { PfNotice(it, tone = NoticeTone.WARNING, alert = true) }
            if (state.documentRefreshRequired || state.documentError != null) {
                PfButton("Обновить документ", enabled = state.documentRefreshReady, onClick = onRefreshDocument,
                    busy = state.documentBusy, busyText = "Загружаем…", modifier = Modifier.testTag(AuthTags.PHONE_DOCUMENT_REFRESH))
            }
            state.errorPresentation?.let { presentation ->
                ErrorMessage(presentation, state.error ?: "Не получилось отправить номер. Попробуйте ещё раз")
            }
            PfButton(
                if (state.newIntentRequired) "Запросить заново" else "Продолжить",
                enabled = state.ageConfirmed && state.pdConsentAccepted && state.document != null && state.retryReady && !state.replayUnavailable, onClick = onSubmit, variant = ButtonVariant.PRIMARY, block = true, busy = state.busy, busyText = "Отправляем…",
                modifier = Modifier.testTag(AuthTags.PHONE_SUBMIT),
            )
        }
    }
}

@Composable
internal fun DeletedScreen(onDone: () -> Unit) {
    AuthScaffold(AuthTags.DELETED) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            PfStatusHero(tone = HeroTone.POSITIVE, centered = false)
            Spacer(Modifier.height(PfTheme.dimens.space5))
            Text("Аккаунт удалён", style = PfTheme.type.title1, color = PfTheme.colors.text)
            Text(
                "Мы стёрли выписки вместе с файлами, операции, ваши правки категорий и историю вопросов помощнику. Восстановить их нельзя",
                style = PfTheme.type.lead, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = PfTheme.dimens.space2),
            )
        }
        PfButton("Готово", onClick = onDone, variant = ButtonVariant.PRIMARY, block = true, modifier = Modifier.testTag(AuthTags.DELETED_DONE))
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
            CallEvent.ConsentRequired -> { vm.consumeEvent(); onBack() }
            CallEvent.SignedIn -> vm.consumeEvent() // the app switches graphs on the session state
            null -> Unit
        }
    }
    CallContent(
        state,
        onDial = {
            vm.onDialOpened()
            runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${state.callbackNumber}"))) }
        },
        onRequestAgain = vm::requestAgain,
        onRetry = vm::retry,
        onChangeNumber = onBack,
    )
}

@Composable
internal fun CallContent(
    state: CallUiState,
    onDial: () -> Unit,
    onRequestAgain: () -> Unit,
    onRetry: () -> Unit,
    onChangeNumber: () -> Unit,
) {
    AuthScaffold(AuthTags.CALL) {
        PfStatusHero(tone = HeroTone.INFO, icon = PfIcons.PHONE, centered = false)
        Spacer(Modifier.height(PfTheme.dimens.space4))
        Text("Позвоните нам для подтверждения", style = PfTheme.type.title1, color = PfTheme.colors.text)
        Text(
            "Позвоните с номера ${state.phoneDisplay} на наш номер. Мы сразу сбросим звонок — по нему и поймём, что номер ваш",
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
                    modifier = Modifier.testTag(AuthTags.CALL_DIAL),
                    onClick = onDial,
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
                        Text(
                            waitText(state.waitUntil), style = PfTheme.type.caption, color = PfTheme.colors.textMuted,
                            modifier = Modifier.testTag(AuthTags.CALL_WAIT),
                        )
                    }
                    Text(
                        "Не проходит? Проверьте, что звоните с ${state.phoneDisplay} и не скрываете свой номер в настройках звонков",
                        style = PfTheme.type.caption, color = PfTheme.colors.textMuted,
                    )
                }
                CallPhase.EXPIRED -> {
                    PfNotice(
                        "Звонка с ${state.phoneDisplay} мы не дождались. Запросите номер ещё раз",
                        title = "Время на звонок вышло", tone = NoticeTone.LIMIT, alert = true,
                    )
                    PfButton(
                        "Позвонить ещё раз", onClick = onRequestAgain, variant = ButtonVariant.PRIMARY, block = true, busy = state.busy, busyText = "Запрашиваем…",
                        modifier = Modifier.testTag(AuthTags.CALL_RETRY),
                    )
                }
                // The deadline passed by the device clock: the number is gone while the stream confirms the status.
                CallPhase.CHECKING -> Unit
                CallPhase.LOCKED -> {
                    ErrorMessage(state.errorPresentation ?: ru.finassist.pf.core.common.error.ErrorPresentation(), state.lockText.orEmpty())
                    if (state.newIntentRequired) PfButton("Запросить заново", onClick = onRequestAgain,
                        enabled = state.retryReady, busy = state.busy, block = true, modifier = Modifier.testTag(AuthTags.CALL_RETRY))
                }
                CallPhase.ERROR -> ErrorMessage(state.errorPresentation ?: ru.finassist.pf.core.common.error.ErrorPresentation(), "Не получилось проверить звонок. Попробуйте ещё раз",
                    onRetry = if (!state.retryReady || state.replayUnavailable) null else onRetry)
                CallPhase.OFFLINE -> {
                    PfNotice("Нет сети — не можем дождаться звонка. Проверьте интернет", tone = NoticeTone.WARNING, alert = true)
                    PfButton("Повторить", onClick = onRetry, enabled = state.retryReady && !state.replayUnavailable, block = true)
                }
            }
            Row { PfLink("Изменить номер", onClick = onChangeNumber, modifier = Modifier.testTag(AuthTags.CALL_CHANGE_NUMBER)) }
        }
    }
}

/** «…Ждём звонок до 12:34» by the device deadline; without one (unknown or passed) — no time is promised. */
internal fun waitText(waitUntil: String?): String {
    val base = "Как только дозвонитесь, вход продолжится сам."
    return if (waitUntil == null) base else "$base Ждём звонок до $waitUntil"
}

@Composable
fun ConsentScreen(onBack: () -> Unit, onExpired: () -> Unit, vm: ConsentViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.expired) { if (state.expired) onExpired() }
    ConsentContent(
        state,
        onBack = onBack,
        onOpenDocument = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        onSubmit = vm::submit,
    )
}

@Composable
internal fun ConsentContent(
    state: ConsentUiState,
    onBack: () -> Unit,
    onOpenDocument: (url: String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(Modifier.fillMaxSize().testTag(AuthTags.CONSENT)) {
        PfPageHeader("Регистрация", onBack = onBack)
        // The button sits at the bottom of the viewport (mockup: a growing spacer of at least space6 before it);
        // the column is at least as tall as the viewport, so the spacer can grow inside the scroll.
        BoxWithConstraints(Modifier.weight(1f)) {
            val viewport = maxHeight
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = viewport)
                    .padding(horizontal = PfTheme.dimens.space5, vertical = PfTheme.dimens.space4),
            ) {
                Text("Номер подтверждён", style = PfTheme.type.title1, color = PfTheme.colors.text)
                Text(
                    "На ${state.phoneDisplay} ещё нет аккаунта — создадим его. Согласие на обработку персональных данных вы уже дали до отправки номера",
                    style = PfTheme.type.lead, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = PfTheme.dimens.space1),
                )
                Spacer(Modifier.height(PfTheme.dimens.space6))
                PfCard {
                    Text("Что будем хранить", style = PfTheme.type.bodyStrong, color = PfTheme.colors.text)
                    Column(
                        Modifier.padding(top = PfTheme.dimens.space2),
                        verticalArrangement = Arrangement.spacedBy(PfTheme.dimens.space2),
                    ) {
                        listOf(
                            "Номер телефона — для входа",
                            "Операции из выписок, которые вы загрузите, — для аналитики",
                            "Сами файлы выписок — зашифрованными, чтобы поддержка могла разобраться с ошибкой разбора",
                        ).forEach { line ->
                            Row(verticalAlignment = Alignment.Top) {
                                PfIcon(PfIcons.CHECK, contentDescription = null, size = PfTheme.dimens.iconMd, tint = PfTheme.colors.accent)
                                Spacer(Modifier.width(PfTheme.dimens.space3))
                                Text(line, style = PfTheme.type.body, color = PfTheme.colors.text)
                            }
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
                        state.document?.url?.let(onOpenDocument)
                    })
                }
                Spacer(Modifier.height(PfTheme.dimens.space4))
                Spacer(Modifier.weight(1f).heightIn(min = PfTheme.dimens.space6))
                state.formError?.let {
                    val presentation = state.errorPresentation
                    if (presentation != null) ErrorMessage(presentation, it) else PfNotice(it, tone = NoticeTone.WARNING, alert = true)
                    Spacer(Modifier.height(PfTheme.dimens.space3))
                }
                PfButton(
                    "Создать аккаунт",
                    enabled = state.retryReady && !state.replayUnavailable,
                    onClick = onSubmit,
                    variant = ButtonVariant.PRIMARY, block = true, busy = state.busy,
                    busyText = "Создаём…",
                    modifier = Modifier.testTag(AuthTags.CONSENT_SUBMIT),
                )
                PfLinkedText(
                    listOf(
                        TextPart.Plain("Нажимая «Создать аккаунт», вы принимаете "),
                        TextPart.Link("условия использования", AuthTags.CONSENT_TERMS) { onOpenDocument(LegalLinks.TERMS) },
                        TextPart.Plain(" и "),
                        TextPart.Link("политику конфиденциальности", AuthTags.CONSENT_PRIVACY) { onOpenDocument(LegalLinks.PRIVACY) },
                    ),
                    modifier = Modifier.padding(top = PfTheme.dimens.space3),
                )
                Spacer(Modifier.windowInsetsPadding(PfInsets.navigationBars))
            }
        }
    }
}

@Composable
fun RegistrationClosedScreen(onBack: () -> Unit) {
    AuthScaffold(AuthTags.CLOSED) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            PfStatusHero(tone = HeroTone.INFO, icon = PfIcons.LOCK, centered = false)
            Spacer(Modifier.height(PfTheme.dimens.space4))
            Text("Регистрация пока закрыта", style = PfTheme.type.title1, color = PfTheme.colors.text)
            Text(
                "Номер подтверждён, но новые аккаунты мы пока не создаём. Попробуйте позже",
                style = PfTheme.type.lead, color = PfTheme.colors.textMuted, modifier = Modifier.padding(top = PfTheme.dimens.space1),
            )
        }
        PfButton("Вернуться ко входу", onClick = onBack, variant = ButtonVariant.PRIMARY, block = true, modifier = Modifier.testTag(AuthTags.CLOSED_BACK))
    }
}

/** Draws `+7 XXX XXX-XX-XX` over digits-only text; the offset mapping keeps the cursor next to the typed digit. */
private object PhoneMaskTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        return TransformedText(
            AnnotatedString(PhoneFormat.display(digits)),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = PhoneFormat.digitToDisplay(digits, offset)
                override fun transformedToOriginal(offset: Int) = PhoneFormat.displayToDigit(digits, offset)
            },
        )
    }
}

/** Test tags of the sign-in screens (UI tests in `.maestro/`, convention in docs/e2e.md). */
internal object AuthTags {
    const val PHONE = "auth.phone"
    const val PHONE_AGE = "auth.phone.age"
    const val PHONE_CONSENT = "auth.phone.consent"
    const val PHONE_DOCUMENT = "auth.phone.document"
    const val PHONE_DOCUMENT_REFRESH = "auth.phone.document_refresh"
    const val PHONE_INPUT = "auth.phone.input"
    const val PHONE_SUBMIT = "auth.phone.submit"
    const val PHONE_NOTICE = "auth.phone.notice"
    const val CALL = "auth.call"
    const val CALL_DIAL = "auth.call.dial"
    const val CALL_WAIT = "auth.call.wait"
    const val CALL_RETRY = "auth.call.retry"
    const val CALL_CHANGE_NUMBER = "auth.call.change_number"
    const val CONSENT = "auth.consent"
    const val CONSENT_CHECKBOX = "auth.consent.checkbox"
    const val CONSENT_SUBMIT = "auth.consent.submit"
    const val CONSENT_TERMS = "auth.consent.terms"
    const val CONSENT_PRIVACY = "auth.consent.privacy"
    const val CLOSED = "auth.closed"
    const val CLOSED_BACK = "auth.closed.back"
    const val DELETED = "auth.deleted"
    const val DELETED_DONE = "auth.deleted.done"
}
