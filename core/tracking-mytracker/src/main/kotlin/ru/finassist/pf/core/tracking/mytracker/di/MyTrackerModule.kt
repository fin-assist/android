package ru.finassist.pf.core.tracking.mytracker.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.core.tracking.mytracker.MyTrackerTracker

@Module
@InstallIn(SingletonComponent::class)
abstract class MyTrackerModule {
    @Binds abstract fun tracker(impl: MyTrackerTracker): Tracker
    @Binds abstract fun crashReporter(impl: MyTrackerTracker): CrashReporter
}
