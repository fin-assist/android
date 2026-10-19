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
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.designsystem.components.pfTestRoot
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.FlagOverrides
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.impl.data.SessionRepositoryImpl
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
 * - `call_ttl_ms=<n>` — lifetime of the next phone verification requests (`expires_at`, `expires_in`); lets a flow
 *   reach «Время на звонок вышло» without waiting 5 minutes.
 * - `network_delay_ms=<n>` — latency of every backend request (keeps a «busy» state up long enough to act on it).
 * - `assistant_model_errors=<n>` — the next n assistant answers fail with MODEL_ERROR («Повторить» under them).
 * - `stream_drops=<n>` — the next n openings of an SSE stream (call, import progress, answer) close after their first
 *   event without a terminal one, as the server does on its own failure; the app re-opens them (FIN-170).
 * - `stream_rate_limits=<n>` — the next n openings that follow a dropped one answer 429 with `Retry-After: 3`; the app
 *   waits and re-opens again, without «Слишком много попыток» (FIN-170).
 * - `stream_retry_s=<n>` — `Retry-After` of those 429 answers, seconds (a long one keeps the app waiting).
 * - `expect_stream_rate_limited=<n>` — a check, not a setting: the applied tag shows only if the fake server has
 *   already turned away at least n stream openings with 429; otherwise [TAG_UNMET] shows, and the flow fails on the
 *   missing applied tag. The check is instant: stream openings run on the main thread this activity holds, and the
 *   app's screen is paused under it, so the openings to count must have happened before the link is opened.
 * - `unknown_codes=true|false` — the fake server sends codes the client does not know (`MockCodes`, FIN-83).
 * - `failing_blocks=<id>,<id>` — those Analytics blocks fail to build and come as `unavailable` (FIN-5); empty — none.
 * - `profile_timezone=<zone id>` — `profile.timezone` on the server, as if changed from another device; the app
 *   adopts it on the next profile read (opening «Профиль»).
 * - `min_version=<MAJOR.MINOR.PATCH>` — the server's minimum client version; below it every request and every stream
 *   opening gets `CLIENT_VERSION_UNSUPPORTED` and the app shows the update screen (FIN-22). Empty — no minimum.
 * - `maintenance=<minutes>|unknown|off` — every request and every stream opening gets 503 `MAINTENANCE` (a stream already
 *   open is cut at its next event), ending in that many minutes from the fake «now» or at an unknown time; `off` ends it (FIN-22).
 * - `maintenance_retry_after=<s>` — `Retry-After` of the maintenance answer (15 s by default, the shortest the app waits).
 * - `sign_in=existing` — signs the existing mock account in and sets passcode 1234 the way the sign-in and passcode
 *   screens would ([E2eSignIn], FIN-180); the app then opens on the feed, unlocked. Applied after the other values
 *   (flags set in the same link are in place first). For flows that do not check the sign-in itself:
 *   `.maestro/subflows/fast_login.yaml`. Fails ([TAG_UNMET]) when already signed in or when the sign-in does not finish.
 *
 * Backend values live until the process dies: set them after `launchApp`.
 *
 * The values are written before the activity shows anything (with `sign_in`, the sign-in finishes first, too); then
 * it shows [TAG_APPLIED] for a moment and finishes. Flows open the link through `.maestro/subflows/config.yaml`,
 * which waits for the tag to come and go: a `launchApp` right after a bare `openLink` could destroy this activity
 * mid-write and drop the remaining values.
 */
@AndroidEntryPoint
class E2eControlActivity : ComponentActivity() {
    @Inject lateinit var overrides: FlagOverrides
    @Inject lateinit var backend: MockBackend
    @Inject lateinit var authApi: AuthApi
    @Inject lateinit var session: SessionRepository
    @Inject lateinit var sessionImpl: SessionRepositoryImpl
    @Inject lateinit var appLock: AppLock

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        // Synchronous on purpose (test build only, a few DataStore writes): nothing can cancel it half-way.
        val unmet = runBlocking { apply(uri) }
        val account = uri?.getQueryParameter(SIGN_IN)
        if (unmet != null || account == null) {
            show(unmet)
            return
        }
        // Not on the blocked main thread: the «call» alone takes 1.5 s. NonCancellable, so the session and the passcode
        // are both in place even if the activity goes away; the applied tag shows only after that.
        lifecycleScope.launch {
            show(withContext(NonCancellable) { signIn(account) })
        }
    }

    private suspend fun signIn(account: String): String? {
        if (account != SIGN_IN_EXISTING) return "sign_in=$account: only $SIGN_IN_EXISTING is supported"
        val signIn = E2eSignIn(
            authApi = authApi,
            session = session,
            startSession = { tokens -> sessionImpl.start(userId = null, tokens = tokens, registered = false) },
            appLock = appLock,
        )
        return signIn.signIn(phone = backend.config.existingPhone, passcode = E2E_PASSCODE)
    }

    private fun show(unmet: String?) {
        setContent {
            if (unmet == null) {
                Box(Modifier.fillMaxSize().pfTestRoot().testTag(TAG_APPLIED)) { Text("pfe2e: applied") }
            } else {
                Box(Modifier.fillMaxSize().pfTestRoot().testTag(TAG_UNMET)) { Text("pfe2e: $unmet") }
            }
        }
        window.decorView.postDelayed({ finish() }, VISIBLE_MS)
    }

    /** Applies the values; returns what an `expect_*` check did not find, or null. */
    private suspend fun apply(uri: Uri?): String? {
        var unmet: String? = null
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
                name == "call_ttl_ms" -> value.toLongOrNull()?.let { backend.config = backend.config.copy(verificationTtlMs = it) }
                name == "network_delay_ms" -> value.toLongOrNull()?.let { backend.config = backend.config.copy(networkDelayMs = it) }
                name == "assistant_model_errors" -> value.toIntOrNull()?.let { backend.config = backend.config.copy(assistantModelErrors = it) }
                name == "stream_drops" -> value.toIntOrNull()?.let { backend.config = backend.config.copy(streamDrops = it) }
                name == "stream_rate_limits" -> value.toIntOrNull()?.let { backend.config = backend.config.copy(streamRateLimits = it) }
                name == "stream_retry_s" -> value.toLongOrNull()?.let { backend.config = backend.config.copy(streamRetrySeconds = it) }
                name == "expect_stream_rate_limited" -> value.toIntOrNull()?.let { expected ->
                    val given = backend.streamRateLimited.value
                    if (given < expected) unmet = "expected $expected stream 429s, got $given"
                }
                name == "unknown_codes" -> backend.config = backend.config.copy(unknownCodes = value.toBoolean())
                name == "failing_blocks" -> backend.config = backend.config.copy(failingBlocks = value.split(',').map(String::trim).filter(String::isNotEmpty).toSet())
                name == "profile_timezone" -> backend.mutex.withLock { backend.profile = backend.profile.copy(timezone = value) }
                name == "min_version" -> backend.config = backend.config.copy(minVersion = value.ifBlank { null })
                name == SIGN_IN -> Unit // after the other values, in onCreate
                name == "maintenance_retry_after" -> value.toIntOrNull()?.let { backend.config = backend.config.copy(maintenanceRetryAfterSeconds = it) }
                name == "maintenance" -> backend.config = when (value) {
                    "off" -> backend.config.copy(maintenance = false, maintenanceUntil = null)
                    "unknown" -> backend.config.copy(maintenance = true, maintenanceUntil = null)
                    else -> backend.config.copy(maintenance = true, maintenanceUntil = value.toLongOrNull()?.let { backend.now().plusMinutes(it) })
                }
                else -> Log.w(TAG, "unknown parameter $name in $uri")
            }
        }
        return unmet
    }

    private companion object {
        const val TAG = "PfE2e"
        const val FLAG_PREFIX = "flag."
        const val SIGN_IN = "sign_in"
        const val SIGN_IN_EXISTING = "existing"
        /** The passcode the flows type (`.maestro/subflows/set_passcode.yaml`). */
        const val E2E_PASSCODE = "1234"
        /** Test tag shown once the values are applied; `.maestro/subflows/config.yaml` waits on it. */
        const val TAG_APPLIED = "e2e.config.applied"
        /** Shown instead of [TAG_APPLIED] when an `expect_*` check fails. */
        const val TAG_UNMET = "e2e.config.unmet"
        /** Long enough for Maestro to catch the tag between its hierarchy polls, even on a slow CI emulator. */
        const val VISIBLE_MS = 4000L
    }
}
