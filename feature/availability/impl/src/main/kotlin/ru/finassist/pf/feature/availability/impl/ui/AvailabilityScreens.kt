package ru.finassist.pf.feature.availability.impl.ui

import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.UserMessage
import ru.finassist.pf.core.designsystem.components.ErrorMessage
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.finassist.pf.core.common.Support
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.time.RussianDates
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.HeroTone
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfStatusHero
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.LocalPfClock
import ru.finassist.pf.core.designsystem.theme.PfInsets
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.availability.api.AvailabilityTags
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * The update and maintenance screens the app root lays over everything by the state of [ServiceGate] (FIN-22). Injected
 * as an interface, like the app lock, so the shell does not reference screen functions of this module.
 */
interface AvailabilityScreens {
    /** Nothing while the gate is open; otherwise the screen of its state. */
    @Composable
    fun Overlay()
}

internal class AvailabilityScreensImpl @Inject constructor() : AvailabilityScreens {
    @Composable
    override fun Overlay() {
        val vm: AvailabilityViewModel = hiltViewModel()
        val state by vm.gate.state.collectAsStateWithLifecycle()
        val checking by vm.checking.collectAsStateWithLifecycle()
        val context = LocalContext.current
        when (val s = state) {
            ServiceGate.State.Open -> Unit
            is ServiceGate.State.UpdateRequired -> UpdateContent(
                onUpdate = { openAppPage(context) },
                presentation = s.presentation,
                onSupport = { writeToSupport(context, "Обновление приложения") },
            )
            is ServiceGate.State.Maintenance -> MaintenanceContent(
                until = s.until,
                presentation = s.presentation,
                checking = checking,
                onCheck = vm::checkNow,
                onSupport = { writeToSupport(context, "Технические работы") },
            )
        }
    }
}

/** Mockup `ForceUpdate`: the store is not named — the app is distributed through more than one. */
@Composable
internal fun UpdateContent(onUpdate: () -> Unit, onSupport: () -> Unit, presentation: ErrorPresentation = ErrorPresentation()) = GateScreen(
    presentation = presentation,
    tag = AvailabilityTags.UPDATE,
    tone = HeroTone.INFO,
    icon = PfIcons.DOWNLOAD,
    title = "Обновите приложение",
    subtitle = "Эта версия больше не поддерживается",
    subtitleTag = null,
    head = "Что сделать",
    body = "Установите новую версию. Выписки, операции и настройки сохранятся — входить заново не придётся.",
    action = { PfButton("Обновить", onClick = onUpdate, variant = ButtonVariant.PRIMARY, block = true, modifier = Modifier.testTag(AvailabilityTags.UPDATE_BUTTON)) },
    support = { PfLink("Написать в поддержку", onClick = onSupport, modifier = Modifier.testTag(AvailabilityTags.UPDATE_SUPPORT)) },
)

/** Mockups `Maintenance` and `MaintenanceNoTime` (without `error.until`). */
@Composable
internal fun MaintenanceContent(until: OffsetDateTime?, checking: Boolean, onCheck: () -> Unit, onSupport: () -> Unit, presentation: ErrorPresentation = ErrorPresentation()) = GateScreen(
    presentation = presentation,
    tag = AvailabilityTags.MAINTENANCE,
    tone = HeroTone.WARNING,
    icon = PfIcons.CLOCK,
    title = "Идут технические работы",
    subtitle = LocalPfClock.current.let { untilText(until, it.now(), it.zone) },
    subtitleTag = AvailabilityTags.MAINTENANCE_UNTIL,
    head = "Ваши данные в сохранности",
    body = "Приложение само проверит, закончились ли работы, и откроется. Держать его открытым не нужно.",
    action = {
        PfButton(
            "Проверить сейчас", onClick = onCheck, variant = ButtonVariant.SECONDARY, block = true, busy = checking,
            modifier = Modifier.testTag(AvailabilityTags.MAINTENANCE_CHECK),
        )
    },
    support = { PfLink("Написать в поддержку", onClick = onSupport, modifier = Modifier.testTag(AvailabilityTags.MAINTENANCE_SUPPORT)) },
)

/** «Закончим примерно к 14:30»; another day — «Закончим примерно 16 октября к 14:30»; unknown — no promise. */
internal fun untilText(until: OffsetDateTime?, now: OffsetDateTime, zone: ZoneId): String {
    if (until == null) return "Пока не знаем, когда закончим"
    // In the zone of the shown clock (the profile's), not the offset of «now»: the end may be past a DST change.
    val local = until.atZoneSameInstant(zone)
    val today = now.atZoneSameInstant(zone).toLocalDate()
    val time = local.format(DateTimeFormatter.ofPattern("HH:mm"))
    return if (local.toLocalDate() == today) "Закончим примерно к $time"
    else "Закончим примерно ${RussianDates.day(local.toLocalDate(), today.year)} к $time"
}

/** Layout of the mockups: hero, title and subtitle, a short paragraph; the action and the support link at the bottom. */
@Composable
private fun GateScreen(
    presentation: ErrorPresentation,
    tag: String,
    tone: HeroTone,
    icon: String,
    title: String,
    subtitle: String,
    subtitleTag: String?,
    head: String,
    body: String,
    action: @Composable () -> Unit,
    support: @Composable () -> Unit,
) {
    val d = PfTheme.dimens
    val c = PfTheme.colors
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg).windowInsetsPadding(PfInsets.statusBars).windowInsetsPadding(PfInsets.navigationBars)) {
        val viewport = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewport)
                .padding(start = d.space5, end = d.space5, top = d.space6, bottom = d.space4)
                .testTag(tag),
        ) {
            PfStatusHero(tone = tone, icon = icon, centered = false)
            Spacer(Modifier.height(d.space5))
            Text(title, style = PfTheme.type.title1, color = c.text, modifier = Modifier.semantics { heading() })
            Text(
                subtitle, style = PfTheme.type.lead, color = c.textMuted,
                modifier = Modifier.padding(top = d.space1).then(if (subtitleTag != null) Modifier.testTag(subtitleTag) else Modifier),
            )
            if (presentation.userMessage != UserMessage.Absent) {
                Spacer(Modifier.height(d.space3))
                ErrorMessage(presentation, subtitle)
            }
            Spacer(Modifier.height(d.space6))
            Text(head, style = PfTheme.type.bodyStrong, color = c.text, modifier = Modifier.semantics { heading() })
            Text(body, style = PfTheme.type.body, color = c.text, modifier = Modifier.padding(top = d.space1))
            Spacer(Modifier.weight(1f).heightIn(min = d.space6))
            action()
            Column(Modifier.fillMaxWidth().padding(top = d.space2), horizontalAlignment = Alignment.CenterHorizontally) { support() }
        }
    }
}

/** «Обновить»: [appPageIntent]. */
private fun openAppPage(context: Context) {
    runCatching { context.startActivity(appPageIntent(context)) }
}

/**
 * The app's page in the store that handles `market://` — RuStore, Google Play or another; when no installed app
 * handles it, the site. (Seeing the stores needs the `market` scheme in `<queries>` of the manifest.)
 */
internal fun appPageIntent(context: Context): Intent {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${context.packageName}"))
    val target = if (market.resolveActivity(context.packageManager) != null) market else Intent(Intent.ACTION_VIEW, Uri.parse(SITE))
    return target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun writeToSupport(context: Context, subject: String) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(Support.EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    runCatching { context.startActivity(intent) }
}

private const val SITE = "https://plainfin.ru"
