package ru.finassist.pf.feature.assistant.impl.di

import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.feature.analytics.api.AnalyticsRoutes
import ru.finassist.pf.feature.assistant.api.AssistantRoutes
import ru.finassist.pf.feature.assistant.impl.ui.AiConsentScreen
import ru.finassist.pf.feature.assistant.impl.ui.ChatScreen
import ru.finassist.pf.feature.assistant.impl.ui.LocalAssistantEnabled
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import javax.inject.Inject

class AssistantEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator) {
        composable<AssistantRoutes.Chat> { entry ->
            // The tab and all restored/new child entries share one main-layer snapshot before VM creation.
            val enabled = LocalAssistantEnabled.current
            if (!enabled) {
                LaunchedEffect(entry) { navigator.finishTo(OperationsRoutes.Feed) }
            } else {
                val handle = entry.savedStateHandle
                ChatScreen(
                    onBack = { navigator.back() },
                    onOpenConsent = { navigator.navigate(AssistantRoutes.Consent) },
                    onOpenSearch = { filter -> navigator.navigate(OperationsRoutes.Search(filter)) },
                    onOpenAnalytics = { params -> navigator.navigate(AnalyticsRoutes.Period(params)) },
                    consentResult = produceState<String?>(null, handle) {
                        handle.getStateFlow<String?>(AssistantRoutes.CONSENT_RESULT, null).collect { value = it }
                    },
                    onConsentResultConsumed = { handle[AssistantRoutes.CONSENT_RESULT] = null },
                )
            }
        }
        composable<AssistantRoutes.Consent> { entry ->
            val enabled = LocalAssistantEnabled.current
            if (!enabled) {
                LaunchedEffect(entry) { navigator.finishTo(OperationsRoutes.Feed) }
            } else {
                AiConsentScreen(
                    onBack = { navigator.back() },
                    onGranted = { navigator.returnResult(AssistantRoutes.CONSENT_RESULT, "true") },
                )
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AssistantModule {
    @Binds @IntoSet
    abstract fun entry(impl: AssistantEntry): FeatureEntry
}
