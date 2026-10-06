package ru.finassist.pf.providers.crash.appmetrica

import android.app.Application
import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig
import ru.finassist.pf.core.tracking.CrashReporter
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Crashes, ANR and non-fatal errors in AppMetrica. Product events go to MyTracker, not here, so the two SDKs
 * do not double-count. [log] keeps the last breadcrumbs and attaches them to the error environment, so a
 * crash report shows what happened just before it. Must be created in `Application.onCreate` — the app
 * injects it there. A blank API key (local builds) turns reporting into a no-op.
 */
@Singleton
class AppMetricaCrashReporter @Inject constructor(
    @ApplicationContext context: Context,
    @Named("appmetricaApiKey") apiKey: String,
) : CrashReporter {

    private val enabled: Boolean = apiKey.isNotBlank()
    private val breadcrumbs = ArrayDeque<String>()

    init {
        if (enabled) {
            val config = AppMetricaConfig.newConfigBuilder(apiKey)
                .withCrashReporting(true)
                .withNativeCrashReporting(true)
                .withAnrMonitoring(true)
                .withLocationTracking(false)
                .build()
            val app = context.applicationContext as Application
            AppMetrica.activate(app, config)
            AppMetrica.enableActivityAutoTracking(app)
        }
    }

    override fun report(throwable: Throwable, message: String?) {
        if (!enabled) return
        AppMetrica.reportError(message ?: throwable.javaClass.simpleName, throwable)
    }

    @Synchronized
    override fun log(message: String) {
        if (!enabled) return
        if (breadcrumbs.size == MAX_BREADCRUMBS) breadcrumbs.removeFirst()
        breadcrumbs.addLast(message.take(MAX_LENGTH))
        AppMetrica.putErrorEnvironmentValue(KEY_BREADCRUMBS, breadcrumbs.joinToString(" | "))
    }

    override fun setUserId(userId: String?) {
        if (!enabled) return
        AppMetrica.setUserProfileID(userId)
    }

    private companion object {
        const val MAX_BREADCRUMBS = 20
        const val MAX_LENGTH = 120
        const val KEY_BREADCRUMBS = "breadcrumbs"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppMetricaModule {
    @Binds abstract fun crashReporter(impl: AppMetricaCrashReporter): CrashReporter
}
