package ru.finassist.pf.core.designsystem.motion

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** README «Движение»: emphasized decelerate for intro growth, standard for value transitions. */
object PfMotion {
    val emphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val INTRO_MS = 500
    const val INTRO_STAGGER_MS = 40
    const val TRANSITION_MS = 250
    const val FADE_MS = 300
}

/** False when the system setting «Убрать анимацию» (animator duration scale 0) is on: final state immediately. */
@Composable
fun rememberMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f
    }
}

/**
 * Process-scoped memory of chart intros: each chart id plays its intro once per app process
 * (not again on rotation, theme change, tab return or data refresh).
 */
object ChartIntroRegistry {
    private val shown = HashSet<String>()

    /** Returns true the first time an id is claimed; false afterwards. */
    @Synchronized
    fun claim(id: String): Boolean = shown.add(id)

    @Synchronized
    fun reset() = shown.clear()
}
