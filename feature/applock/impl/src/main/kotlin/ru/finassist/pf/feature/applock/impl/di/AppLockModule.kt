package ru.finassist.pf.feature.applock.impl.di

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.applock.api.AppLockRoutes
import ru.finassist.pf.feature.applock.impl.data.AppLockImpl
import ru.finassist.pf.feature.applock.impl.data.KeystorePasscodeCrypto
import ru.finassist.pf.feature.applock.impl.data.PasscodeCrypto
import ru.finassist.pf.feature.applock.impl.ui.AppLockScreens
import ru.finassist.pf.feature.applock.impl.ui.AppLockScreensImpl
import ru.finassist.pf.feature.applock.impl.ui.ChangePasscodeScreen
import ru.finassist.pf.feature.applock.impl.ui.ConfirmPasscodeScreen
import ru.finassist.pf.feature.applock.impl.ui.SecurityScreen
import javax.inject.Inject

/**
 * Screens that live inside the main graph (profile → security → change; confirm). The setup screen and the
 * unlock overlay are not routes: the app shows them by lock state through [AppLockScreens].
 */
class AppLockEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator) {
        composable<AppLockRoutes.Security> {
            SecurityScreen(onBack = { navigator.back() }, onChangePasscode = { navigator.navigate(AppLockRoutes.Change) })
        }
        composable<AppLockRoutes.Change> {
            ChangePasscodeScreen(onBack = { navigator.back() }, onDone = { navigator.back() })
        }
        composable<AppLockRoutes.Confirm> {
            ConfirmPasscodeScreen(
                onBack = { navigator.back() },
                onConfirmed = { navigator.returnResult(AppLockRoutes.Confirm.RESULT_CONFIRMED, "true") },
            )
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class AppLockModule {
    @Binds abstract fun appLock(impl: AppLockImpl): AppLock

    @Binds abstract fun screens(impl: AppLockScreensImpl): AppLockScreens

    @Binds abstract fun passcodeCrypto(impl: KeystorePasscodeCrypto): PasscodeCrypto

    @Binds @IntoSet
    abstract fun entry(impl: AppLockEntry): FeatureEntry
}
