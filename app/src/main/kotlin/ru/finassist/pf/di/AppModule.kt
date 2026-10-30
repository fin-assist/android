package ru.finassist.pf.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.BuildConfig
import ru.finassist.pf.core.common.availability.ClientVersion
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.ReportZone
import ru.finassist.pf.core.common.time.ShiftedClock
import ru.finassist.pf.core.common.time.SystemClock
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.MinVersionCheck
import ru.finassist.pf.core.tracking.CompositeCrashReporter
import ru.finassist.pf.core.tracking.CrashContextSync
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.providers.crash.tracer.TracerCrashReporter
import java.time.OffsetDateTime
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** A test run never forwards verification secrets to a redirect destination outside its fixed origin. */
    @Provides
    @Named("httpFollowRedirects")
    fun httpFollowRedirects(): Boolean = !BuildConfig.STAGE_UI_TEST

    @Provides
    @Singleton
    fun reportZone(): ReportZone = ReportZone()

    /** `X-Client-Version` (FIN-20/22): the release version and the CI build number. */
    @Provides
    fun clientVersion(): ClientVersion = ClientVersion(BuildConfig.CLIENT_VERSION_NAME, BuildConfig.VERSION_CODE)

    /** Update and maintenance screens over the whole app (FIN-22). */
    @Provides
    @Singleton
    fun serviceGate(elapsedClock: ElapsedClock): ServiceGate = ServiceGate(elapsedClock)

    @Provides
    fun minVersionCheck(
        @ApplicationContext context: Context,
        flags: FeatureFlags,
        version: ClientVersion,
        gate: ServiceGate,
    ): MinVersionCheck = MinVersionCheck(flags, version, gate, LastMinVersionPrefs(context))

    /**
     * Days and "today" in the report zone (profile zone, FIN-92). The e2e build pins "now" ([BuildConfig.E2E_NOW]) so
     * UI tests over the fixture statement are reproducible.
     */
    @Provides
    @Singleton
    fun clock(zone: ReportZone): Clock =
        if (BuildConfig.E2E_NOW.isEmpty()) SystemClock(zone::current) else ShiftedClock(OffsetDateTime.parse(BuildConfig.E2E_NOW), zone::current)

    /** Server durations use a boot-based clock that includes sleep and ignores wall-clock changes. */
    @Provides
    @Singleton
    fun elapsedClock(): ElapsedClock = ElapsedClock { android.os.SystemClock.elapsedRealtime() }

    /** `true` when the build has a Tracer app token; otherwise the SDK is not even started. */
    @Provides
    @Named(TracerCrashReporter.CRASH_REPORTING_ENABLED)
    fun crashReportingEnabled(): Boolean = BuildConfig.CRASH_REPORTING_ENABLED

    /**
     * What the app injects as [CrashReporter]: every provider of the flavor at once — Tracer in both, plus logcat
     * in `mock` (providers contribute with `@IntoSet`).
     */
    @Provides
    @Singleton
    fun crashReporter(providers: Set<@JvmSuppressWildcards CrashReporter>): CrashReporter = CompositeCrashReporter(providers)

    @Provides
    @Singleton
    fun crashContext(reporter: CrashReporter, flags: FeatureFlags): CrashContextSync =
        CrashContextSync(reporter, configVersion = { flags.version })
}
