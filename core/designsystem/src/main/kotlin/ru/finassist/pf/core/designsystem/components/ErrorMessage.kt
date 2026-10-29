package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity

internal object ErrorMessageTags {
    const val INFO = "ds.error.info"
    const val WARNING = "ds.error.warning"
    const val ERROR = "ds.error.error"
}

/** Shared plain-text renderer; action existence and callback belong to the feature, never to server text/code. */
@Composable
fun ErrorMessage(
    presentation: ErrorPresentation,
    fallback: String,
    modifier: Modifier = Modifier,
    legacySafeText: String? = null,
    onRetry: (() -> Unit)? = null,
    retryModifier: Modifier = Modifier,
) {
    Box(modifier) {
        PfNotice(
            text = presentation.textOrFallback(fallback, legacySafeText),
            modifier = Modifier.testTag(when (presentation.severity) {
                ErrorSeverity.INFO -> ErrorMessageTags.INFO
                ErrorSeverity.WARNING -> ErrorMessageTags.WARNING
                ErrorSeverity.ERROR -> ErrorMessageTags.ERROR
            }),
            tone = when (presentation.severity) {
                ErrorSeverity.INFO -> NoticeTone.INFO
                ErrorSeverity.WARNING -> NoticeTone.WARNING
                ErrorSeverity.ERROR -> NoticeTone.ERROR
            },
            alert = presentation.severity == ErrorSeverity.ERROR,
            action = if (onRetry != null) ({ PfNoticeLink("Повторить", onClick = onRetry, modifier = retryModifier) }) else null,
        )
    }
}
