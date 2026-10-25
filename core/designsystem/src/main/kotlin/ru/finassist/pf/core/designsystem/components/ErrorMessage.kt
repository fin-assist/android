package ru.finassist.pf.core.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity

/** Shared plain-text renderer; action existence and callback belong to the feature, never to server text/code. */
@Composable
fun ErrorMessage(
    presentation: ErrorPresentation,
    fallback: String,
    modifier: Modifier = Modifier,
    legacySafeText: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier) {
    PfNotice(
        text = presentation.textOrFallback(fallback, legacySafeText),
        modifier = Modifier.testTag("ds.error.${presentation.severity.name.lowercase()}"),
        tone = when (presentation.severity) {
            ErrorSeverity.INFO -> NoticeTone.INFO
            ErrorSeverity.WARNING, ErrorSeverity.ERROR -> NoticeTone.WARNING
        },
        alert = presentation.severity == ErrorSeverity.ERROR,
        action = if (onRetry != null) ({ PfNoticeLink("Повторить", onClick = onRetry) }) else null,
    )
    }
}
