package ru.finassist.pf.feature.applock.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import ru.finassist.pf.core.designsystem.components.BiometricKind
import ru.finassist.pf.core.designsystem.components.NumPad
import ru.finassist.pf.core.designsystem.components.PageHeader
import ru.finassist.pf.core.designsystem.components.PasscodeDots
import ru.finassist.pf.core.designsystem.components.PfLink
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

/**
 * Shared passcode screen: title, dots, numpad. The 4th digit submits automatically; [onComplete] returns an
 * error text to show (digits are cleared) or null when accepted.
 */
@Composable
fun PasscodeEntry(
    title: String,
    dotsLabel: String,
    onComplete: suspend (String) -> String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    headerTitle: String? = null,
    onBack: (() -> Unit)? = null,
    biometric: BiometricKind = BiometricKind.None,
    onBiometric: (() -> Unit)? = null,
    footerLinkText: String? = null,
    onFooterLink: (() -> Unit)? = null,
    resetKey: Any? = null,
) {
    val c = PfTheme.colors
    var digits by remember(resetKey) { mutableStateOf("") }
    var error by remember(resetKey) { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    LaunchedEffect(digits) {
        if (digits.length == 4 && !submitting) {
            submitting = true
            error = onComplete(digits)
            digits = ""
            submitting = false
        }
    }
    Column(modifier.fillMaxSize().background(c.bg)) {
        if (headerTitle != null && onBack != null) PageHeader(title = headerTitle, onBack = onBack)
        else Spacer(Modifier.windowInsetsPadding(WindowInsets.statusBars).height(PfSpace.s12))
        Column(Modifier.weight(1f).padding(horizontal = ScreenPadding), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(PfSpace.s6))
            Text(title, style = PfTheme.type.title1, color = c.text, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Spacer(Modifier.height(PfSpace.s2))
                Text(subtitle, style = PfTheme.type.lead, color = c.textMuted, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(PfSpace.s8))
            PasscodeDots(filled = digits.length, label = dotsLabel, error = error)
        }
        Column(Modifier.padding(horizontal = ScreenPadding).windowInsetsPadding(WindowInsets.navigationBars), horizontalAlignment = Alignment.CenterHorizontally) {
            NumPad(
                onDigit = { d -> if (digits.length < 4 && !submitting) { error = null; digits += d } },
                onDelete = { digits = digits.dropLast(1) },
                biometric = biometric,
                onBiometric = onBiometric,
            )
            Spacer(Modifier.height(PfSpace.s4))
            if (footerLinkText != null && onFooterLink != null) PfLink(footerLinkText, onClick = onFooterLink)
            Spacer(Modifier.height(PfSpace.s4))
        }
    }
}
