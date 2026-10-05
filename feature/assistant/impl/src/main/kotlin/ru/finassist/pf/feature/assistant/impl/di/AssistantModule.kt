package ru.finassist.pf.feature.assistant.impl.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.feature.assistant.api.AssistantLimitRepository
import ru.finassist.pf.feature.assistant.impl.data.AssistantRepository
import ru.finassist.pf.feature.assistant.impl.ui.AssistantEntry

@Module
@InstallIn(SingletonComponent::class)
abstract class AssistantModule {
    @Binds abstract fun limitRepository(impl: AssistantRepository): AssistantLimitRepository
    @Binds @IntoSet abstract fun entry(impl: AssistantEntry): FeatureEntry
}
