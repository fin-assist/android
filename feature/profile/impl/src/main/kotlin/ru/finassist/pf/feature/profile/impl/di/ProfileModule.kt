package ru.finassist.pf.feature.profile.impl.di

import androidx.compose.runtime.produceState
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.feature.applock.api.AppLockRoutes
import ru.finassist.pf.feature.assistant.api.AssistantRoutes
import ru.finassist.pf.feature.profile.api.ProfileRoutes
import ru.finassist.pf.feature.profile.impl.ui.DeleteAccountScreen
import ru.finassist.pf.feature.profile.impl.ui.ProfileActions
import ru.finassist.pf.feature.profile.impl.ui.ProfileScreen
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import javax.inject.Inject

class ProfileEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator) {
        composable<ProfileRoutes.Home> {
            ProfileScreen(
                ProfileActions(
                    openHistory = { navigator.navigate(StatementsRoutes.History) },
                    openUpload = { navigator.navigate(StatementsRoutes.Upload()) },
                    openChat = { navigator.navigate(AssistantRoutes.Chat()) },
                    openConsent = { navigator.navigate(AssistantRoutes.Consent) },
                    openSecurity = { navigator.navigate(AppLockRoutes.Security) },
                    openDeleteAccount = { navigator.navigate(ProfileRoutes.DeleteAccount) },
                ),
            )
        }
        composable<ProfileRoutes.DeleteAccount> { entry ->
            val handle = entry.savedStateHandle
            DeleteAccountScreen(
                onBack = { navigator.back() },
                onConfirmPasscode = { navigator.navigate(AppLockRoutes.Confirm) },
                passcodeResult = produceState<String?>(null, handle) {
                    handle.getStateFlow<String?>(AppLockRoutes.Confirm.RESULT_CONFIRMED, null).collect { value = it }
                },
                onPasscodeResultConsumed = { handle[AppLockRoutes.Confirm.RESULT_CONFIRMED] = null },
            )
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ProfileModule {
    @Binds @IntoSet
    abstract fun entry(impl: ProfileEntry): FeatureEntry
}
