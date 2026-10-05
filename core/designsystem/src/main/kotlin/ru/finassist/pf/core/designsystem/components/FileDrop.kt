package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.theme.PfRadius
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** File picker zone: dashed `border-strong` frame; `busy` shows the file name and progress bar. */
@Composable
fun FileDrop(
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Выбрать файл OFX",
    hint: String = "Файл OFX до 10 МБ",
    busy: Boolean = false,
    fileName: String? = null,
    progress: Int = 0,
) {
    val c = PfTheme.colors
    val shape = RoundedCornerShape(PfRadius.xl)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .drawBehind { drawRoundRect(c.borderStrong, cornerRadius = CornerRadius(18.dp.toPx()), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx())))) }
            .then(if (busy) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier.clickable(role = Role.Button, onClick = onPick))
            .padding(PfSpace.s5),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PfIcon(if (busy) "file-text" else "upload", size = 28.dp, tint = c.accent)
        Spacer(Modifier.height(PfSpace.s2))
        if (busy) {
            Text("Разбираем операции…", style = PfTheme.type.bodyStrong, color = c.text, textAlign = TextAlign.Center)
            if (fileName != null) Text(fileName, style = PfTheme.type.caption, color = c.textMuted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(PfSpace.s3))
            Box(Modifier.fillMaxWidth().height(6.dp).background(c.border, RoundedCornerShape(PfRadius.full))) {
                Box(Modifier.fillMaxWidth((progress.coerceIn(0, 100)) / 100f).height(6.dp).background(c.accent, RoundedCornerShape(PfRadius.full)))
            }
        } else {
            Text(title, style = PfTheme.type.bodyStrong, color = c.accent, textAlign = TextAlign.Center)
            Text(hint, style = PfTheme.type.caption, color = c.textMuted, textAlign = TextAlign.Center)
        }
    }
}
