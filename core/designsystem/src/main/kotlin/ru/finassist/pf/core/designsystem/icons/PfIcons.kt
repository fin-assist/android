package ru.finassist.pf.core.designsystem.icons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.theme.PfTheme

/**
 * Icon set of the design system: linear 24×24 outlines, stroke 1.75 (2.25 for sizes up to 14 dp), one colour
 * (`currentColor`). Keys are the names the API sends in `category_icon`; an unknown key falls back to `tag`.
 */
object PfIcons {
    const val DEFAULT = "tag"

    private val cache = HashMap<String, ImageVector>()

    val names: Set<String> get() = ICON_PATHS.keys

    fun has(name: String) = name in ICON_PATHS

    operator fun get(name: String): ImageVector = vector(if (name in ICON_PATHS) name else DEFAULT)

    private fun vector(name: String): ImageVector = cache.getOrPut(name) {
        ImageVector.Builder(name = "pf-$name", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                ICON_PATHS.getValue(name).forEach { d ->
                    addPath(
                        pathData = addPathNodes(d),
                        name = "",
                        fill = null,
                        stroke = SolidColor(Color.Black),
                        strokeLineWidth = STROKE,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()
    }

    private const val STROKE = 1.75f

    // Frequently used keys, so call sites do not repeat strings.
    const val ARROW_LEFT = "arrow-left"
    const val X = "x"
    const val CHEVRON_RIGHT = "chevron-right"
    const val CHEVRON_LEFT = "chevron-left"
    const val CHEVRON_DOWN = "chevron-down"
    const val SEARCH = "search"
    const val LIST = "list"
    const val BAR_CHART = "bar-chart"
    const val USER = "user"
    const val MESSAGE = "message"
    const val MIC = "mic"
    const val SEND = "send"
    const val CLOCK = "clock"
    const val LOCK = "lock"
    const val INFO = "info"
    const val ALERT = "alert-triangle"
    const val CHECK = "check"
    const val CHECK_CIRCLE = "check-circle"
    const val UPLOAD = "upload"
    const val FILE_TEXT = "file-text"
    const val LANDMARK = "landmark"
    const val RECEIPT = "receipt"
    const val FINGERPRINT = "fingerprint"
    const val SCAN_FACE = "scan-face"
    const val DELETE = "delete"
    const val LOG_OUT = "log-out"
    const val LIFE_BUOY = "life-buoy"
    const val MOON = "moon"
    const val SUN = "sun"
    const val SMARTPHONE = "smartphone"
    const val PHONE = "phone"
    const val TRASH = "trash"
    const val REPEAT = "repeat"
    const val PERCENT = "percent"
    const val TRENDING_UP = "trending-up"
    const val TRANSFER = "transfer"
    const val TAG = "tag"
    const val DOWNLOAD = "download"
    const val MAIL = "mail"
    const val SLIDERS = "sliders"
    const val PENCIL = "pencil"
}

/** Draws an icon by key with the current content colour (or [tint]). Stroke is thicker below 14 dp per the DS. */
@Composable
fun PfIcon(
    name: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = PfTheme.dimens.iconLg,
    tint: Color = Color.Unspecified,
) {
    Icon(
        imageVector = PfIcons[name],
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint,
    )
}
