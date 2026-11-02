package ru.finassist.pf.feature.statements.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.designsystem.components.ErrorMessage
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfIconButton
import ru.finassist.pf.core.designsystem.components.PfTextField
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.statements.impl.domain.DownloadPeriod

@Composable
internal fun DownloadPeriodSheet(state: UploadUiState, onSelect: (LocalDate?, LocalDate?) -> Unit, onDownload: () -> Unit, onDismiss: () -> Unit) {
    val today = state.periodToday ?: return
    val from = state.periodFrom
    val to = state.periodTo
    var month by remember { mutableStateOf(YearMonth.from(to ?: from ?: today)) }
    var fromText by remember { mutableStateOf(from?.toString().orEmpty()) }
    var toText by remember { mutableStateOf(to?.toString().orEmpty()) }
    val enabled = !state.periodDownloading
    val d = PfTheme.dimens
    val colors = PfTheme.colors
    PfBottomSheet("Скачать за период", onDismiss, modifier = Modifier.testTag("statements.download_period"), footer = {
        PfButton(if (state.periodDownloading) "Готовим ссылку…" else "Скачать", onClick = onDownload,
            enabled = enabled && state.periodRetryReady && from != null && to != null && DownloadPeriod(from, to).valid(today), block = true,
            modifier = Modifier.testTag("statements.download_period.confirm"))
    }) {
        Text("Выберите начало и конец периода. Обе даты входят в выписку", style = PfTheme.type.body, color = colors.textMuted)
        Spacer(Modifier.height(d.space4))
        Row(horizontalArrangement = Arrangement.spacedBy(d.space3)) {
            PfTextField(fromText, onValueChange = { fromText = it; val date = parseDownloadDay(it); onSelect(date, to); if (date != null) month = YearMonth.from(date) },
                label = "С (ГГГГ-ММ-ДД)", enabled = enabled, modifier = Modifier.weight(1f).testTag("statements.download_period.from"))
            PfTextField(toText, onValueChange = { toText = it; val date = parseDownloadDay(it); onSelect(from, date); if (date != null) month = YearMonth.from(date) },
                label = "По (ГГГГ-ММ-ДД)", enabled = enabled, modifier = Modifier.weight(1f).testTag("statements.download_period.to"))
        }
        Spacer(Modifier.height(d.space4))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PfIconButton(PfIcons.CHEVRON_LEFT, "Предыдущий месяц", { month = month.minusMonths(1) }, enabled = enabled && month > YearMonth.of(1, 1), modifier = Modifier.testTag("statements.download_period.previous"))
            Text(RussianDates.monthTitle(month), style = PfTheme.type.bodyStrong, color = colors.text, modifier = Modifier.weight(1f))
            PfIconButton(PfIcons.CHEVRON_RIGHT, "Следующий месяц", { month = month.plusMonths(1) }, enabled = enabled && month < YearMonth.from(today), modifier = Modifier.testTag("statements.download_period.next"))
        }
        Row { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { day -> Box(Modifier.weight(1f).height(d.space8), contentAlignment = Alignment.Center) { Text(day, style = PfTheme.type.caption, color = colors.textMuted) } } }
        val first = month.atDay(1)
        val leading = first.dayOfWeek.value - 1
        val cells = ((leading + month.lengthOfMonth() + 6) / 7) * 7
        (0 until cells).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(vertical = d.space1)) {
                week.forEachIndexed { column, index ->
                    val day = index - leading + 1
                    val date = if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
                    val selectedDay = date != null && from != null && date >= from && date <= (to ?: from)
                    val start = selectedDay && (date == from || column == 0 || day == 1)
                    val end = selectedDay && (date == (to ?: from) || column == 6 || day == month.lengthOfMonth())
                    // Only the external ends of a row segment are rounded; neighbouring cells join without gaps.
                    val shape = RoundedCornerShape(topStart = if (start) d.radiusMd else 0.dp, bottomStart = if (start) d.radiusMd else 0.dp,
                        topEnd = if (end) d.radiusMd else 0.dp, bottomEnd = if (end) d.radiusMd else 0.dp)
                    Box(Modifier.weight(1f).height(d.touch)
                        .background(if (selectedDay) colors.accentSoft else colors.surface, shape)
                        .then(if (date == null) Modifier else Modifier.testTag("statements.download_period.day.$date").semantics {
                            selected = selectedDay; contentDescription = RussianDates.day(date, currentYear = 0)
                        }.clickable(enabled = enabled && date <= today, role = Role.Button) {
                            if (from == null || to != null || date < from) { fromText = date.toString(); toText = ""; onSelect(date, null) }
                            else { toText = date.toString(); onSelect(from, date) }
                        }), contentAlignment = Alignment.Center) {
                        if (date != null) Text(day.toString(), style = if (date == from || date == to) PfTheme.type.bodyStrong else PfTheme.type.body,
                            color = when { date > today -> colors.textFaint; selectedDay -> colors.accent; else -> colors.text })
                    }
                }
            }
        }
        state.periodError?.let { ErrorMessage(it, "Не получилось подготовить ссылку. Проверьте даты и попробуйте ещё раз") }
    }
}

internal fun parseDownloadDay(raw: String): LocalDate? = runCatching { LocalDate.parse(raw) }.getOrNull()
    ?.takeIf { raw.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && it.year in 1..9999 }
