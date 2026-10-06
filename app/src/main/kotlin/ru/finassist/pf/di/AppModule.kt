package ru.finassist.pf.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.SystemClock
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.ui.PlaceholderEntry
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun clock(): Clock = SystemClock()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppEntries {
    /** Stage-4 placeholders for screens of later stages; removed when their features land. */
    @Binds @IntoSet
    abstract fun placeholders(impl: PlaceholderEntry): FeatureEntry
}
