package ru.finassist.pf.feature.auth.impl.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PageHeader
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfIcon
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.components.StatusHero
import ru.finassist.pf.core.designsystem.components.StatusTone
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import java.time.format.DateTimeFormatter

/** Call-back screen (mockups Code / CodeExpired / CodeLocked). The user dials the number themselves (ACTION_DIAL). */
@Composable
fun CallScreen(state: AuthViewModel.UiState, onBack: () -> Unit, onCallAgain: () -> Unit, onChangePhone: () -> Unit) {
    val c = PfTheme.colors
    val context = LocalContext.current
    val phone = state.phoneFormatted
    val number = state.verification?.callbackNumber
    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageHeader(title = "Подтверждение номера", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = ScreenPadding, vertical = PfSpace.s4)) {
            when (val cs = state.callState) {
                AuthViewModel.CallState.Waiting -> {
                    StatusHero("phone", StatusTone.Info, "Позвоните нам для подтверждения", "Позвоните с номера $phone на наш номер. Звонок бесплатный, мы сразу сбросим его — по нему и поймём, что номер ваш")
                    Spacer(Modifier.height(PfSpace.s6))
                    PfCard {
                        Text("Номер для звонка", style = PfTheme.type.caption, color = c.textMuted)
                        Text(number?.let(::formatCallback) ?: "…", style = PfTheme.type.title1, color = c.text)
                        Spacer(Modifier.height(PfSpace.s4))
                        PfButton("Позвонить", onClick = {
                            if (number != null) context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                        }, variant = ButtonVariant.Primary, block = true, icon = "phone", enabled = number != null)
                    }
                    Spacer(Modifier.height(PfSpace.s4))
                    Row(verticalAlignment = Alignment.Top) {
                        PfIcon("clock", size = PfSize.iconMd, tint = c.textMuted)
                        Spacer(Modifier.width(PfSpace.s2))
                        Text("Ждём звонок 3 минуты — после него вход продолжится сам", style = PfTheme.type.body, color = c.textMuted)
                    }
                    Spacer(Modifier.height(PfSpace.s4))
                    Text("Не проходит? Проверьте, что звоните с $phone и не скрываете свой номер в настройках звонков", style = PfTheme.type.caption, color = c.textMuted)
                }
                AuthViewModel.CallState.Expired -> {
                    Notice("Звонка с $phone мы не дождались. Запросите номер ещё раз — звонок по-прежнему бесплатный", tone = NoticeTone.Warning, title = "Время на звонок вышло")
                    Spacer(Modifier.height(PfSpace.s4))
                    PfButton("Позвонить ещё раз", onClick = onCallAgain, variant = ButtonVariant.Primary, block = true, busy = state.starting, busyText = "Секунду…")
                }
                is AuthViewModel.CallState.Locked -> {
                    val at = cs.retryAt?.let { "в " + it.atZoneSameInstant(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) } ?: "позже"
                    Notice("Слишком много попыток. Позвонить можно будет $at", tone = NoticeTone.Limit)
                }
                AuthViewModel.CallState.RegistrationClosed -> {
                    Notice("Номер подтверждён, но регистрация новых пользователей пока закрыта. Мы откроем её в ближайших версиях", tone = NoticeTone.Info, title = "Регистрация пока закрыта")
                }
                is AuthViewModel.CallState.Error -> {
                    Notice("Не получилось подтвердить номер. Запросите звонок ещё раз", tone = NoticeTone.Warning, alert = true)
                    Spacer(Modifier.height(PfSpace.s4))
                    PfButton("Позвонить ещё раз", onClick = onCallAgain, variant = ButtonVariant.Primary, block = true, busy = state.starting)
                }
            }
            Spacer(Modifier.height(PfSpace.s4))
            PfLink("Изменить номер", onClick = onChangePhone)
        }
    }
}

/** «+78001234567» → «8 800 123-45-67». */
private fun formatCallback(e164: String): String {
    val d = e164.filter { it.isDigit() }
    if (d.length != 11) return e164
    return "8 ${d.substring(1, 4)} ${d.substring(4, 7)}-${d.substring(7, 9)}-${d.substring(9, 11)}"
}
