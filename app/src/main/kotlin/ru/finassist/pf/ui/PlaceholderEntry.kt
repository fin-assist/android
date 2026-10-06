package ru.finassist.pf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfEmptyState
import ru.finassist.pf.core.designsystem.components.PfPageHeader
import ru.finassist.pf.core.designsystem.components.PfTabHeader
import ru.finassist.pf.core.designsystem.icons.PfIcons
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.feature.analytics.api.AnalyticsRoutes
import ru.finassist.pf.feature.applock.api.AppLockRoutes
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.operations.api.OperationsRoutes
import ru.finassist.pf.feature.profile.api.ProfileRoutes
import ru.finassist.pf.feature.statements.api.StatementsRoutes
import javax.inject.Inject

/**
 * Stage-4 stand-ins for the tab roots and the upload screen, so the shell is navigable end to end. Each
 * screen is replaced by its feature's entry in stages 5–7 and this file is deleted then.
 */
class PlaceholderEntry @Inject constructor(private val session: SessionRepository) : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator) {
        composable<OperationsRoutes.Feed> {
            Tab("Операции") {
                PfEmptyState(PfIcons.LIST, "Операции — этап 5", "Лента, поиск и фильтры появятся на следующем этапе") {
                    PfButton("Загрузить выписку", onClick = { navigator.navigate(StatementsRoutes.Upload()) }, variant = ButtonVariant.PRIMARY)
                }
            }
        }
        composable<AnalyticsRoutes.Home> {
            Tab("Аналитика") { PfEmptyState(PfIcons.BAR_CHART, "Аналитика — этап 6") }
        }
        composable<ProfileRoutes.Home> {
            val scope = rememberCoroutineScope()
            Tab("Профиль") {
                PfEmptyState(PfIcons.USER, "Профиль — этап 7") {
                    PfButton("Код-пароль и биометрия", onClick = { navigator.navigate(AppLockRoutes.Security) })
                    PfButton("Выйти из аккаунта", onClick = { scope.launch { session.signOut(reason = "logged_out") } }, variant = ButtonVariant.GHOST)
                }
            }
        }
        composable<StatementsRoutes.Upload> { entry ->
            val route = entry.toRoute<StatementsRoutes.Upload>()
            Column(Modifier.fillMaxSize()) {
                PfPageHeader(if (route.firstRun) "Шаг 4 из 4" else "Загрузка выписки", onBack = if (route.firstRun) null else ({ navigator.back() }))
                PfEmptyState(PfIcons.FILE_TEXT, "Загрузка выписки — этап 5") {
                    if (route.firstRun) PfButton("Позже", onClick = { navigator.popUpTo(OperationsRoutes.Feed, inclusive = false) })
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
