package ru.finassist.pf.feature.statements.impl.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import ru.finassist.pf.core.designsystem.charts.StatTile
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.Notice
import ru.finassist.pf.core.designsystem.components.NoticeTone
import ru.finassist.pf.core.designsystem.components.PageHeader
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.PfIcon
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.components.SectionTitle
import ru.finassist.pf.core.designsystem.components.StatusHero
import ru.finassist.pf.core.designsystem.components.StatusTone
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.navigation.OperationsFilter
import ru.finassist.pf.core.network.dto.ImportResultDto
import ru.finassist.pf.feature.statements.impl.domain.ImportFailure
import ru.finassist.pf.feature.statements.impl.domain.ImportResultTexts

/** Outcome of an import (mockups ImportResult*: first / repeat / short / unlocked / noExpenses / nothingNew / empty). */
@Composable
fun ImportResultScreen(
    result: ImportResultDto,
    fileName: String,
    uploadId: String?,
    onAnalytics: () -> Unit,
    onOperations: () -> Unit,
    onUncategorized: (OperationsFilter) -> Unit,
    onHistory: () -> Unit,
    onUnreadLines: () -> Unit,
    onAnotherFile: () -> Unit,
    onGuide: () -> Unit,
    onBack: () -> Unit,
) {
    val c = PfTheme.colors
    val t = ImportResultTexts(result)
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageHeader(title = "Итог загрузки", onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = PfSpace.s2),
            verticalArrangement = Arrangement.spacedBy(PfSpace.s5),
        ) {
            StatusHero(
                icon = if (t.empty) "info" else "check-circle",
                tone = if (t.empty || t.nothingNew) StatusTone.Info else StatusTone.Positive,
                title = t.heading,
                text = if (t.empty) "За выбранный период операций не нашлось — выгрузите выписку за другой период" else t.subtitle,
            )
            when {
                t.empty -> {
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                        PfButton("Выбрать другой файл", onClick = onAnotherFile, variant = ButtonVariant.Primary, block = true)
                        PfButton("Как скачать выписку", onClick = onGuide, block = true)
                    }
                }
                t.nothingNew -> {
                    Notice(t.nothingNewText, tone = NoticeTone.Info)
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                        PfButton("Посмотреть аналитику", onClick = onAnalytics, variant = ButtonVariant.Primary, block = true)
                        PfButton("История загрузок", onClick = onHistory, block = true)
                    }
                }
                else -> {
                    t.unreadText?.let { text ->
                        Notice(text, tone = NoticeTone.Warning, action = { PfLink("Подробнее", onClick = onUnreadLines, inline = true) })
                    }
                    if (t.totalsPeriod != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                            Text(t.totalsPeriod!!, style = PfTheme.type.caption, color = c.textMuted)
                            Row(horizontalArrangement = Arrangement.spacedBy(PfSpace.s3)) {
                                StatTile("Расходы", t.expenseText, modifier = Modifier.weight(1f), introId = "import.expense.$uploadId")
                                StatTile("Доходы", t.incomeText, modifier = Modifier.weight(1f), introId = "import.income.$uploadId")
                            }
                        }
                    }
                    if (t.noExpenses) {
                        Notice("В выписке нет расходов — возможно, выгружен не тот счёт", tone = NoticeTone.Warning, action = { PfLink("Выбрать другой файл", onClick = onAnotherFile, inline = true) })
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                        SectionTitle("Что мы нашли")
                        PfCard {
                            Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s3)) {
                                t.duplicatesText?.let { Found("repeat", it) }
                                t.accountsText?.let { Found("landmark", it) }
                                Found("tag", t.categorizedText)
                                t.transfersText?.let { Found("transfer", it) }
                                t.uncategorizedText?.let { text ->
                                    val filter = result.uncategorizedFilters?.let { f -> OperationsFilter(selection = f.selection, selectionName = f.selectionName) } ?: OperationsFilter()
                                    Row(verticalAlignment = Alignment.Top) {
                                        PfIcon("search", size = PfSize.iconMd, tint = c.accent)
                                        Spacer(Modifier.width(PfSpace.s3))
                                        Column(Modifier.weight(1f)) {
                                            Text(buildAnnotatedString {
                                                append(text)
                                                withStyle(SpanStyle(color = c.accent, fontWeight = FontWeight.SemiBold)) { append("разобрать") }
                                            }, style = PfTheme.type.body, color = c.text, modifier = Modifier.clickableText { onUncategorized(filter) })
                                            Text("Операции без категории входят в суммы", style = PfTheme.type.hint, color = c.textMuted)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    t.coverageText?.let { text ->
                        Notice(text, tone = NoticeTone.Info, action = if (t.suggestMoreMonths) ({ PfLink("Загрузить прошлые месяцы", onClick = onGuide, inline = true) }) else null)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                        PfButton("Посмотреть аналитику", onClick = onAnalytics, variant = ButtonVariant.Primary, block = true)
                        PfButton("Перейти к операциям", onClick = onOperations, block = true)
                        PfLink("История загрузок", onClick = onHistory, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
            Spacer(Modifier.height(PfSpace.s6))
        }
    }
}

@Composable
private fun Found(icon: String, text: String) {
    val c = PfTheme.colors
    Row(verticalAlignment = Alignment.Top) {
        PfIcon(icon, size = PfSize.iconMd, tint = c.accent)
        Spacer(Modifier.width(PfSpace.s3))
        Text(text, style = PfTheme.type.body, color = c.text, modifier = Modifier.weight(1f))
    }
}

private fun Modifier.clickableText(onClick: () -> Unit): Modifier = clickable(role = Role.Button, onClick = onClick)

/** Failure of the synchronous checks or of parsing (mockup ImportError*). Nothing was saved. */
@Composable
fun ImportErrorScreen(
    failure: ImportFailure,
    fileName: String,
    onAnotherFile: () -> Unit,
    onGuide: () -> Unit,
    onBack: () -> Unit,
) {
    val c = PfTheme.colors
    val context = LocalContext.current
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageHeader(title = "Ошибка загрузки", onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = PfSpace.s2),
            verticalArrangement = Arrangement.spacedBy(PfSpace.s5),
        ) {
            StatusHero(icon = "alert-triangle", tone = StatusTone.Warning, title = "Не получилось прочитать файл", text = failure.reason)
            PfCard {
                Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s3)) {
                    Labelled("Файл", fileName)
                    Labelled("Нужен", "OFX из Т-Банка")
                    Labelled("Что сделать", failure.advice)
                }
            }
            Notice("Ничего не загрузили — данные не изменились", tone = NoticeTone.Info)
            Column(verticalArrangement = Arrangement.spacedBy(PfSpace.s2)) {
                PfButton("Выбрать другой файл", onClick = onAnotherFile, variant = ButtonVariant.Primary, block = true)
                if (failure.showGuideLink) PfButton("Как скачать выписку", onClick = onGuide, block = true)
                PfLink("Написать в поддержку", onClick = { openSupportMail(context, "Ошибка загрузки выписки", "Файл: $fileName\nПричина: ${failure.reason}") }, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun Labelled(label: String, value: String) {
    val c = PfTheme.colors
    Column {
        Text(label, style = PfTheme.type.caption, color = c.textMuted)
        Text(value, style = PfTheme.type.body, color = c.text)
    }
}
