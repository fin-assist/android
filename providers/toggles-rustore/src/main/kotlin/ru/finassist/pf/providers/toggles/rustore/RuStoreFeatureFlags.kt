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
import ru.finassist.pf.core.toggles.Experiment
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.RemoteValue
import ru.finassist.pf.core.toggles.flagsVersion
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

    /** One immutable snapshot: a reader never sees new flags with old variants. */
    private class Config(
        val values: Map<String, Boolean> = emptyMap(),
        val variants: Map<String, String> = emptyMap(),
        val strings: Map<String, String> = emptyMap(),
    )

    @Volatile private var config = Config()
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

    override fun isEnabled(flag: Flag): Boolean = config.values[flag.key] ?: flag.defaultValue

    override fun variant(experiment: Experiment): String = experiment.variantOf(config.variants[experiment.key])

    override fun value(key: RemoteValue): String = config.strings[key.key] ?: key.defaultValue

    override val changes: Flow<Unit> = changesFlow

    override val version: String get() = config.let { flagsVersion(it.values, it.variants) }

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
        val values = Flag.entries.mapNotNull { flag ->
            if (!config.containsKey(flag.key)) return@mapNotNull null
            runCatching { flag.key to config.getBoolean(flag.key) }.getOrNull()
        }.toMap()
        // Experiment variants are strings (FIN-9); a missing or unknown one is the default variant.
        val variants = Experiment.entries.mapNotNull { e ->
            if (!config.containsKey(e.key)) return@mapNotNull null
            runCatching { e.key to config.getString(e.key) }.getOrNull()
        }.toMap()
        val strings = RemoteValue.entries.mapNotNull { v ->
            if (!config.containsKey(v.key)) return@mapNotNull null
            runCatching { v.key to config.getString(v.key) }.getOrNull()
        }.toMap()
        this.config = Config(values, variants, strings)
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
