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
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.model.ProfileUpdate
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
    @Inject lateinit var session: SessionRepository
    @Inject lateinit var sessionImpl: SessionRepositoryImpl
    @Inject lateinit var appLock: AppLock
    @Inject lateinit var keys: IdempotencyKeys

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val success = try {
                withTimeout(90_000) { apply(intent?.data) }
                true
            } catch (_: Exception) {
                // Never render an exception or URI: it can contain server details or a caller's arbitrary text.
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

    private suspend fun apply(uri: Uri?) {
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
                name in setOf("expect_stream_rate_limited", "expect_stream_rate_limited_exact", "expect_error_requests", "expect_uploads") -> require(value.toIntOrNull() in 0..10_000)
                name == "pending_upload_fixture" -> require(value == "october")
                name == "profile_timezone" -> require(value in setOf("Asia/Vladivostok", "Europe/Moscow"))
                else -> StageFaultPlan.validate(name, value)
            }
        }
        faults.configure(controls.filterKeys { name ->
            name !in setOf("flags", "sign_in", "expect_stream_rate_limited", "expect_stream_rate_limited_exact", "expect_error_requests", "expect_uploads", "pending_upload_fixture", "profile_timezone") && !name.startsWith("flag.")
        })
        controls.forEach { (name, value) ->
            when {
                name == "flags" -> overrides.clear()
                name.startsWith("flag.") -> overrides.set(Flag.byKey(name.removePrefix("flag."))!!, value.toBooleanStrictOrNull())
            }
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
    }
}
