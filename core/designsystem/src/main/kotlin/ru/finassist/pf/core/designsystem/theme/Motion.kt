package ru.finassist.pf.core.designsystem.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Motion rules of the design system («Движение»). Only Analytics charts animate: an intro once per process
 * for each chart, and a 250 ms transition between values afterwards. With the system "Remove animations"
 * setting everything renders in its final state.
 */
object PfMotion {
    /** Growth of one bar / one category bar during the intro. */
    const val INTRO_DURATION_MS = 500
    /** Delay between consecutive bars / rows during the intro. */
    const val INTRO_STAGGER_MS = 40
    /** Tiles only fade in. */
    const val FADE_DURATION_MS = 300
    /** Transition between old and new values (period change, filter, refreshed data). */
    const val CHANGE_DURATION_MS = 250
    /** Value labels appear during the last 150 ms of their bar's growth. */
    const val LABEL_FADE_MS = 150

    /** Emphasized decelerate — intro growth. */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Standard — value transitions. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/**
 * Remembers which charts have already played their intro in this process. Keyed by a stable chart id
 * (`"analytics.expenses.monthly"`, `"assistant.<answer>.<block>"`); rotation, theme change, tab switches and
 * returning from Search keep the key, so the intro does not replay. An interrupted intro is not replayed either:
 * the key is marked as soon as the intro starts.
 */
object ChartIntroRegistry {
    private val played = ConcurrentHashMap.newKeySet<String>()

    /** True the first time a chart with this id asks; false afterwards. */
    fun claimIntro(chartId: String): Boolean = played.add(chartId)

    fun hasPlayed(chartId: String): Boolean = chartId in played

    /** Tests only. */
    fun reset() = played.clear()
}

/** True when the system animator duration scale is 0 ("Remove animations"): charts show their final state at once. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
