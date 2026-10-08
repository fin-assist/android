package ru.finassist.pf.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.BuildConfig
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.ShiftedClock
import ru.finassist.pf.core.common.time.SystemClock
import java.time.OffsetDateTime
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** The e2e build pins "now" ([BuildConfig.E2E_NOW]) so UI tests over the fixture statement are reproducible. */
    @Provides
    @Singleton
    fun clock(): Clock =
        if (BuildConfig.E2E_NOW.isEmpty()) SystemClock() else ShiftedClock(OffsetDateTime.parse(BuildConfig.E2E_NOW))
}
