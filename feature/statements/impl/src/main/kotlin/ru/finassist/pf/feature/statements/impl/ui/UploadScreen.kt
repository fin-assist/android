package ru.finassist.pf.feature.statements.impl.ui

import ru.finassist.pf.core.designsystem.components.ErrorMessage
import ru.finassist.pf.core.designsystem.theme.PfInsets
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import ru.finassist.pf.feature.statements.impl.domain.ImportErrorTexts
import ru.finassist.pf.core.designsystem.components.TextPart
import ru.finassist.pf.core.designsystem.components.PfDivider
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.common.Support
import ru.finassist.pf.core.common.LegalLinks
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
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
    onOpenHistory: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
    // While parsing, «back» asks whether to interrupt instead of leaving the screen silently.
    BackHandler(enabled = state.phase is UploadPhase.Busy) { vm.askCancel(true) }

    UploadContent(
        state = state,
        firstRun = firstRun,
        onBack = onBack,
        onLater = onLater,
        onRetry = vm::load,
        onShowFallback = vm::showFallback,
        onOpenUrl = { url -> openUrl(context, url) },
        onPick = { picker.launch(arrayOf("*/*")) },
        onPickAnother = { vm.backToGuide(); picker.launch(arrayOf("*/*")) },
        onBackToGuide = vm::backToGuide,
        onAskCancel = vm::askCancel,
        onCancelImport = vm::cancelImport,
        onRetryUpload = vm::retryUpload,
        onSupport = { writeToSupport(context, "Не получилось загрузить выписку") },
        onStartNewUpload = vm::startNewUpload,
        onOpenHistory = onOpenHistory,
    )
}

/** Stateless body of [UploadScreen]: everything it draws for [state], the cancel dialog and the fallback sheet included. */
@Composable
internal fun UploadContent(
    state: UploadUiState,
    firstRun: Boolean,
    onBack: () -> Unit,
    onLater: () -> Unit,
    onRetry: () -> Unit,
    onShowFallback: (Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
    onPick: () -> Unit,
    onPickAnother: () -> Unit,
    onBackToGuide: () -> Unit,
    onAskCancel: (Boolean) -> Unit,
    onCancelImport: () -> Unit,
    onRetryUpload: () -> Unit,
    onSupport: () -> Unit,
    onStartNewUpload: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
) {
    val d = PfTheme.dimens
    val busy = state.phase as? UploadPhase.Busy
    val error = state.phase as? UploadPhase.Error

    Column(
        Modifier
            .testTag(StatementsTags.UPLOAD)
            .fillMaxSize()
            .windowInsetsPadding(PfInsets.navigationBars),
    ) {
        if (error != null) {
            // The error screen has no header (mockup `ImportError`).
            ErrorBlock(error, onPickAnother = onPickAnother, onGuide = onBackToGuide, onSupport = onSupport)
            return@Column
        }
        PfPageHeader(
            title = "Загрузка выписки",
            // While a file is parsed «back» asks whether to interrupt (mockup `ImportGuideBusyCancel`); on the first
            // run it leaves for the app, as «Загружу позже».
            onBack = when {
                busy != null -> ({ onAskCancel(true) })
                firstRun -> onLater
                else -> onBack
            },
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = d.space5)
                .padding(top = d.space4, bottom = d.space4),
        ) {
            state.guideErrorPresentation?.let {
                ErrorMessage(it, "Не получилось обновить условия загрузки. Используем сохранённые условия", modifier = Modifier.padding(horizontal = d.space5))
            }
            when (val phase = state.phase) {
                UploadPhase.Loading -> Unit
                // First run has no back arrow: these states must offer a way on.
                UploadPhase.Disabled -> PfEmptyState(PfIcons.FILE_TEXT, "Загрузка выписки временно недоступна", "Попробуйте позже — операции и аналитика работают как обычно") {
                    if (firstRun) PfButton("Продолжить", onClick = onLater, variant = ButtonVariant.PRIMARY)
                }
                UploadPhase.Offline -> PfEmptyState(PfIcons.ALERT, "Нет сети", "Проверьте интернет и попробуйте ещё раз", modifier = Modifier.testTag(StatementsTags.UPLOAD_OFFLINE)) {
                    PfButton("Повторить", onClick = onRetry, variant = ButtonVariant.PRIMARY, modifier = Modifier.testTag(StatementsTags.UPLOAD_RETRY))
                    if (firstRun) PfLink("Загружу позже", onClick = onLater, modifier = Modifier.testTag(StatementsTags.UPLOAD_LATER))
                }
                is UploadPhase.Guide, is UploadPhase.Busy -> Guide(
                    config = state.config ?: (phase as? UploadPhase.Guide)?.config ?: return@Column,
                    busy = busy,
                    offline = state.uploadOffline,
                    retryEnabled = state.uploadRetryReady,
                    canSkip = firstRun || (state.config ?: (phase as? UploadPhase.Guide)?.config)?.loaded == null,
                    onShowFallback = { onShowFallback(true) },
                    onDownload = { url -> onOpenUrl(url) },
                    onPick = onPick,
                    onRetryUpload = onRetryUpload,
                    onLater = if (firstRun) onLater else onBack,
                    onPrivacy = { onOpenUrl(LegalLinks.PRIVACY) },
                )
                is UploadPhase.RecoveryRequired -> Column(Modifier.testTag(StatementsTags.UPLOAD_RECOVERY), verticalArrangement = Arrangement.spacedBy(d.space4)) {
                    PfNotice("Результат предыдущей загрузки неизвестен: файл мог уже загрузиться. Проверьте историю. Новая загрузка создаст отдельную запись.", tone = NoticeTone.WARNING)
                    FileRow("Файл", phase.fileName, divider = false)
                    PfButton("Загрузить заново", onClick = onStartNewUpload, block = true, modifier = Modifier.testTag(StatementsTags.UPLOAD_NEW_INTENT))
                    PfLink("Проверить историю загрузок", onClick = onOpenHistory)
                }
                is UploadPhase.Error -> Unit
            }
        }
    }
    if (state.fallbackOpen) FallbackSheet(onDismiss = { onShowFallback(false) })
    if (state.askCancel) {
        PfDialog(
            title = "Прервать разбор?",
            description = "Операции из файла ${busy?.fileName ?: "выписки"} не сохранятся. Файл останется на телефоне — его можно загрузить снова.",
            confirmText = "Прервать",
            onConfirm = onCancelImport,
            onDismiss = { onAskCancel(false) },
            busy = busy?.cancelling == true,
            busyText = "Прерываем…",
            cancelText = "Продолжить разбор",
        )
    }
}

/**
 * The guide (mockups `ImportGuide*`): what is loaded now, how to download the statement, the file and the privacy
 * notice. While a file is parsed only the file area changes; a file not sent for lack of network gets a notice
 * with «Повторить» above it.
 */
@Composable
private fun ColumnScope.Guide(
    config: ImportConfig,
    busy: UploadPhase.Busy?,
    offline: Boolean,
    retryEnabled: Boolean = true,
    canSkip: Boolean,
    onShowFallback: () -> Unit,
    onDownload: (String) -> Unit,
    onPick: () -> Unit,
    onRetryUpload: () -> Unit,
    onLater: () -> Unit,
    onPrivacy: () -> Unit,
) {
    val d = PfTheme.dimens
    val c = PfTheme.colors
    config.loaded?.let { loaded ->
        PfCard(Modifier.testTag(StatementsTags.UPLOAD_LOADED)) {
            Text("Сейчас загружено", style = PfTheme.type.caption, color = c.textMuted)
            Text(
                "${RussianDates.dayRange(loaded.firstOperationAt.toLocalDate(), loaded.lastOperationAt.toLocalDate(), withYear = true)} · " +
                    countWithNoun(loaded.operationCount, "операция", "операции", "операций"),
                style = PfTheme.type.bodyStrong.copy(fontFeatureSettings = "tnum"), color = c.text,
                modifier = Modifier.padding(top = d.space1),
            )
            Text(
                "Кнопка «Скачать за год» захватит и прошлые месяцы — сравнивать станет с чем. Совпадающие операции не задвоятся",
                style = PfTheme.type.caption, color = c.textMuted, modifier = Modifier.padding(top = d.space2),
            )
        }
        Spacer(Modifier.height(d.space4))
    }
    PfCard {
        Text("Как скачать выписку", style = PfTheme.type.bodyStrong, color = c.text, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(d.space3))
        PfOrderedSteps(
            listOf(
                "Войдите в кабинет Т-Банка в браузере",
                "Нажмите «Скачать за год» — файл сохранится в «Загрузки»",
                "Выберите скачанный файл ниже",
            ),
        )
        Spacer(Modifier.height(d.space2))
        Row(horizontalArrangement = Arrangement.spacedBy(d.space3)) {
            PfButton(
                "Войти в Т-Банк", onClick = { onDownload(config.loginUrl) }, variant = ButtonVariant.SECONDARY, block = true,
                modifier = Modifier.weight(1f).testTag(StatementsTags.UPLOAD_LOGIN),
            )
            PfButton(
                "Скачать за год", onClick = { onDownload(config.downloadUrl) }, variant = ButtonVariant.SECONDARY, block = true,
                modifier = Modifier.weight(1f).testTag(StatementsTags.UPLOAD_DOWNLOAD),
            )
        }
        Spacer(Modifier.height(d.space1))
        PfLink("Не скачивается?", onClick = onShowFallback, modifier = Modifier.testTag(StatementsTags.UPLOAD_FALLBACK))
    }
    if (offline) {
        Spacer(Modifier.height(d.space4))
        PfNotice(
            "Нет связи с интернетом — файл не загружен",
            tone = NoticeTone.WARNING,
            alert = true,
            modifier = Modifier.testTag(StatementsTags.UPLOAD_SEND_OFFLINE),
            action = {
                Box(Modifier.padding(top = d.space2)) {
                    PfButton("Повторить", onClick = onRetryUpload, enabled = retryEnabled, variant = ButtonVariant.SECONDARY, modifier = Modifier.testTag(StatementsTags.UPLOAD_SEND_RETRY))
                }
            },
        )
    }
    Spacer(Modifier.height(d.space4))
    if (busy != null) {
        PfFileDrop(onPick = {}, busyFileName = busy.fileName, progress = busy.progress, modifier = Modifier.testTag(StatementsTags.UPLOAD_BUSY))
    } else {
        PfFileDrop(
            onPick = onPick,
            hint = "Файл ${config.acceptedFormats.joinToString(" / ")} до ${config.maxFileSizeBytes / (1024 * 1024)} МБ",
            modifier = Modifier.testTag(StatementsTags.UPLOAD_PICK),
        )
    }
    if (canSkip && busy == null) {
        Spacer(Modifier.height(d.space2))
        PfLink("Загружу позже", onClick = onLater, modifier = Modifier.align(Alignment.CenterHorizontally).testTag(StatementsTags.UPLOAD_LATER))
    }
    Spacer(Modifier.height(d.space4))
    val privacy = "Операции видите только вы. Файл выписки храним зашифрованным — поддержка открывает его, только чтобы " +
        "исправить ошибку разбора. Помощнику данные уходят с вашего согласия. Удалить загрузку или аккаунт — в профиле. "
    PfNotice(
        privacy,
        tone = NoticeTone.INFO,
        parts = listOf(TextPart.Plain(privacy), TextPart.Link("Как мы защищаем данные", StatementsTags.UPLOAD_PRIVACY, onPrivacy)),
    )
}

/** «Не скачивается?» (mockup `ImportGuideFallback`): manual export in the bank's web office. */
@Composable
private fun FallbackSheet(onDismiss: () -> Unit) {
    val d = PfTheme.dimens
    PfBottomSheet("Если файл не скачивается", onDismiss = onDismiss) {
        Text(
            "Скачайте выписку вручную в кабинете Т-Банка",
            style = PfTheme.type.body, color = PfTheme.colors.textMuted,
            modifier = Modifier.padding(top = d.space1, bottom = d.space3),
        )
        PfOrderedSteps(
            listOf(
                "Откройте tbank.ru в браузере и войдите. На телефоне включите в меню браузера «Версия для ПК»",
                "Откройте «Операции» и выберите период — лучше год",
                "Нажмите значок загрузки и выберите «Скачать в OFX». Счета — все, «Без переводов» не включайте",
                "Выберите скачанный файл в нашем приложении",
            ),
        )
        Spacer(Modifier.height(d.space4))
        PfButton("Понятно", onClick = onDismiss, variant = ButtonVariant.SECONDARY, block = true, modifier = Modifier.testTag(StatementsTags.UPLOAD_FALLBACK_OK))
    }
}

/**
 * A rejected file (mockups `ImportError*`): no header; the hero, «Не получилось прочитать файл» and the reason,
 * the file against what is needed, what to do; the actions at the bottom.
 */
@Composable
private fun ColumnScope.ErrorBlock(phase: UploadPhase.Error, onPickAnother: () -> Unit, onGuide: () -> Unit, onSupport: () -> Unit) {
    val d = PfTheme.dimens
    val c = PfTheme.colors
    val text = ImportErrorTexts.of(phase.code, phase.fileName, phase.config?.maxFileSizeBytes ?: (10L * 1024 * 1024))
    BoxWithConstraints(Modifier.weight(1f).windowInsetsPadding(PfInsets.statusBars)) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(start = d.space5, end = d.space5, top = d.space6, bottom = d.space4)
                .testTag(StatementsTags.uploadError(phase.code)),
        ) {
            PfStatusHero(tone = HeroTone.WARNING, icon = PfIcons.ALERT, centered = false)
            Spacer(Modifier.height(d.space5))
            Text("Не получилось прочитать файл", style = PfTheme.type.title1, color = c.text, modifier = Modifier.semantics { heading() })
            val presentation = phase.presentation
            if (presentation != null) ErrorMessage(presentation, text.reason, modifier = Modifier.padding(top = d.space1))
            else Text(text.reason, style = PfTheme.type.lead, color = c.textMuted, modifier = Modifier.padding(top = d.space1))
            Spacer(Modifier.height(d.space5))
            PfCard(flush = true) {
                FileRow("Файл", phase.fileName, divider = true)
                FileRow("Нужен", "OFX из Т-Банка", divider = false)
            }
            Spacer(Modifier.height(d.space6))
            Text("Что сделать", style = PfTheme.type.bodyStrong, color = c.text, modifier = Modifier.semantics { heading() })
            Text(text.advice, style = PfTheme.type.body, color = c.text, modifier = Modifier.padding(top = d.space1))
            Text(
                "Ничего не загрузили — данные не изменились",
                style = PfTheme.type.caption, color = c.textMuted, modifier = Modifier.padding(top = d.space4),
            )
            Spacer(Modifier.weight(1f).heightIn(min = d.space6))
            PfButton("Выбрать другой файл", onClick = onPickAnother, variant = ButtonVariant.PRIMARY, block = true, modifier = Modifier.testTag(StatementsTags.UPLOAD_PICK_ANOTHER))
            Column(Modifier.fillMaxWidth().padding(top = d.space2), horizontalAlignment = Alignment.CenterHorizontally) {
                PfLink("Как скачать выписку", onClick = onGuide, modifier = Modifier.testTag(StatementsTags.UPLOAD_GUIDE))
                PfLink("Написать в поддержку", onClick = onSupport, modifier = Modifier.testTag(StatementsTags.UPLOAD_SUPPORT))
            }
        }
    }
}

/** A `body` row of the error card: muted label, value at the end (breaks anywhere — file names are long). */
@Composable
private fun FileRow(label: String, value: String, divider: Boolean) {
    val d = PfTheme.dimens
    Column {
        // `.pf-card-flush { padding: 0 16px }`: the row and its divider keep 16 dp from the card's frame.
        Row(Modifier.fillMaxWidth().padding(horizontal = d.space4, vertical = d.space3), horizontalArrangement = Arrangement.spacedBy(d.space4)) {
            Text(label, style = PfTheme.type.body, color = PfTheme.colors.textMuted)
            Text(value, style = PfTheme.type.body, color = PfTheme.colors.text, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        if (divider) PfDivider(Modifier.padding(horizontal = d.space4))
    }
}

/** Opens the mail app with a letter to support; nothing happens without one. */
private fun writeToSupport(context: android.content.Context, subject: String) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(Support.EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    runCatching { context.startActivity(intent) }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url)) }
        .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/** Test tags of the statement screens (UI tests in `.maestro/`, convention in docs/e2e.md). */
internal object StatementsTags {
    const val UPLOAD_RECOVERY = "statements.upload.recovery"
    const val UPLOAD_NEW_INTENT = "statements.upload.new_intent"
    const val UPLOAD = "statements.upload"
    const val UPLOAD_PICK = "statements.upload.pick"
    const val UPLOAD_LATER = "statements.upload.later"
    const val UPLOAD_OFFLINE = "statements.upload.offline"
    const val UPLOAD_RETRY = "statements.upload.retry"
    const val UPLOAD_BUSY = "statements.upload.busy"
    const val UPLOAD_CANCEL = "statements.upload.cancel"
    const val UPLOAD_PICK_ANOTHER = "statements.upload.pick_another"
    const val UPLOAD_GUIDE = "statements.upload.guide"
    const val UPLOAD_SUPPORT = "statements.upload.support"
    const val UPLOAD_LOADED = "statements.upload.loaded"
    const val UPLOAD_LOGIN = "statements.upload.login"
    const val UPLOAD_DOWNLOAD = "statements.upload.download"
    const val UPLOAD_FALLBACK = "statements.upload.fallback"
    const val UPLOAD_FALLBACK_OK = "statements.upload.fallback.ok"
    const val UPLOAD_PRIVACY = "statements.upload.privacy"
    const val UPLOAD_SEND_OFFLINE = "statements.upload.send_offline"
    const val UPLOAD_SEND_RETRY = "statements.upload.send_retry"
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
    /** A special case of the import the client does not know: the server's text (FIN-83). */
    const val RESULT_NOTICE = "statements.result.notice"
    const val RESULT_UPLOAD_MORE = "statements.result.upload_more"
    const val RESULT_HISTORY = "statements.result.history"
    const val RESULT_UNCATEGORIZED = "statements.result.uncategorized"
    const val RESULT_DONE = "statements.result.done"

    const val HISTORY = "statements.history"
    const val HISTORY_EMPTY = "statements.history.empty"
    const val HISTORY_ROW = "statements.history.row"
    const val HISTORY_DELETE = "statements.history.delete"
    const val HISTORY_UNREAD = "statements.history.unread"
    const val HISTORY_RESOLVED = "statements.history.resolved"
    const val HISTORY_UPLOAD = "statements.history.upload"

    const val UNREAD = "statements.unread"
    const val UNREAD_REPORT = "statements.unread.report"
}
