package ru.finassist.pf.core.tracking.log.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.core.tracking.log.LogTracker

@Module
@InstallIn(SingletonComponent::class)
abstract class LogTrackingModule {
    @Binds abstract fun tracker(impl: LogTracker): Tracker
    @Binds abstract fun crashReporter(impl: LogTracker): CrashReporter
}
