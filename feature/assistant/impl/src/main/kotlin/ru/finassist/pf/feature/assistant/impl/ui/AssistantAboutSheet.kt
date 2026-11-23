package ru.finassist.pf.feature.assistant.impl.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.finassist.pf.core.api.RequestOwner
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.api.model.HelpTags
import ru.finassist.pf.core.designsystem.components.LocalSuppressPopups
import ru.finassist.pf.core.designsystem.components.PfBottomSheet
import ru.finassist.pf.core.designsystem.components.PfIconButton
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.PfListRow
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.icons.PfIcon
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.feature.auth.api.SessionState
import java.time.ZoneId

private const val ABOUT_TITLE = "О помощнике"
private val aboutTags = setOf(HelpTags.ONBOARDING, HelpTags.BOTTOM)

@Composable
internal fun AssistantChatHeader(
    state: ChatUiState,
    zone: ZoneId,
    onBack: () -> Unit,
    about: AssistantAboutViewModel?,
    initiallyOpen: Boolean,
    refreshLimit: (suspend () -> Unit)?,
) {
    val session = about?.session?.collectAsStateWithLifecycle(SessionState.Unknown)?.value as? SessionState.SignedIn
    val owner = remember(session) {
        session?.authSessionEpoch?.takeIf { it.isNotBlank() && session.userId.isNotBlank() }
            ?.let { RequestOwner(session.userId, it) }
    }
    var opened by rememberSaveable(session?.userId, session?.authSessionEpoch) { mutableStateOf(initiallyOpen) }
    val suppressed = LocalSuppressPopups.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    if (suppressed) {
        LaunchedEffect(Unit) { opened = false }
    } else if (owner != null && about != null && HelpTags.ONBOARDING in aboutTags) {
        LaunchedEffect(owner, about, lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                withFrameNanos { }
                if (about.claimOnboarding(owner)) opened = true
            }
        }
    }

    PfPageHeader("Помощник", onBack = onBack, subtitle = "Отвечает по вашим операциям") {
        PfIconButton(
            PfIcons.INFO,
            ABOUT_TITLE,
            onClick = { opened = true },
            enabled = !suppressed,
            modifier = Modifier.testTag("assistant.about.open"),
        )
    }
    if (opened && !suppressed && HelpTags.BOTTOM in aboutTags) {
        if (refreshLimit != null) {
            val currentRefresh by rememberUpdatedState(refreshLimit)
            LaunchedEffect(Unit) { currentRefresh() }
        }
        AssistantAboutSheet(
            remaining = state.knownRemaining ?: state.limit?.remaining,
            limit = state.limit.takeIf { state.hasLimitMetadata },
            zone = zone,
            onDismiss = { opened = false },
            about = about,
            owner = owner,
        )
    }
}

@Composable
private fun AssistantAboutSheet(
    remaining: Int?,
    limit: AssistantLimit?,
    zone: ZoneId,
    onDismiss: () -> Unit,
    about: AssistantAboutViewModel?,
    owner: RequestOwner?,
) {
    val d = PfTheme.dimens
    var showRules by rememberSaveable { mutableStateOf(false) }

    PfBottomSheet(ABOUT_TITLE, onDismiss, modifier = Modifier.testTag("assistant.about.sheet")) {
        Column(Modifier.padding(top = d.space2), verticalArrangement = Arrangement.spacedBy(d.space4)) {
            AboutSection("Что можно спросить") {
                Text("Помощник помогает разбирать траты и доходы по загруженным выпискам и отвечает на общие вопросы о личных финансах.")
                Text("«На что я больше всего потратил в прошлом месяце?»")
                Text("«Как работает накопительный счёт?»")
            }
            AboutSection("Сколько вопросов осталось") {
                Text(
                    assistantLimitSummary(remaining, limit, zone),
                    modifier = Modifier.testTag("assistant.about.limit"),
                    style = PfTheme.type.bodyStrong,
                )
                PfListRow(
                    "Как считаются вопросы",
                    modifier = Modifier.testTag("assistant.about.rules").semantics {
                        stateDescription = if (showRules) "Развёрнуто" else "Свёрнуто"
                    },
                    onClick = { showRules = !showRules },
                    divider = false,
                    trailing = {
                        PfIcon(
                            PfIcons.CHEVRON_DOWN,
                            contentDescription = null,
                            modifier = Modifier.rotate(if (showRules) 180f else 0f),
                        )
                    },
                )
                if (showRules) {
                    Text(
                        "Попытка возвращается, если произошёл сбой, ответа не получилось или сервер подтвердил, что для ответа не хватает данных.",
                    )
                    Text("Повторяющиеся вопросы вне темы могут временно ограничить отправку даже при ненулевом остатке.")
                }
            }
            AboutSection("На каких данных основан ответ") {
                Text("Для ответа о ваших финансах Помощник использует доступные операции из загруженных выписок. Если данных не хватает, он пояснит, каких именно.")
            }
            AboutSection("Как используются данные") {
                Text("При отправке вопроса с вашего согласия данные, нужные для ответа, передаются Alice AI от Яндекса.")
                ConsentDocumentLink(about, owner)
            }
        }
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PfTheme.dimens.space2)) {
        Text(title, style = PfTheme.type.title2, modifier = Modifier.semantics { heading() })
        content()
    }
}

@Composable
private fun ConsentDocumentLink(about: AssistantAboutViewModel?, owner: RequestOwner?) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ready = about?.documentReady?.collectAsStateWithLifecycle(initialValue = false)?.value == true
    var opening by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    PfLink(
        "Как помощник использует данные",
        onClick = {
            if (about != null && owner != null && !opening && ready) {
                opening = true
                failed = false
                scope.launch {
                    try {
                        val doc = about.loadDocument(owner)
                        val url = doc?.takeIf { it.type == ConsentType.ASSISTANT }?.let { Uri.parse(it.url) }
                            ?.takeIf { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrBlank() }
                        failed = url == null || runCatching {
                            CustomTabsIntent.Builder().build().launchUrl(context, url)
                        }.isFailure
                    } finally {
                        opening = false
                    }
                }
            }
        },
        modifier = Modifier.testTag("assistant.about.document"),
    )
    if (opening) Text("Открываем документ…", style = PfTheme.type.caption)
    if (failed) Text("Не получилось открыть документ. Попробуйте ещё раз.", style = PfTheme.type.caption)
}

internal fun assistantLimitSummary(remaining: Int?, limit: AssistantLimit?, zone: ZoneId): String = when {
    remaining != null && limit != null ->
        "Осталось $remaining из ${limit.dailyMax} вопросов. Лимит обновится в ${resetTime(limit.resetsAt, zone)}."
    remaining != null -> "Осталось вопросов: $remaining. Суточный лимит и время обновления пока недоступны."
    else -> "Остаток вопросов пока недоступен."
}
