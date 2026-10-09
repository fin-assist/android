package ru.finassist.pf.e2e

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ru.finassist.pf.mock.MockConfig
import javax.inject.Singleton

/**
 * Fake backend for UI tests: always the anonymized fixture (never a private statement on the tester's
 * machine), and short delays so flows run fast while every intermediate state still shows up.
 */
@Module
@InstallIn(SingletonComponent::class)
object E2eModule {
    @Provides
    @Singleton
    fun mockConfig(): MockConfig = MockConfig(
        callVerifyDelayMs = 1_500,
        networkDelayMs = 50,
        importStepDelayMs = 300,
        assistantChunkDelayMs = 5,
        statementAssets = listOf(MockConfig.FIXTURE_STATEMENT),
    )
}
