package ru.finassist.pf.providers.toggles.varioqub

import android.content.Context
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yandex.varioqub.analyticadapter.AdapterIdentifiersCallback
import com.yandex.varioqub.analyticadapter.VarioqubConfigAdapter
import com.yandex.varioqub.analyticadapter.data.ConfigData
import com.yandex.varioqub.appmetricaadapter.AppMetricaAdapter
import com.yandex.varioqub.config.FetchError
import com.yandex.varioqub.config.OnFetchCompleteListener
import com.yandex.varioqub.config.Varioqub
import com.yandex.varioqub.config.VarioqubSettings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.finassist.pf.core.toggles.Experiment
import ru.finassist.pf.core.toggles.FLAGS_VERSION_DEFAULTS
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.RemoteValue
import ru.finassist.pf.core.toggles.flagsVersion
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.minutes

/**
 * Loads the cached config before fetching and holds the last applied snapshot on failure.
 * It refreshes while the app is in use so an outdated minimum version can be lifted without a restart.
 */
@Singleton
internal class VarioqubFeatureFlags(private val client: ConfigClient?) : FeatureFlags {
    @Inject constructor(
        @ApplicationContext context: Context,
        @Named("varioqubAppId") appId: String,
        @Named("appMetricaActive") analyticsActive: Boolean,
    ) : this(SdkConfigClient.create(context, appId, analyticsActive)) {
        if (client != null) {
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
                ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (isActive) {
                        refresh()
                        delay(REFRESH_INTERVAL)
                    }
                }
            }
        }
    }

    private val updates = Mutex()
    private val changesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile private var config: ConfigSnapshot? = null

    override fun isEnabled(flag: Flag): Boolean = config?.values?.get(flag.key) ?: flag.defaultValue

    override fun variant(experiment: Experiment): String = experiment.variantOf(config?.variants?.get(experiment.key))

    override fun value(key: RemoteValue): String = config?.strings?.get(key.key) ?: key.defaultValue

    override val changes: Flow<Unit> = changesFlow

    override val version: String get() = config?.let { flagsVersion(it.values, it.variants) } ?: FLAGS_VERSION_DEFAULTS

    override val loaded: Boolean get() = client == null || config != null

    override suspend fun refresh() {
        val client = client ?: return
        updates.withLock {
            try {
                // Also picks up a download that finished after the previous foreground scope was cancelled.
                apply(client, fetched = false)
                if (client.fetch()) apply(client, fetched = true)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Log.w(TAG, "Remote config refresh failed")
            }
        }
    }

    private suspend fun apply(client: ConfigClient, fetched: Boolean) {
        val keys = client.activate()
        // An empty cache is indistinguishable from no cache. Keep the persisted minimum until a fetch confirms it.
        if (!fetched && keys.isEmpty()) return

        val snapshot = ConfigSnapshot.of { key -> if (key in keys) client.read(key) else null }
        if (config != snapshot) {
            config = snapshot
            changesFlow.tryEmit(Unit)
        }
    }
}

internal interface ConfigClient {
    suspend fun activate(): Set<String>
    suspend fun fetch(): Boolean
    fun read(key: String): String?
}

private object SdkConfigClient : ConfigClient {
    fun create(context: Context, appId: String, analyticsActive: Boolean): ConfigClient? {
        if (appId.isBlank()) return null
        return runCatching {
            val settings = VarioqubSettings.Builder("appmetrica.$appId")
                .withThrottleInterval(REFRESH_INTERVAL.inWholeSeconds)
                .build()
            val adapter = if (analyticsActive) AppMetricaAdapter(context) else ConfigOnlyAdapter
            Varioqub.init(settings, adapter, context)
            this
        }.onFailure { Log.w(TAG, "Remote config initialization failed") }.getOrNull()
    }

    override suspend fun activate(): Set<String> {
        suspendCancellableCoroutine<Unit> { continuation ->
            Varioqub.activateConfig { continuation.resume(Unit) }
        }
        return Varioqub.getAllKeys()
    }

    override suspend fun fetch(): Boolean = suspendCancellableCoroutine { continuation ->
        Varioqub.fetchConfig(object : OnFetchCompleteListener {
            override fun onSuccess() = continuation.resume(true)
            override fun onError(message: String, error: FetchError) = continuation.resume(false)
        })
    }

    override fun read(key: String): String? = Varioqub.getValue(key)?.value
}

private const val TAG = "PfFlags"
private val REFRESH_INTERVAL = 15.minutes

/** Varioqub can fetch flags without activating AppMetrica or asking it for identifiers. */
private object ConfigOnlyAdapter : VarioqubConfigAdapter {
    override val adapterName = "ConfigOnly"
    override fun requestDeviceId(callback: AdapterIdentifiersCallback) = callback.onSuccess("")
    override fun requestUserId(callback: AdapterIdentifiersCallback) = callback.onSuccess("")
    override fun reportConfigChanged(configData: ConfigData) = Unit
    override fun setExperiments(experiments: String) = Unit
    override fun setTriggeredTestIds(triggeredTestIds: Set<Long>) = Unit
}

/** A snapshot keeps flags, experiment variants and other strings consistent at publication. */
internal data class ConfigSnapshot(
    val values: Map<String, Boolean>,
    val variants: Map<String, String>,
    val strings: Map<String, String>,
) {
    companion object {
        fun of(read: (String) -> String?): ConfigSnapshot {
            fun <T> readKeys(keys: List<String>, value: (String) -> T?): Map<String, T> =
                keys.mapNotNull { key -> value(key)?.let { key to it } }.toMap()

            return ConfigSnapshot(
                values = readKeys(Flag.entries.map { it.key }) { read(it)?.toBooleanStrictOrNull() },
                variants = readKeys(Experiment.entries.map { it.key }, read),
                strings = readKeys(RemoteValue.entries.map { it.key }, read),
            )
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class VarioqubTogglesModule {
    @Binds abstract fun flags(impl: VarioqubFeatureFlags): FeatureFlags
}
