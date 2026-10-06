package ru.finassist.pf.feature.auth.impl.di

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.impl.data.SessionRepositoryImpl
import ru.finassist.pf.feature.auth.impl.ui.CallScreen
import ru.finassist.pf.feature.auth.impl.ui.ConsentScreen
import ru.finassist.pf.feature.auth.impl.ui.PhoneScreen
import ru.finassist.pf.feature.auth.impl.ui.RegistrationClosedScreen
import javax.inject.Inject

/** Registers the sign-in screens. The app shows this graph while there is no session. */
class AuthEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator) {
        composable<AuthRoutes.Phone> { entry ->
            val route = entry.toRoute<AuthRoutes.Phone>()
            PhoneScreen(reason = route.reason, onNext = { navigator.navigate(AuthRoutes.Call) })
        }
        composable<AuthRoutes.Call> {
            CallScreen(
                onBack = { navigator.back() },
                onNewUser = { navigator.navigate(AuthRoutes.Consent) },
                onRegistrationClosed = { navigator.navigate(AuthRoutes.RegistrationClosed) },
            )
        }
        composable<AuthRoutes.Consent> {
            ConsentScreen(
                onBack = { navigator.back() },
                // Back to a fresh phone screen with nothing behind it (the expired call/consent are dropped).
                onExpired = { navigator.navigate(AuthRoutes.Phone()) { popUpTo<AuthRoutes.Phone> { inclusive = true } } },
            )
        }
        composable<AuthRoutes.RegistrationClosed> {
            RegistrationClosedScreen(onBack = { navigator.navigate(AuthRoutes.Phone()) { popUpTo<AuthRoutes.Phone> { inclusive = true } } })
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AuthModule {
    @Binds abstract fun session(impl: SessionRepositoryImpl): SessionRepository

    @Binds @IntoSet
    abstract fun entry(impl: AuthEntry): FeatureEntry
}
