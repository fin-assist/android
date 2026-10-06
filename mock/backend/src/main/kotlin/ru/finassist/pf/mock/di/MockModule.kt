package ru.finassist.pf.mock.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.core.api.AnalyticsApi
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.CategoriesApi
import ru.finassist.pf.core.api.OperationsApi
import ru.finassist.pf.core.api.PfApi
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.mock.MockBackend
import ru.finassist.pf.mock.MockPfApi
import javax.inject.Singleton

/** Flavor `mock`: every `:core:api` service is served in-process. */
@Module
@InstallIn(SingletonComponent::class)
object MockModule {
    @Provides @Singleton
    fun mockApi(@ApplicationContext context: Context, clock: Clock, tokenStore: TokenStore): MockPfApi = MockPfApi(context, clock, tokenStore)

    @Provides @Singleton fun backend(api: MockPfApi): MockBackend = api.backend
    @Provides @Singleton fun pfApi(api: MockPfApi): PfApi = api
    @Provides @Singleton fun auth(api: MockPfApi): AuthApi = api.auth
    @Provides @Singleton fun profile(api: MockPfApi): ProfileApi = api.profile
    @Provides @Singleton fun statements(api: MockPfApi): StatementsApi = api.statements
    @Provides @Singleton fun operations(api: MockPfApi): OperationsApi = api.operations
    @Provides @Singleton fun categories(api: MockPfApi): CategoriesApi = api.categories
    @Provides @Singleton fun analytics(api: MockPfApi): AnalyticsApi = api.analytics
    @Provides @Singleton fun assistant(api: MockPfApi): AssistantApi = api.assistant
}
