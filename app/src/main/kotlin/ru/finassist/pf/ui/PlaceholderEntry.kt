package ru.finassist.pf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import kotlinx.coroutines.launch
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.components.PfTabHeader
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.feature.assistant.api.AssistantRoutes
import ru.finassist.pf.feature.applock.api.AppLockRoutes
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.profile.api.ProfileRoutes
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import javax.inject.Inject

/**
 * Stand-ins for the assistant chat and the profile tab root, so the shell is navigable end to end. Replaced by the
 * features' own entries in stage 7; this file is deleted then.
 */
class PlaceholderEntry @Inject constructor(private val session: SessionRepository) : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator) {
        composable<AssistantRoutes.Chat> {
            Column(Modifier.fillMaxSize()) {
                PfPageHeader("Помощник", onBack = { navigator.back() })
                PfEmptyState(PfIcons.MESSAGE, "Помощник — этап 7")
            }
        }
        composable<ProfileRoutes.Home> {
            val scope = rememberCoroutineScope()
            Tab("Профиль") {
                PfEmptyState(PfIcons.USER, "Профиль — этап 7") {
                    PfButton("История загрузок", onClick = { navigator.navigate(StatementsRoutes.History) })
                    PfButton("Код-пароль и биометрия", onClick = { navigator.navigate(AppLockRoutes.Security) })
                    PfButton("Выйти из аккаунта", onClick = { scope.launch { session.signOut(reason = "logged_out") } }, variant = ButtonVariant.GHOST)
                }
            }
        }
    }
}

@Composable
private fun Tab(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        PfTabHeader(title)
        content()
    }
}
