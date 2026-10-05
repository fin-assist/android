package ru.finassist.pf.core.tracking

import android.app.Application

/**
 * Startup hook for SDK providers (RuStore Remote Config, MyTracker, AppMetrica) that must be initialised in
 * `Application.onCreate`. Bound into a set; the mock flavour binds none.
 */
fun interface AppInitializer {
    fun initialize(application: Application)
}
