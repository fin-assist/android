package ru.finassist.pf.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.network.api.PfApi
import ru.finassist.pf.core.network.dto.ProfileUpdateDto
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.feature.applock.api.AppLock
import ru.finassist.pf.feature.auth.api.PhoneScreenNotice
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.profile.api.AppTheme
import ru.finassist.pf.feature.profile.api.ThemeRepository
import java.util.TimeZone
import javax.inject.Inject

/** Root state: who is logged in, whether the lock gate is up, which theme to use. */
@HiltViewModel
class AppStateViewModel @Inject constructor(
    session: SessionRepository,
    appLock: AppLock,
    theme: ThemeRepository,
    val entries: Set<@JvmSuppressWildcards FeatureEntry>,
    val flags: FeatureFlags,
    private val api: PfApi,
) : ViewModel() {
    val session: StateFlow<SessionState> = session.state
    val signOutNotice: StateFlow<PhoneScreenNotice> = session.lastSignOutNotice
    val locked: StateFlow<Boolean> = appLock.locked
    val hasPasscode: StateFlow<Boolean?> = appLock.hasPasscode.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val theme: StateFlow<AppTheme> = theme.theme.stateIn(viewModelScope, SharingStarted.Eagerly, AppTheme.System)

    init {
        viewModelScope.launch { runCatching { flags.refresh() } }
        // Calendar units are computed in the profile time zone (api.md 2.2): keep it equal to the device zone, so the
        // client may group days and months in the device zone.
        viewModelScope.launch {
            session.state.collect { s ->
                if (s is SessionState.LoggedIn) runCatching {
                    val device = TimeZone.getDefault().id
                    if (api.getProfile().timezone != device) api.updateProfile(ProfileUpdateDto(timezone = device))
                }
            }
        }
    }
}
