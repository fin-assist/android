package ru.finassist.pf.core.toggles.rustore

import android.app.Application
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.Flags
import ru.finassist.pf.core.tracking.AppInitializer
import ru.rustore.sdk.remoteconfig.Account
import ru.rustore.sdk.remoteconfig.AppId
import ru.rustore.sdk.remoteconfig.ConfigRequestParameter
import ru.rustore.sdk.remoteconfig.ConfigRequestParameterProvider
import ru.rustore.sdk.remoteconfig.Language
import ru.rustore.sdk.remoteconfig.RemoteConfig
import ru.rustore.sdk.remoteconfig.RemoteConfigClient
import ru.rustore.sdk.remoteconfig.RemoteConfigClientBuilder
import ru.rustore.sdk.remoteconfig.UpdateBehaviour
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.minutes

/**
 * RuStore Remote Config provider. Flags are boolean parameters named exactly as in [Flags]; a parameter that is
 * missing in the config keeps its code default. `account` for segmentation is our user id after login.
 * With an empty [appId] (not configured yet) the provider stays silent and defaults apply.
 */
@Singleton
class RuStoreFeatureFlags @Inject constructor(
    @Named("rustoreAppId") private val appId: String,
) : FeatureFlags, AppInitializer {
    private val values = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    @Volatile private var userId: String? = null
    @Volatile private var client: RemoteConfigClient? = null

    override fun initialize(application: Application) {
        if (appId.isBlank()) { Log.w(TAG, "RuStore app id is empty — remote flags disabled"); return }
        client = RemoteConfigClientBuilder(appId = AppId(appId), context = application)
            // Persistent cache refreshed in the background; a fresh value also lands on every explicit refresh.
            .setUpdateBehaviour(UpdateBehaviour.Default(15.minutes))
            .setConfigRequestParameterProvider(object : ConfigRequestParameterProvider {
                override fun getConfigRequestParameter(): ConfigRequestParameter = ConfigRequestParameter(
                    language = Language(Locale.getDefault().language),
                    account = userId?.let { Account(it) },
                )
            })
            .build()
            .also { it.init() }
    }

    override fun isEnabled(flag: Flag): Boolean = values.value[flag.key] ?: flag.default
    override fun observe(flag: Flag): Flow<Boolean> = values.map { it[flag.key] ?: flag.default }.distinctUntilChanged()

    override suspend fun refresh() {
        val c = client ?: return
        val config = suspendCancellableCoroutine<RemoteConfig?> { cont ->
            c.getRemoteConfig()
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { e -> Log.w(TAG, "remote config fetch failed", e); if (cont.isActive) cont.resume(null) }
        } ?: return
        values.value = Flags.all.mapNotNull { f ->
            runCatching { if (config.containsKey(f.key)) f.key to config.getBoolean(f.key) else null }.getOrNull()
        }.toMap()
    }

    override suspend fun setUser(userId: String?) { this.userId = userId; refresh() }

    private companion object { const val TAG = "PfFlags" }
}
