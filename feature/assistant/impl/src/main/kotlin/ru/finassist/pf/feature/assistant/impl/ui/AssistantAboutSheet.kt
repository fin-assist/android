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
import androidx.compose.runtime.key
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
import ru.finassist.pf.core.api.model.HelpTags
import ru.finassist.pf.core.designsystem.components.ErrorMessage
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
    refreshLimit: (() -> Unit)?,
) {
    val session = about?.session?.collectAsStateWithLifecycle()?.value as? SessionState.SignedIn
    val owner = remember(session) {
        session?.authSessionEpoch?.takeIf { it.isNotBlank() && session.userId.isNotBlank() }
            ?.let { RequestOwner(session.userId, it) }
    }
    // Leave the owner's saved state untouched until the session is confirmed, including after process restoration.
    if (about != null && owner == null) {
        AssistantPageHeader(onBack, onAbout = null)
        return
    }
    key(owner?.userId, owner?.authSessionEpoch) {
        AssistantOwnedChatHeader(state, zone, onBack, about, owner, initiallyOpen, refreshLimit)
    }
}

@Composable
private fun AssistantOwnedChatHeader(
    state: ChatUiState,
    zone: ZoneId,
    onBack: () -> Unit,
    about: AssistantAboutViewModel?,
    owner: RequestOwner?,
    initiallyOpen: Boolean,
    refreshLimit: (() -> Unit)?,
) {
    var opened by rememberSaveable { mutableStateOf(initiallyOpen) }
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

    AssistantPageHeader(onBack, onAbout = if (suppressed) null else ({ opened = true }))
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
private fun AssistantPageHeader(onBack: () -> Unit, onAbout: (() -> Unit)?) {
    PfPageHeader("Помощник", onBack = onBack, subtitle = "Отвечает по вашим операциям") {
        PfIconButton(
            PfIcons.INFO,
            ABOUT_TITLE,
            onClick = { onAbout?.invoke() },
            enabled = onAbout != null,
            modifier = Modifier.testTag("assistant.about.open"),
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
                Text("Помощник помогает разбирать траты и доходы по загруженным банковским данным и отвечает на общие вопросы о личных финансах.")
                Text("«На что я больше всего потратил в прошлом месяце?»")
                Text("«Как составить личный бюджет?»")
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
                        "Если Помощник не смог ответить, вопрос не засчитывается в суточный лимит. " +
                            "Вопрос также не засчитывается, если для ответа не хватило данных и сервер это подтвердил.",
                    )
                    Text(
                        "Если часто задавать вопросы вне темы, отправка может стать временно недоступной, " +
                            "даже если вы ещё не исчерпали суточный лимит вопросов.",
                    )
                }
            }
            AboutSection("На каких данных основан ответ") {
                Text("Для ответа о ваших финансах Помощник использует доступные операции из загруженных банковских данных. Если данных не хватает, он пояснит, каких именно.")
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
    val state = about?.document?.collectAsStateWithLifecycle()?.value ?: AboutDocumentState()
    val open: () -> Unit = {
        if (about != null && owner != null) {
            scope.launch {
                val url = about.loadDocument(owner) ?: return@launch
                val launched = runCatching {
                    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url).normalizeScheme())
                }
                if (launched.isFailure) about.documentLaunchFailed(owner)
            }
        }
    }
    when {
        state.opening -> Text("Открываем документ…", style = PfTheme.type.caption)
        state.error != null -> {
            ErrorMessage(state.error, "Не получилось открыть документ", onRetry = open.takeIf { state.retryReady })
            if (!state.retryReady) Text("Повторное открытие пока недоступно.", style = PfTheme.type.caption)
        }
        else -> PfLink("Как помощник использует данные", onClick = open, modifier = Modifier.testTag("assistant.about.document"))
    }
}

internal fun assistantLimitSummary(remaining: Int?, limit: AssistantLimit?, zone: ZoneId): String = when {
    remaining != null && limit != null ->
        "Осталось $remaining из ${limit.dailyMax} вопросов. Лимит обновится в ${resetTime(limit.resetsAt, zone)}."
    remaining != null -> "Осталось вопросов: $remaining. Суточный лимит и время обновления пока недоступны."
    else -> "Остаток вопросов пока недоступен."
}
