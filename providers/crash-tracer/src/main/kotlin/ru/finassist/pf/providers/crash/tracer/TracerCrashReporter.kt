package ru.finassist.pf.providers.crash.tracer

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.tracking.Breadcrumb
import ru.finassist.pf.core.tracking.CrashKey
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.CrashValue
import ru.finassist.pf.core.tracking.NonFatal
import ru.finassist.pf.core.tracking.ScrubbedThrowable
import ru.finassist.pf.core.tracking.Severity
import ru.ok.tracer.Tracer
import ru.ok.tracer.crash.report.TracerCrashReport
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import ru.ok.tracer.Severity as TracerSeverity

/**
 * [CrashReporter] on Tracer. Crashes and ANRs are collected by the SDK itself once it starts
 * ([CrashReportingApplication]); this class adds the user id, the context keys, breadcrumbs and non-fatal errors.
 *
 * The only place in the project that calls the SDK. In a build without an app token the SDK is never started
 * (its startup provider is disabled in the manifest) and every method here does nothing: calling the SDK then
 * would throw «Tracer is not initialized».
 */
@Singleton
class TracerCrashReporter internal constructor(
    private val enabled: Boolean,
    private val tracer: TracerGateway,
) : CrashReporter {

    @Inject constructor(@Named(CRASH_REPORTING_ENABLED) enabled: Boolean) : this(enabled, SdkTracerGateway)

    override fun report(error: NonFatal) = guarded {
        tracer.report(error.severity.toTracer(), ScrubbedThrowable.of(error.cause, error.code?.value), error.issueKey)
    }

    override fun breadcrumb(crumb: Breadcrumb) = guarded { tracer.log(crumb.render()) }

    override fun setUserId(userId: String?) = guarded { tracer.setUserId(userId) }

    override fun setKey(key: CrashKey, value: CrashValue?) = guarded { tracer.setKey(key.name, value?.value) }

    /** Reporting must never break the app: an SDK failure is swallowed, there is nowhere better to send it. */
    private inline fun guarded(call: () -> Unit) {
        if (!enabled) return
        runCatching(call)
    }

    private fun Severity.toTracer(): TracerSeverity = when (this) {
        Severity.WARNING -> TracerSeverity.WARNING
        Severity.ERROR -> TracerSeverity.ERROR
        Severity.FATAL -> TracerSeverity.FATAL
    }

    companion object {
        /** Hilt name of the Boolean «this build has a Tracer app token» (`BuildConfig.CRASH_REPORTING_ENABLED`). */
        const val CRASH_REPORTING_ENABLED = "crashReportingEnabled"
    }
}

/** The SDK calls [TracerCrashReporter] makes; a seam for unit tests (the SDK is static and needs a device). */
internal interface TracerGateway {
    fun report(severity: TracerSeverity, throwable: Throwable, issueKey: String)
    fun log(message: String)
    fun setUserId(userId: String?)
    fun setKey(key: String, value: String?)
}

internal object SdkTracerGateway : TracerGateway {
    override fun report(severity: TracerSeverity, throwable: Throwable, issueKey: String) =
        TracerCrashReport.report(severity, throwable, issueKey)

    override fun log(message: String) = TracerCrashReport.log(message)
    override fun setUserId(userId: String?) = Tracer.setUserId(userId)
    override fun setKey(key: String, value: String?) = Tracer.setKey(key, value)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class TracerModule {
    @Binds @IntoSet abstract fun crashReporter(impl: TracerCrashReporter): CrashReporter
}
