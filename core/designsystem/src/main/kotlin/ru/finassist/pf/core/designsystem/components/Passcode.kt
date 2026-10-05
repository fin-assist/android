package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Four dots for the passcode, with an optional error line announced as alert. */
@Composable
fun PasscodeDots(filled: Int, label: String, modifier: Modifier = Modifier, length: Int = 4, error: String? = null) {
    val c = PfTheme.colors
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(PfSpace.s4),
            modifier = Modifier.semantics { contentDescription = "$label: введено $filled из $length цифр" },
        ) {
            repeat(length) { i ->
                val isFilled = i < filled
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(if (isFilled) c.accent else c.surface)
                        .border1(if (error != null) c.warningText else if (isFilled) c.accent else c.borderStrong, CircleShape, 2.dp),
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(PfSpace.s3))
            Text(error, style = PfTheme.type.captionStrong, color = c.warningText, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
        }
    }
}

enum class BiometricKind { None, Fingerprint, Face }

/** 3×4 numeric pad, 72 dp round keys; bottom-left biometric, bottom-right delete. */
@Composable
fun NumPad(onDigit: (Int) -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier, biometric: BiometricKind = BiometricKind.None, onBiometric: (() -> Unit)? = null) {
    val c = PfTheme.colors
    val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
    Column(modifier.semantics { contentDescription = "Цифровая клавиатура" }, verticalArrangement = Arrangement.spacedBy(PfSpace.s4), horizontalAlignment = Alignment.CenterHorizontally) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(PfSpace.s8)) { row.forEach { d -> Key(d.toString(), onClick = { onDigit(d) }) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PfSpace.s8)) {
            if (biometric != BiometricKind.None) {
                Box(Modifier.size(72.dp).clip(CircleShape).clickable(role = Role.Button) { onBiometric?.invoke() }.semantics { contentDescription = "Войти по биометрии" }, contentAlignment = Alignment.Center) {
                    PfIcon(if (biometric == BiometricKind.Face) "scan-face" else "fingerprint", size = 28.dp, tint = c.accent)
                }
            } else Box(Modifier.size(72.dp))
            Key("0", onClick = { onDigit(0) })
            Box(Modifier.size(72.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDelete).semantics { contentDescription = "Стереть" }, contentAlignment = Alignment.Center) {
                PfIcon("delete", size = 28.dp, tint = c.text)
            }
        }
    }
}

@Composable
private fun Key(text: String, onClick: () -> Unit) {
    Box(Modifier.size(72.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = PfTheme.type.title1.copy(fontWeight = FontWeight.Normal), color = PfTheme.colors.text)
    }
}
