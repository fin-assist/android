package ru.finassist.pf.e2e

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.AnalyticsApi
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.designsystem.components.pfTestRoot
import ru.finassist.pf.core.storage.IdempotencyKeys
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.FlagOverrides
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.impl.data.SessionRepositoryImpl
import java.security.MessageDigest
import javax.inject.Inject

/** Closed controls for the isolated test APK. Backend operations always use the run-owned synthetic session. */
@AndroidEntryPoint
class StageControlActivity : ComponentActivity() {
    @Inject lateinit var runs: StageRunStore
    @Inject lateinit var faults: StageFaults
    @Inject lateinit var overrides: FlagOverrides
    @Inject lateinit var authApi: AuthApi
    @Inject lateinit var profileApi: ProfileApi
    @Inject lateinit var statementsApi: StatementsApi
    @Inject lateinit var analyticsApi: AnalyticsApi
    @Inject lateinit var assistantApi: AssistantApi
    @Inject lateinit var session: SessionRepository
    @Inject lateinit var sessionImpl: SessionRepositoryImpl
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var keys: IdempotencyKeys

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            var status: StageControlStatus? = null
            var phase = StageBootstrapPhase.INITIAL
            val observe: (StageBootstrapPhase) -> Unit = { next ->
                phase = next
                status?.write(phase, StageControlOutcome.STARTED)
            }
            val success = try {
                val names = intent?.data?.queryParameterNames.orEmpty()
                val operation = when {
                    "setup_run" in names -> StageControlOperation.SETUP
                    "cleanup_run" in names -> StageControlOperation.CLEANUP
                    "sign_in" in names -> StageControlOperation.SIGN_IN
                    "expect_process_question" in names -> StageControlOperation.PROCESS_QUESTION
                    else -> StageControlOperation.CONFIG
                }
                val writer = StageControlStatus(this@StageControlActivity, runs.read().run_id, operation)
                status = writer
                observe(StageBootstrapPhase.INITIAL)
                withTimeout(180_000) { apply(intent?.data, observe) }
                writer.write(StageBootstrapPhase.DONE, StageControlOutcome.PASSED)
                true
            } catch (failure: Exception) {
                // Never render or persist an exception message or URI: both can contain caller/server data.
                runCatching { status?.write(phase, StageControlOutcome.FAILED, failure) }
                false
            }
            setContent {
                Box(Modifier.fillMaxSize().pfTestRoot().testTag(if (success) "e2e.config.applied" else "e2e.config.unmet")) {
                    Text(if (success) "Stage UI: applied" else "Stage UI: unsupported control or unmet assertion")
                }
            }
            window.decorView.postDelayed({ finish() }, 4_000L)
        }
    }

    private suspend fun apply(uri: Uri?, onPhase: (StageBootstrapPhase) -> Unit) {
        val run = runs.read()
        require(uri?.scheme == "pfe2e" && uri.host == "config" && uri.fragment == null)
        val controls = uri.queryParameterNames.associateWith { name ->
            require(uri.getQueryParameters(name).size == 1)
            uri.getQueryParameter(name).orEmpty()
        }
        // Validate the complete command before applying its first mutation.
        controls.forEach { (name, value) ->
            when {
                name.startsWith("flag.") -> {
                    require(Flag.byKey(name.removePrefix("flag.")) != null)
                    require(value == "default" || value.toBooleanStrictOrNull() != null)
                }
                name == "flags" -> require(value == "reset")
                name == "sign_in" -> require(value == "existing")
                name == "setup_run" -> require(value in StageBootstrap.MODES)
                name == "cleanup_run" -> require(value == "owned")
                name == "expect_process_question" -> require(value == "one")
                name in setOf("expect_stream_rate_limited", "expect_stream_rate_limited_exact", "expect_error_requests", "expect_uploads") -> require(value.toIntOrNull() in 0..10_000)
                name == "pending_upload_fixture" -> require(value == "october")
                name == "profile_timezone" -> require(value in setOf("Asia/Vladivostok", "Europe/Moscow"))
                else -> StageFaultPlan.validate(name, value)
            }
        }
        require(controls["setup_run"] == null || controls["sign_in"] == null)
        if (controls["cleanup_run"] != null) {
            require(controls.size == 1)
            faults.clearForCleanup()
            onPhase(StageBootstrapPhase.PROFILE)
            val current = profileApi.getProfile()
            require(faults.canCleanup(current.phone, current.userId))
            onPhase(StageBootstrapPhase.CLEANUP_DELETE)
            profileApi.deleteAccount()
            onPhase(StageBootstrapPhase.CLEANUP_LOCAL)
            session.clearLocal("deleted")
            appLock.reset()
            return
        }
        faults.configure(controls.filterKeys { name ->
            name !in setOf("flags", "sign_in", "setup_run", "expect_process_question", "expect_stream_rate_limited", "expect_stream_rate_limited_exact", "expect_error_requests", "expect_uploads", "pending_upload_fixture", "profile_timezone") && !name.startsWith("flag.")
        })
        controls.forEach { (name, value) ->
            when {
                name == "flags" -> {
                    overrides.clear()
                    faults.flagsResetApplied()
                }
                name.startsWith("flag.") -> {
                    overrides.set(Flag.byKey(name.removePrefix("flag."))!!, value.toBooleanStrictOrNull())
                    faults.flagOverrideApplied()
                }
            }
        }
        controls["setup_run"]?.let { mode ->
            require(run.new_account)
            StageBootstrap(
                authApi, profileApi, statementsApi, analyticsApi, session, appLock,
                startSession = { registered ->
                    // The precondition performs the real initial import before revealing the main UI.
                    sessionImpl.start(registered.userId, TokenPair(registered.accessToken, registered.refreshToken), registered = false)
                },
                fixture = { assets.open("statements/fixture.ofx").use { it.readBytes() } },
                onPhase = onPhase,
            ).setup(run.phone, mode)
        }
        if (controls["sign_in"] != null) {
            val signIn = E2eSignIn(authApi, session, { tokens ->
                sessionImpl.start(userId = null, tokens = tokens, registered = false)
            }, appLock)
            require(signIn.signIn(run.phone, "1234") == null)
        }
        if (controls["hold_ask_response_ms"]?.toLong()?.let { it > 0 } == true) {
            // A fresh real profile read binds the actual authenticated response to the primary run account.
            require(profileApi.getProfile().phone == run.phone)
        }
        controls["profile_timezone"]?.let { timezone ->
            require(profileApi.getProfile().phone == run.phone)
            profileApi.updateProfile(ProfileUpdate(timezone = timezone))
        }
        controls["pending_upload_fixture"]?.let { seedPendingUpload() }
        controls["expect_stream_rate_limited"]?.let { require(faults.count("stream_429") >= it.toInt()) }
        controls["expect_stream_rate_limited_exact"]?.let { require(faults.count("stream_429") == it.toInt()) }
        controls["expect_error_requests"]?.let { require(faults.count("error_requests") == it.toInt()) }
        controls["expect_uploads"]?.let {
            require(profileApi.getProfile().phone == run.phone)
            require(statementsApi.listStatements().uploads.size == it.toInt())
        }
        if (controls["expect_process_question"] != null) {
            val current = profileApi.getProfile()
            require(current.phone == run.phone && faults.canCleanup(current.phone, current.userId))
            // api.md 7.3: history pages accept at most 50 messages; hasOlder must still be false.
            StageProcessQuestionEvidence.verify(run.run_id, assistantApi.listMessages(limit = 50), assistantApi.getLimit(), faults::count)
        }
    }

    private suspend fun seedPendingUpload() {
        val digest = MessageDigest.getInstance("SHA-256")
        assets.open("e2e/statements/october.ofx").use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        val hash = MessageDigest.getInstance("SHA-256").apply {
            update(digest.digest()); update("october.ofx".toByteArray(Charsets.UTF_8))
        }.digest().joinToString("") { "%02x".format(it) }
        keys.keyFor("statements.upload:$hash")
        faults.pendingUploadSeeded()
    }
}
