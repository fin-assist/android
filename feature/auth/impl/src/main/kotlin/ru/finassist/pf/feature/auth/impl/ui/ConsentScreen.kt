package ru.finassist.pf.feature.auth.impl.ui

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
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PageHeader
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfCheckbox
import ru.finassist.pf.core.designsystem.components.PfIcon
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.common.error.AppError

/** Personal-data consent for a new number (mockups Consent / ConsentError). */
@Composable
fun ConsentScreen(state: AuthViewModel.UiState, onBack: () -> Unit, onChecked: (Boolean) -> Unit, onCreate: () -> Unit, onOpenUrl: (String) -> Unit) {
    val c = PfTheme.colors
    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageHeader(title = "Регистрация", onBack = onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = ScreenPadding, vertical = PfSpace.s2)) {
            Text("Номер подтверждён", style = PfTheme.type.title1, color = c.text)
            Spacer(Modifier.height(PfSpace.s2))
            Text("На ${state.phoneFormatted} ещё нет аккаунта — создадим его. Для этого нужно ваше согласие на обработку персональных данных", style = PfTheme.type.lead, color = c.textMuted)
            Spacer(Modifier.height(PfSpace.s6))
            PfCard {
                Text("Что будем хранить", style = PfTheme.type.bodyStrong, color = c.text)
                Spacer(Modifier.height(PfSpace.s3))
                StoredItem("phone", "Номер телефона — для входа")
                StoredItem("list", "Операции из выписок, которые вы загрузите, — для аналитики")
                StoredItem("file-text", "Сами файлы выписок — зашифрованными, чтобы поддержка могла разобраться с ошибкой разбора")
                Spacer(Modifier.height(PfSpace.s2))
                Text("Данные хранятся в России. Удалить их можно в любой момент — вместе с аккаунтом в профиле", style = PfTheme.type.caption, color = c.textMuted)
                state.consentUrl?.let { url -> PfLink("Текст согласия", onClick = { onOpenUrl(url) }) }
            }
            Spacer(Modifier.height(PfSpace.s4))
            PfCheckbox(checked = state.consentChecked, onCheckedChange = onChecked, label = "Даю согласие на обработку персональных данных", error = state.consentError != null)
            if (state.consentError != null) {
                Spacer(Modifier.height(PfSpace.s2))
                Notice(state.consentError, tone = NoticeTone.Warning, alert = true)
            }
            when (val e = state.registerError) {
                null -> Unit
                is AppError.Offline -> { Spacer(Modifier.height(PfSpace.s2)); Notice("Нет сети. Проверьте интернет и повторите", tone = NoticeTone.Warning, alert = true) }
                is AppError.ConsentOutdated -> { Spacer(Modifier.height(PfSpace.s2)); Notice("Текст согласия обновился — прочитайте новую версию и подтвердите ещё раз", tone = NoticeTone.Warning, alert = true) }
                else -> { Spacer(Modifier.height(PfSpace.s2)); Notice("Не получилось создать аккаунт. Повторите позже", tone = NoticeTone.Warning, alert = true) }
            }
            Spacer(Modifier.height(PfSpace.s6))
            PfButton("Создать аккаунт", onClick = onCreate, variant = ButtonVariant.Primary, block = true, busy = state.registering, busyText = "Создаём…")
            Spacer(Modifier.height(PfSpace.s3))
            Text("Нажимая «Создать аккаунт», вы принимаете условия использования и политику конфиденциальности", style = PfTheme.type.hint, color = c.textMuted)
            Spacer(Modifier.height(PfSpace.s6))
        }
    }
}

@Composable
private fun StoredItem(icon: String, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        PfIcon(icon, size = PfSize.iconMd, tint = PfTheme.colors.accent)
        Spacer(Modifier.width(PfSpace.s3))
        Text(text, style = PfTheme.type.body, color = PfTheme.colors.text)
    }
}
