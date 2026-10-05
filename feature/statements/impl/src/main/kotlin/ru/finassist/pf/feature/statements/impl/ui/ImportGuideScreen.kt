package ru.finassist.pf.feature.statements.impl.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.FileDrop
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.OrderedSteps
import ru.finassist.pf.core.designsystem.components.PageHeader
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfDialog
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.components.SectionTitle
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.statements.impl.domain.loadedText

private val STEPS = listOf(
    "Войдите в кабинет Т-Банка в браузере",
    "Нажмите «Скачать за год» — файл сохранится в «Загрузки»",
    "Выберите скачанный файл ниже",
)

private val FALLBACK_STEPS = listOf(
    "Откройте tbank.ru в браузере и войдите. На телефоне включите в меню браузера «Версия для ПК»",
    "Откройте «Операции» и выберите период — лучше год",
    "Нажмите значок загрузки и выберите «Скачать в OFX». Счета — все, «Без переводов» не включайте",
    "Выберите скачанный файл в нашем приложении",
)

private const val PRIVACY_SHORT = "Операции видите только вы. Файл выписки храним зашифрованным — поддержка открывает его, только чтобы исправить ошибку разбора. Помощнику данные уходят с вашего согласия. Удалить загрузку или аккаунт — в профиле."

/** Guide + file picker + parse progress (mockups ImportGuide*, ImportProgress). */
@Composable
fun ImportGuideScreen(
    state: ImportViewModel.UiState,
    vm: ImportViewModel,
    first: Boolean,
    onBack: () -> Unit,
) {
    val c = PfTheme.colors
    val context = LocalContext.current
    val uploading = state.phase as? ImportViewModel.Phase.Uploading
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::onFilePicked) }
    // OFX files are served with assorted MIME types (and often none at all), so the picker shows everything.
    val pick = { picker.launch(arrayOf("*/*")) }

    BackHandler(enabled = uploading != null) { vm.onAskCancel(true) }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageHeader(title = "Загрузка выписки", onBack = { if (uploading != null) vm.onAskCancel(true) else onBack() })
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = PfSpace.s2),
            verticalArrangement = Arrangement.spacedBy(PfSpace.s5),
        ) {
            state.config?.loaded?.let { loaded ->
                PfCard {
                    Text("Сейчас загружено", style = PfTheme.type.caption, color = c.textMuted)
                    Text(loadedText(loaded), style = PfTheme.type.bodyStrong, color = c.text)
                    Spacer(Modifier.height(PfSpace.s2))
                    Text("Кнопка «Скачать за год» захватит и прошлые месяцы — сравнивать станет с чем. Совпадающие операции не задвоятся", style = PfTheme.type.caption, color = c.textMuted)
                }
            }
            if (!state.uploadEnabled) {
                Notice("Загрузка выписок временно недоступна — попробуйте позже", tone = NoticeTone.Info)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s3)) {
                    SectionTitle("Как скачать выписку")
                    OrderedSteps(STEPS.map { text -> { Text(text, style = PfTheme.type.body, color = c.text) } })
                    Row(horizontalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                        PfButton("Войти в Т-Банк", onClick = { state.config?.loginUrl?.let { openUrl(context, it) } }, modifier = Modifier.weight(1f), enabled = state.config != null && uploading == null)
                        PfButton("Скачать за год", onClick = { state.config?.downloadUrl?.let { openUrl(context, it) } }, modifier = Modifier.weight(1f), variant = ButtonVariant.Primary, icon = "download", enabled = state.config != null && uploading == null)
                    }
                    PfLink("Не скачивается?", onClick = { vm.onFallbackSteps(true) })
                }
                if (state.configError != null && state.config == null) {
                    Notice("Не получилось загрузить ссылки на кабинет банка", tone = NoticeTone.Warning, action = { PfLink("Повторить", onClick = vm::loadConfig, inline = true) })
                }
                FileDrop(onPick = pick, busy = uploading != null, fileName = uploading?.fileName, progress = uploading?.percent ?: 0)
                if (state.cancelError) {
                    Notice("Не получилось прервать разбор — нет сети. Разбор продолжается, прервать можно в истории загрузок", tone = NoticeTone.Warning, alert = true)
                }
                if (state.uploadOffline) {
                    Notice("Нет связи с интернетом — файл не загружен", tone = NoticeTone.Warning, alert = true, action = { PfLink("Повторить", onClick = vm::retryUpload, inline = true) })
                }
                if (uploading != null) {
                    PfLink("Прервать разбор", onClick = { vm.onAskCancel(true) }, modifier = Modifier.align(Alignment.CenterHorizontally))
                } else if (first) {
                    PfLink("Загружу позже", onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally))
                }
            }
            Column {
                Text(PRIVACY_SHORT, style = PfTheme.type.caption, color = c.textMuted)
                PfLink("Как мы защищаем данные", onClick = { vm.onPrivacy(true) })
            }
            Spacer(Modifier.height(PfSpace.s6))
        }
    }

    if (state.showFallbackSteps) PfBottomSheet(
        title = "Скачайте выписку вручную в кабинете Т-Банка",
        onDismiss = { vm.onFallbackSteps(false) },
        footer = { PfButton("Понятно", onClick = { vm.onFallbackSteps(false) }, variant = ButtonVariant.Primary, block = true) },
    ) {
        OrderedSteps(FALLBACK_STEPS.map { text -> { Text(text, style = PfTheme.type.body, color = c.text) } })
    }

    if (state.showPrivacy) PfBottomSheet(
        title = "Как мы защищаем данные",
        onDismiss = { vm.onPrivacy(false) },
        footer = { PfButton("Понятно", onClick = { vm.onPrivacy(false) }, variant = ButtonVariant.Primary, block = true) },
    ) {
        PrivacyPoints()
    }

    if (uploading != null && uploading.askCancel) PfDialog(
        title = "Прервать разбор?",
        description = "Операции из файла ${uploading.fileName} не сохранятся. Файл останется на телефоне — его можно загрузить снова.",
        onDismiss = { vm.onAskCancel(false) },
        confirmText = "Прервать",
        onConfirm = vm::cancelUpload,
        dismissText = "Продолжить разбор",
    )
}

@Composable
private fun PrivacyPoints() {
    val c = PfTheme.colors
    val points = listOf(
        "file-text" to "Файл выписки хранится зашифрованным. Поддержка открывает его только по вашему обращению — чтобы исправить ошибку разбора.",
        "lock" to "Операции видите только вы: вход по номеру телефона, в приложении — код-пароль или биометрия.",
        "send" to "Помощнику данные уходят только после вашего согласия. Его можно отозвать в профиле — прошлые ответы останутся.",
        "trash" to "Удалить одну загрузку можно в «Истории загрузок», весь аккаунт с данными — в профиле. Удаление необратимо.",
    )
    Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s3)) {
        points.forEach { (icon, text) ->
            Row(verticalAlignment = Alignment.Top) {
                ru.finassist.pf.core.designsystem.components.IconTile(icon, small = true)
                Spacer(Modifier.width(PfSpace.s3))
                Text(text, style = PfTheme.type.body, color = c.text, modifier = Modifier.weight(1f))
            }
        }
    }
}
