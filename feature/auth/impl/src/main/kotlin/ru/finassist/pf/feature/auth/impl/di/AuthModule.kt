package ru.finassist.pf.feature.auth.impl.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.network.client.SessionTokens
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.impl.data.SecureTokenStore
import ru.finassist.pf.feature.auth.impl.domain.SessionRepositoryImpl
import ru.finassist.pf.feature.auth.impl.ui.AuthEntry

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {
    @Binds abstract fun sessionRepository(impl: SessionRepositoryImpl): SessionRepository
    @Binds abstract fun sessionTokens(impl: SecureTokenStore): SessionTokens
    @Binds @IntoSet abstract fun entry(impl: AuthEntry): FeatureEntry
}
