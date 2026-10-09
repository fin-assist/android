package ru.finassist.pf.e2e

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import ru.finassist.pf.core.designsystem.components.pfTestRoot
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.FlagOverrides
import ru.finassist.pf.mock.MockBackend
import javax.inject.Inject

/**
 * Test control, mock e2e build only. Maestro opens `pfe2e://config?<param>=<value>&…` with `openLink`; the
 * activity applies the values and finishes, so the app underneath carries on.
 *
 * - `flag.<key>=true|false|default` — a feature flag override (`flag.auth.registration=false`). Persisted with
 *   the app data, so it survives `launchApp` and is wiped by `clearState`. Flags are read at the screen
 *   boundary: set them before opening the screen they affect.
 * - `flags=reset` — drop every override.
 * - `offline=true|false` — every request of the fake backend fails with «нет сети».
 * - `call_delay_ms=<n>` — how long the sign-in call takes to «arrive» (keeps the call screen up).
 *
 * Backend values live until the process dies: set them after `launchApp`.
 *
 * The values are written before the activity shows anything; then it shows [TAG_APPLIED] for a moment and
 * finishes. Flows open the link through `.maestro/subflows/config.yaml`, which waits for the tag to come and go:
 * a `launchApp` right after a bare `openLink` could destroy this activity mid-write and drop the remaining values.
 */
@AndroidEntryPoint
class E2eControlActivity : ComponentActivity() {
    @Inject lateinit var overrides: FlagOverrides
    @Inject lateinit var backend: MockBackend

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Synchronous on purpose (test build only, a few DataStore writes): nothing can cancel it half-way.
        runBlocking { apply(intent?.data) }
        setContent {
            Box(Modifier.fillMaxSize().pfTestRoot().testTag(TAG_APPLIED)) { Text("pfe2e: applied") }
        }
        window.decorView.postDelayed({ finish() }, VISIBLE_MS)
    }

    private suspend fun apply(uri: Uri?) {
        uri?.queryParameterNames.orEmpty().forEach { name ->
            val value = uri?.getQueryParameter(name).orEmpty()
            when {
                name == "flags" && value == "reset" -> overrides.clear()
                name.startsWith(FLAG_PREFIX) -> {
                    val flag = Flag.byKey(name.removePrefix(FLAG_PREFIX))
                    if (flag == null) Log.w(TAG, "unknown flag in $uri") else overrides.set(flag, value.toBooleanStrictOrNull())
                }
                name == "offline" -> backend.config = backend.config.copy(offline = value.toBoolean())
                name == "call_delay_ms" -> value.toLongOrNull()?.let { backend.config = backend.config.copy(callVerifyDelayMs = it) }
                else -> Log.w(TAG, "unknown parameter $name in $uri")
            }
        }
    }

    private companion object {
        const val TAG = "PfE2e"
        const val FLAG_PREFIX = "flag."
        /** Test tag shown once the values are applied; `.maestro/subflows/config.yaml` waits on it. */
        const val TAG_APPLIED = "e2e.config.applied"
        /** Long enough for Maestro to see the tag between its polls. */
        const val VISIBLE_MS = 1500L
    }
}
