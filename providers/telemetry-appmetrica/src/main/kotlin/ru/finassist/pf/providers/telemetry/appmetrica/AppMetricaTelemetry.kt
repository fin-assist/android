package ru.finassist.pf.providers.telemetry.appmetrica

import android.content.Context
import android.content.pm.ApplicationInfo
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig
import ru.finassist.pf.core.tracking.Breadcrumb
import ru.finassist.pf.core.tracking.CrashKey
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.CrashValue
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.NonFatal
import ru.finassist.pf.core.tracking.ScrubbedThrowable
import ru.finassist.pf.core.tracking.Tracker
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
internal class AppMetricaRuntime(
    private val enabled: Boolean,
    private val gateway: AppMetricaGateway,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        @Named("appMetricaApiKey") apiKey: String,
        @Named("appMetricaSessions") sessions: Boolean,
    ) : this(activateAppMetrica(context, apiKey, sessions, SdkGateway), SdkGateway)

    fun call(action: AppMetricaGateway.() -> Unit) {
        if (enabled) runCatching { gateway.action() }
    }
}

internal fun appMetricaConfig(apiKey: String, sessions: Boolean): AppMetricaConfig =
    AppMetricaConfig.newConfigBuilder(apiKey)
        .withCrashReporting(true)
        .withNativeCrashReporting(false)
        .withCrashTransformer(::scrubCrash)
        .withAnrMonitoring(true)
        .withLocationTracking(false)
        .withAdvIdentifiersTracking(false)
        .withRevenueAutoTrackingEnabled(false)
        .withAppOpenTrackingEnabled(false)
        .withSessionsAutoTrackingEnabled(sessions)
        .build()

// Drop a crash if it cannot be scrubbed, even under memory pressure.
internal fun scrubCrash(error: Throwable): Throwable? = runCatching { ScrubbedThrowable.of(error) }.getOrNull()

class AppMetricaTracker @Inject internal constructor(private val runtime: AppMetricaRuntime) : Tracker {
    override fun track(event: Event, params: Map<String, String>) = runtime.call {
        event(event.name, appMetricaEventParams(event, params))
    }
}

@Singleton
internal class AppMetricaCrashReporter @Inject constructor(private val runtime: AppMetricaRuntime) : CrashReporter {
    private val breadcrumbs = ArrayDeque<String>()

    override fun report(error: NonFatal) = runtime.call {
        error(error.issueKey, error.severity.name, ScrubbedThrowable.of(error.cause, error.code?.value))
    }

    @Synchronized
    override fun breadcrumb(crumb: Breadcrumb) = runtime.call {
        if (breadcrumbs.size == BREADCRUMB_LIMIT) breadcrumbs.removeFirst()
        breadcrumbs.addLast(crumb.render())
        context("breadcrumbs", breadcrumbs.joinToString("\n"))
    }

    override fun setKey(key: CrashKey, value: CrashValue?) = runtime.call {
        context(key.name, value?.value)
    }

    private companion object {
        const val BREADCRUMB_LIMIT = 6
    }
}

internal interface AppMetricaGateway {
    fun activate(context: Context, config: AppMetricaConfig)
    fun clearErrorEnvironment()
    fun clearProfile()
    fun event(name: String, params: Map<String, String>)
    fun error(issueKey: String, severity: String, cause: Throwable)
    fun context(key: String, value: String?)
}

internal fun activateAppMetrica(
    context: Context,
    apiKey: String,
    sessions: Boolean,
    gateway: AppMetricaGateway,
): Boolean = apiKey.isNotBlank() && runCatching {
    discardLegacyAppMetricaReports(context.noBackupFilesDir, context.packageName)
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
        ScrubbingExceptionHandler.install()
    }
    gateway.clearErrorEnvironment()
    gateway.activate(context, appMetricaConfig(apiKey, sessions))
    gateway.clearProfile()
}.isSuccess

private object SdkGateway : AppMetricaGateway {
    override fun activate(context: Context, config: AppMetricaConfig) = AppMetrica.activate(context, config)

    override fun clearErrorEnvironment() = AppMetrica.clearErrorEnvironment()

    override fun clearProfile() = AppMetrica.setUserProfileID(null)

    override fun event(name: String, params: Map<String, String>) {
        if (params.isEmpty()) AppMetrica.reportEvent(name) else AppMetrica.reportEvent(name, params)
    }

    override fun error(issueKey: String, severity: String, cause: Throwable) =
        AppMetrica.reportError(issueKey, severity, cause)

    override fun context(key: String, value: String?) = AppMetrica.putErrorEnvironmentValue(key, value)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppMetricaModule {
    @Binds @IntoSet abstract fun crashReporter(impl: AppMetricaCrashReporter): CrashReporter
}
