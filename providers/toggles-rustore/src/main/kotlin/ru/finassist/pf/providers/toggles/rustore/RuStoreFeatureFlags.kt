package ru.finassist.pf.providers.toggles.rustore

import android.content.Context
import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.rustore.sdk.remoteconfig.Account
import ru.rustore.sdk.remoteconfig.AppId
import ru.rustore.sdk.remoteconfig.ConfigRequestParameter
import ru.rustore.sdk.remoteconfig.ConfigRequestParameterProvider
import ru.rustore.sdk.remoteconfig.Language
import ru.rustore.sdk.remoteconfig.RemoteConfig
import ru.rustore.sdk.remoteconfig.RemoteConfigClient
import ru.rustore.sdk.remoteconfig.RemoteConfigClientBuilder
import ru.rustore.sdk.remoteconfig.RemoteConfigClientEventListener
import ru.rustore.sdk.remoteconfig.RemoteConfigException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Flags from RuStore Remote Config. Targeting/AB is configured in the RuStore console by `account` (our
 * `user_id` after sign-in, nothing before it — the SDK then keys by its own device id).
 *
 * The SDK's default update behaviour (persisted config, background sync every 15 minutes) serves the persisted config immediately and syncs in the background, so a
 * cold start never waits for the network; [changes] fires when a fresher config lands. Values are snapshotted
 * into a map on every update, so [isEnabled] is a cheap synchronous read. A blank app id (local builds
 * without keys) disables the SDK — code defaults apply.
 */
@Singleton
class RuStoreFeatureFlags @Inject constructor(
    @ApplicationContext context: Context,
    @Named("rustoreRemoteConfigAppId") appId: String,
) : FeatureFlags {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile private var values: Map<String, Boolean> = emptyMap()
    @Volatile private var account: String? = null

    private val client: RemoteConfigClient? = appId.takeIf { it.isNotBlank() }?.let { id ->
        runCatching {
            RemoteConfigClientBuilder(appId = AppId(id), context = context)
                .setConfigRequestParameterProvider(
                    object : ConfigRequestParameterProvider {
                        override fun getConfigRequestParameter(): ConfigRequestParameter = ConfigRequestParameter(
                            language = Language(Locale.getDefault().language),
                            account = account?.let { Account(it) },
                        )
                    },
                )
                .setRemoteConfigClientEventListener(
                    object : RemoteConfigClientEventListener {
                        override fun backgroundJobErrors(exception: RemoteConfigException.BackgroundConfigUpdateError) = Unit
                        override fun firstLoadComplete() = reload()
                        override fun initComplete() = reload()
                        override fun memoryCacheUpdated() = reload()
                        override fun persistentStorageUpdated() = reload()
                        override fun remoteConfigNetworkRequestFailure(throwable: Throwable) = Unit
                    },
                )
                .build()
                .also { it.init() }
        }.onFailure { Log.w(TAG, "Remote Config disabled", it) }.getOrNull()
    }

    override fun isEnabled(flag: Flag): Boolean = values[flag.key] ?: flag.defaultValue

    override val changes: Flow<Unit> = changesFlow

    override suspend fun setUser(userId: String?) {
        if (account == userId) return
        account = userId
        refresh()
    }

    override suspend fun refresh() {
        val config = client?.let { fetch(it) } ?: return
        snapshot(config)
    }

    private fun reload() {
        scope.launch { refresh() }
    }

    private suspend fun fetch(client: RemoteConfigClient): RemoteConfig? = suspendCancellableCoroutine { cont ->
        client.getRemoteConfig()
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resume(null) }
    }

    /** Only known keys of boolean type are read; a wrong type in the console falls back to the code default. */
    private fun snapshot(config: RemoteConfig) {
        values = Flag.entries.mapNotNull { flag ->
            if (!config.containsKey(flag.key)) return@mapNotNull null
            runCatching { flag.key to config.getBoolean(flag.key) }.getOrNull()
        }.toMap()
        changesFlow.tryEmit(Unit)
    }

    private companion object {
        const val TAG = "PfFlags"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RuStoreTogglesModule {
    @Binds abstract fun flags(impl: RuStoreFeatureFlags): FeatureFlags
}
