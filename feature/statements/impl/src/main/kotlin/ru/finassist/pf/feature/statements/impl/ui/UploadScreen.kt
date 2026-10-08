package ru.finassist.pf.feature.statements.impl.ui

import ru.finassist.pf.core.designsystem.theme.PfInsets
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.common.time.countWithNoun
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.HeroTone
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfDialog
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfFileDrop
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfNotice
import ru.finassist.pf.core.designsystem.components.PfOrderedSteps
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.components.PfStatusHero
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme

/**
 * «Загрузка выписки»: guide with the download link, file picker, progress and the error states
 * (format / CSV / bank / size / parsing). Mock flavor parses any OFX on the device.
 */
@Composable
fun UploadScreen(
    firstRun: Boolean,
    onBack: () -> Unit,
    onLater: () -> Unit,
    onDone: (uploadId: String) -> Unit,
    vm: UploadViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val d = PfTheme.dimens
    val picker = rememberLauncherForActivityResult(PickStatementDocument()) { uri -> if (uri != null) vm.onFilePicked(uri) }

    LaunchedEffect(state.doneUploadId) {
        val id = state.doneUploadId ?: return@LaunchedEffect
        vm.consumeDone()
        onDone(id)
    }
    LifecycleResumeEffect(Unit) {
        vm.watch()
        onPauseOrDispose { vm.stopWatching() }
    }
    val busy = state.phase as? UploadPhase.Busy
    // While parsing, «back» asks whether to interrupt instead of leaving the screen silently.
    BackHandler(enabled = busy != null) { vm.askCancel(true) }

    Column(
        Modifier
            .testTag(StatementsTags.UPLOAD)
            .fillMaxSize()
            .windowInsetsPadding(PfInsets.navigationBars),
    ) {
        PfPageHeader(
            title = if (firstRun) "Шаг 4 из 4" else "Загрузка выписки",
            onBack = if (firstRun || busy != null) null else onBack,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = d.space5)
                .padding(bottom = d.space4),
        ) {
            when (val phase = state.phase) {
                UploadPhase.Loading -> Unit
                // First run has no back arrow: these states must offer a way on.
                UploadPhase.Disabled -> PfEmptyState(PfIcons.FILE_TEXT, "Загрузка выписки временно недоступна", "Попробуйте позже — операции и аналитика работают как обычно") {
                    if (firstRun) PfButton("Продолжить", onClick = onLater, variant = ButtonVariant.PRIMARY)
                }
                UploadPhase.Offline -> PfEmptyState(PfIcons.ALERT, "Нет сети", "Проверьте интернет и попробуйте ещё раз", modifier = Modifier.testTag(StatementsTags.UPLOAD_OFFLINE)) {
                    PfButton("Повторить", onClick = vm::load, variant = ButtonVariant.PRIMARY, modifier = Modifier.testTag(StatementsTags.UPLOAD_RETRY))
                    if (firstRun) PfLink("Загружу позже", onClick = onLater, modifier = Modifier.testTag(StatementsTags.UPLOAD_LATER))
                }
                is UploadPhase.Guide -> Guide(
                    config = phase.config,
                    firstRun = firstRun,
                    fallbackOpen = state.fallbackOpen,
                    onToggleFallback = vm::toggleFallback,
                    onDownload = { openUrl(context, phase.config.downloadUrl) },
                    onLogin = { openUrl(context, phase.config.loginUrl) },
                    onPick = { picker.launch(arrayOf("*/*")) },
                    onLater = onLater,
                )
                is UploadPhase.Busy -> BusyBlock(phase, onCancel = { vm.askCancel(true) })
                is UploadPhase.Error -> ErrorBlock(phase, onPickAnother = { vm.backToGuide(); picker.launch(arrayOf("*/*")) }, onBack = vm::backToGuide)
            }
        }
    }
    if (state.askCancel) {
        PfDialog(
            title = "Прервать разбор?",
            description = "Ничего не сохраним — данные не изменятся. Файл можно загрузить снова.",
            confirmText = "Прервать",
            onConfirm = vm::cancelImport,
            onDismiss = { vm.askCancel(false) },
            busy = busy?.cancelling == true,
            busyText = "Прерываем…",
            cancelText = "Продолжить разбор",
        )
    }
}

@Composable
private fun ColumnScope.Guide(
    config: ImportConfig,
    firstRun: Boolean,
    fallbackOpen: Boolean,
    onToggleFallback: () -> Unit,
    onDownload: () -> Unit,
    onLogin: () -> Unit,
    onPick: () -> Unit,
    onLater: () -> Unit,
) {
    val d = PfTheme.dimens
    val c = PfTheme.colors
    val periodText = RussianDates.dayRange(config.suggestedPeriod.from.toLocalDate(), RussianDates.lastDayOf(config.suggestedPeriod))
    Text("Загрузите выписку Т-Банка", style = PfTheme.type.title1, color = c.text)
    Spacer(Modifier.height(d.space2))
    Text(
        if (config.loaded == null) "Разберём операции за год по категориям и покажем, куда уходят деньги"
        else "Догрузим операции с последней загрузки — повторы пропустим",
        style = PfTheme.type.lead, color = c.textMuted,
    )
    Spacer(Modifier.height(d.space5))
    PfOrderedSteps(
        listOf(
            "Нажмите «Скачать за год» — откроется Т-Банк. Войдите, и файл выписки за $periodText сохранится на телефон",
            "Вернитесь сюда и выберите этот файл",
        ),
    )
    Spacer(Modifier.height(d.space5))
    PfButton(if (config.loaded == null) "Скачать за год" else "Скачать с последней загрузки", onClick = onDownload, variant = ButtonVariant.PRIMARY, block = true, icon = PfIcons.DOWNLOAD)
    Spacer(Modifier.height(d.space2))
    PfLink(if (fallbackOpen) "Скрыть инструкцию" else "Не скачивается?", onClick = onToggleFallback, modifier = Modifier.align(Alignment.CenterHorizontally))
    if (fallbackOpen) {
        Spacer(Modifier.height(d.space2))
        PfCard {
            Text("Выгрузите вручную", style = PfTheme.type.bodyStrong, color = c.text)
            Spacer(Modifier.height(d.space3))
            PfOrderedSteps(
                listOf(
                    "Войдите в Т-Банк в браузере",
                    "Откройте «Операции» → «Выписка» и выберите период: $periodText",
                    "Формат — OFX. Сохраните файл и вернитесь сюда",
                ),
            )
            Spacer(Modifier.height(d.space3))
            PfButton("Войти в Т-Банк", onClick = onLogin, block = true)
        }
    }
    Spacer(Modifier.height(d.space6))
    PfFileDrop(onPick = onPick, hint = "Файл ${config.acceptedFormats.joinToString(" / ")} до ${config.maxFileSizeBytes / (1024 * 1024)} МБ", modifier = Modifier.testTag(StatementsTags.UPLOAD_PICK))
    config.loaded?.let { loaded ->
        Spacer(Modifier.height(d.space4))
        PfCard {
            Text("Сейчас загружено", style = PfTheme.type.caption, color = c.textMuted)
            Text(
                "${RussianDates.dayRange(loaded.firstOperationAt.toLocalDate(), loaded.lastOperationAt.toLocalDate())} · ${countWithNoun(loaded.operationCount, "операция", "операции", "операций")}",
                style = PfTheme.type.bodyStrong, color = c.text,
            )
        }
    }
    Spacer(Modifier.height(d.space4))
    PfNotice(
        "Файл выписки храним зашифрованным — он нужен только поддержке, если какие-то строки не прочитаются. Удаляется вместе с загрузкой и аккаунтом",
        tone = NoticeTone.INFO,
    )
    if (firstRun) {
        Spacer(Modifier.height(d.space4))
        PfLink("Загружу позже", onClick = onLater, modifier = Modifier.align(Alignment.CenterHorizontally).testTag(StatementsTags.UPLOAD_LATER))
    }
}

@Composable
private fun ColumnScope.BusyBlock(phase: UploadPhase.Busy, onCancel: () -> Unit) {
    val d = PfTheme.dimens
    Text("Разбираем выписку", style = PfTheme.type.title1, color = PfTheme.colors.text)
    Spacer(Modifier.height(d.space2))
    Text("Читаем операции, ищем повторы и проставляем категории. Обычно это меньше минуты", style = PfTheme.type.lead, color = PfTheme.colors.textMuted)
    Spacer(Modifier.height(d.space6))
    PfFileDrop(onPick = {}, busyFileName = phase.fileName, progress = phase.progress, modifier = Modifier.testTag(StatementsTags.UPLOAD_BUSY))
    Spacer(Modifier.height(d.space4))
    PfButton("Прервать", onClick = onCancel, variant = ButtonVariant.GHOST, block = true, enabled = !phase.cancelling, modifier = Modifier.testTag(StatementsTags.UPLOAD_CANCEL))
}

@Composable
private fun ColumnScope.ErrorBlock(phase: UploadPhase.Error, onPickAnother: () -> Unit, onBack: () -> Unit) {
    val d = PfTheme.dimens
    val (title, text) = when (phase.code) {
        ErrorCodes.CSV_NOT_ACCEPTED -> "Это CSV, а нужен OFX" to "В Т-Банке при выгрузке выберите формат OFX — в нём есть всё, что нужно для разбора. CSV мы не принимаем"
        ErrorCodes.WRONG_FORMAT -> "Не похоже на выписку" to "Файл «${phase.fileName}» — не OFX. Скачайте выписку в формате OFX из Т-Банка и выберите её"
        ErrorCodes.WRONG_BANK -> "Выписка другого банка" to "Пока принимаем только выписки Т-Банка. Другие банки добавим позже"
        ErrorCodes.FILE_TOO_LARGE -> "Файл больше ${(phase.config?.maxFileSizeBytes ?: (10L * 1024 * 1024)) / (1024 * 1024)} МБ" to "Выгрузите выписку за меньший период — например, по полгода"
        "OFFLINE" -> "Нет сети" to "Файл не отправился. Проверьте интернет и попробуйте ещё раз"
        else -> "Не удалось разобрать файл" to "Ничего не загрузили — данные не изменились. Попробуйте скачать выписку заново; если не поможет, напишите нам"
    }
    Spacer(Modifier.height(d.space8))
    PfStatusHero(tone = HeroTone.WARNING, title = title, subtitle = text, modifier = Modifier.testTag(StatementsTags.uploadError(phase.code)))
    Spacer(Modifier.height(d.space8))
    Column(verticalArrangement = Arrangement.spacedBy(d.space2), modifier = Modifier.fillMaxWidth()) {
        PfButton("Выбрать другой файл", onClick = onPickAnother, variant = ButtonVariant.PRIMARY, block = true, modifier = Modifier.testTag(StatementsTags.UPLOAD_PICK_ANOTHER))
        PfButton("К инструкции", onClick = onBack, variant = ButtonVariant.GHOST, block = true, modifier = Modifier.testTag(StatementsTags.UPLOAD_GUIDE))
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
        .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/** Test tags of the statement screens (UI tests in `.maestro/`, convention in docs/e2e.md). */
internal object StatementsTags {
    const val UPLOAD = "statements.upload"
    const val UPLOAD_PICK = "statements.upload.pick"
    const val UPLOAD_LATER = "statements.upload.later"
    const val UPLOAD_OFFLINE = "statements.upload.offline"
    const val UPLOAD_RETRY = "statements.upload.retry"
    const val UPLOAD_BUSY = "statements.upload.busy"
    const val UPLOAD_CANCEL = "statements.upload.cancel"
    const val UPLOAD_PICK_ANOTHER = "statements.upload.pick_another"
    const val UPLOAD_GUIDE = "statements.upload.guide"
    /** `statements.upload.error.csv_not_accepted`, `.wrong_format`, `.wrong_bank`, `.file_too_large`, … */
    fun uploadError(code: String) = "statements.upload.error.${code.lowercase()}"

    const val RESULT = "statements.result"
    /** Hero of the result: new operations added / nothing new (all duplicates) / empty statement. */
    const val RESULT_NEW = "statements.result.new"
    const val RESULT_NO_NEW = "statements.result.no_new"
    const val RESULT_EMPTY = "statements.result.empty"
    const val RESULT_UNREAD = "statements.result.unread"
    const val RESULT_ANALYTICS = "statements.result.analytics"
    const val RESULT_UPLOAD_ANOTHER = "statements.result.upload_another"
    const val RESULT_DONE = "statements.result.done"

    const val HISTORY = "statements.history"
    const val HISTORY_EMPTY = "statements.history.empty"
    const val HISTORY_ROW = "statements.history.row"
    const val HISTORY_DELETE = "statements.history.delete"
    const val HISTORY_UNREAD = "statements.history.unread"
    const val HISTORY_UPLOAD = "statements.history.upload"

    const val UNREAD = "statements.unread"
}
