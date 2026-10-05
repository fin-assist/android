package ru.finassist.pf.feature.auth.impl.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import ru.finassist.pf.core.designsystem.components.AppMark
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfTextField
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.components.StatusHero
import ru.finassist.pf.core.designsystem.components.StatusTone
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.auth.api.PhoneScreenNotice

/** Phone screen (mockups Phone / PhoneLoggedOut / PhoneDeleted / PhoneFormatError). */
@Composable
fun PhoneScreen(state: AuthViewModel.UiState, notice: PhoneScreenNotice, onPhoneChanged: (String) -> Unit, onContinue: () -> Unit, onDeletedAcknowledged: () -> Unit) {
    val c = PfTheme.colors
    if (notice == PhoneScreenNotice.AccountDeleted) {
        Column(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = ScreenPadding), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            StatusHero("check-circle", StatusTone.Positive, "Аккаунт удалён", "Мы стёрли выписки вместе с файлами, операции, ваши правки категорий и историю вопросов помощнику. Восстановить их нельзя")
            Spacer(Modifier.height(PfSpace.s6))
            PfButton("Готово", onClick = onDeletedAcknowledged, variant = ButtonVariant.Primary, block = true)
        }
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = ScreenPadding),
    ) {
        Spacer(Modifier.height(PfSpace.s12))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppMark(PfSize.mark)
            Spacer(Modifier.width(PfSpace.s3))
            Text("Понятные финансы", style = PfTheme.type.wordmark, color = c.text)
        }
        Spacer(Modifier.height(PfSpace.s6))
        Text("Разберите операции по выписке и узнайте о своих финансах", style = PfTheme.type.lead, color = c.textMuted)
        when (notice) {
            PhoneScreenNotice.LoggedOut -> { Spacer(Modifier.height(PfSpace.s4)); Notice("Вы вышли из аккаунта. Войдите по номеру телефона", tone = NoticeTone.Info) }
            PhoneScreenNotice.SessionExpired -> { Spacer(Modifier.height(PfSpace.s4)); Notice("Сеанс завершён. Войдите по номеру телефона ещё раз", tone = NoticeTone.Info) }
            else -> Unit
        }
        Spacer(Modifier.height(PfSpace.s6))
        PfTextField(
            value = state.phoneFormatted.takeIf { state.phoneDigits.isNotEmpty() } ?: "",
            onValueChange = onPhoneChanged,
            label = "Номер телефона",
            placeholder = "+7 900 000-00-00",
            hint = "Для подтверждения позвоните нам с этого номера — бесплатно",
            error = state.phoneError,
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Done,
            onImeAction = onContinue,
        )
        Spacer(Modifier.height(PfSpace.s6))
        PfButton("Продолжить", onClick = onContinue, variant = ButtonVariant.Primary, block = true, busy = state.starting, busyText = "Секунду…")
        Spacer(Modifier.height(PfSpace.s6))
    }
}
