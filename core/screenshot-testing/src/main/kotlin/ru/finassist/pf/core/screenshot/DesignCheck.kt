package ru.finassist.pf.core.screenshot

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import ru.finassist.pf.core.designsystem.theme.LocalPfSystemBars
import ru.finassist.pf.core.designsystem.theme.PfSystemBars
import androidx.compose.ui.Modifier
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import ru.finassist.pf.core.designsystem.components.PfTabBar
import ru.finassist.pf.core.designsystem.components.TabItem
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.designsystem.theme.PfTheme
import java.io.File
import java.time.Duration

/**
 * Design check: renders a screen the way the mockups draw it, for comparison with the Claude Design artboards.
 *
 * - 390 dp wide at xxhdpi (×3): the same 1170 px as the mockup render.
 * - System bars as in the mockups: 24 dp status bar and 24 dp gesture bar, supplied through
 *   [LocalPfSystemBars] (Robolectric does not deliver window insets to Compose).
 * - Main-graph screens get the tab bar the app draws around them (`MainLayer`), because the artboards include it.
 *
 * Output: `$pf.designcheck.dir/<name>.png` (set by the `pf.screenshots` convention; Roborazzi record mode).
 */
object DesignCheck {
    const val WIDTH_DP = 390
    const val PHONE_HEIGHT_DP = 844
    private const val SYSTEM_BAR_DP = 24

    /** Same tabs as `MainLayer` in the app module. */
    private val tabs = listOf(
        TabItem(PfIcons.LIST, "Операции"),
        TabItem(PfIcons.BAR_CHART, "Аналитика"),
        TabItem(PfIcons.USER, "Профиль"),
    )

    private val outputDir: File
        get() = File(System.getProperty("pf.designcheck.dir") ?: "build/design-check")

    /**
     * @param name artboard name in the canvas (`Main`, `MainDark`…): the output file is matched to it by name.
     * @param heightDp artboard height; long artboards (Analytics) are taller than a phone.
     * @param tab active tab for main-graph screens, null for screens without the tab bar.
     */
    fun capture(
        name: String,
        dark: Boolean = false,
        heightDp: Int = PHONE_HEIGHT_DP,
        tab: Int? = null,
        content: @Composable () -> Unit,
    ) {
        RuntimeEnvironment.setQualifiers("w${WIDTH_DP}dp-h${heightDp}dp-${if (dark) "night" else "notnight"}-xxhdpi")
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        // The app's activity has no action bar; the default test theme would draw one with the class name.
        controller.get().setTheme(android.R.style.Theme_Material_NoActionBar)
        val activity = controller.setup().get()
        activity.setContent {
            val bars = PfSystemBars(
                statusBars = WindowInsets(top = SYSTEM_BAR_DP.dp),
                navigationBars = WindowInsets(bottom = SYSTEM_BAR_DP.dp),
            )
            CompositionLocalProvider(LocalPfSystemBars provides bars) { PfTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(PfTheme.colors.bg)) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
                        if (tab != null) PfTabBar(items = tabs, active = tab, onSelect = {})
                    }
                }
            } }
        }
        // Let intro animations (charts, tiles) reach their final frame, as on the static mockup.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        activity.window.decorView.captureRoboImage(
            File(outputDir, "$name.png").path,
            RoborazziOptions(recordOptions = RoborazziOptions.RecordOptions(resizeScale = 1.0)),
        )
    }
}
