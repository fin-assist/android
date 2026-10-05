package ru.finassist.pf.feature.profile.impl.ui

import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import ru.finassist.pf.core.navigation.DebugMenu
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.feature.applock.api.AppLockRoutes
import ru.finassist.pf.feature.assistant.api.AssistantRoutes
import ru.finassist.pf.feature.profile.api.ProfileRoutes
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import java.util.Optional
import javax.inject.Inject

class ProfileEntry @Inject constructor(private val debugMenu: Optional<DebugMenu>) : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator, navController: NavController) {
        composable<ProfileRoutes.Profile> {
            val vm: ProfileViewModel = hiltViewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            // Flags and server data are re-read on every return to the screen (flag changes apply at screen boundaries).
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshFlags(); vm.load() }
            ProfileScreen(
                state = state,
                onHistory = { navigator.navigate(StatementsRoutes.UploadHistory) },
                onChat = { navigator.navigate(AssistantRoutes.Chat()) },
                onAssistantConsent = vm::onAssistantConsentToggle,
                onSecurity = { navigator.navigate(AppLockRoutes.Security) },
                onThemeSheet = vm::onThemeSheet,
                onTheme = vm::onTheme,
                onLogout = vm::logout,
                onDelete = { navigator.navigate(AppLockRoutes.Confirm(ProfileViewModel.CONFIRM_DELETE, "Удаление аккаунта")) },
                onDeleteDismiss = vm::onDeleteDismiss,
                onDeleteConfirm = vm::deleteAccount,
                onDebug = debugMenu.orElse(null)?.let { menu -> { navigator.navigate(menu.route()) } },
            )
        }
    }
}
