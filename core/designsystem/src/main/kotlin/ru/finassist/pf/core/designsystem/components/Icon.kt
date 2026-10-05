package ru.finassist.pf.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfRadius
import ru.finassist.pf.core.designsystem.theme.PfSize
import ru.finassist.pf.core.designsystem.theme.PfTheme

/** Line icon by design-system name; `contentDescription == null` marks it decorative. */
@Composable
fun PfIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = PfSize.iconMd,
    tint: Color = PfTheme.colors.text,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(PfIcons.resolve(name)),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/** Category icon on an `accent-soft` tile: 40 (lists) or 36 (small). */
@Composable
fun IconTile(name: String, modifier: Modifier = Modifier, small: Boolean = false) {
    val c = PfTheme.colors
    Box(
        modifier = modifier
            .size(if (small) PfSize.tileSm else PfSize.tile)
            .background(c.accentSoft, RoundedCornerShape(if (small) PfRadius.sm else PfRadius.md)),
        contentAlignment = Alignment.Center,
    ) {
        PfIcon(name, size = PfSize.iconMd, tint = c.accent)
    }
}
