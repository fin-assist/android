package ru.finassist.pf.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import ru.finassist.pf.core.designsystem.components.ButtonVariant
import ru.finassist.pf.core.designsystem.components.PageHeader
import ru.finassist.pf.core.designsystem.components.PfButton
import ru.finassist.pf.core.designsystem.components.PfCard
import ru.finassist.pf.core.designsystem.components.ScreenPadding
import ru.finassist.pf.core.designsystem.components.SegmentedControl
import ru.finassist.pf.core.designsystem.theme.PfSpace
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.navigation.DebugMenu
import ru.finassist.pf.core.navigation.FeatureEntry
import ru.finassist.pf.core.navigation.Navigator
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.Flags
import ru.finassist.pf.core.toggles.local.LocalFeatureFlags
import javax.inject.Inject

/** Mock flavour only: a screen to override every flag (default / on / off) to hand-test the toggles. */
object DebugRoutes {
    @Serializable data object Flags
}

class DebugMenuImpl @Inject constructor() : DebugMenu {
    override fun route(): Any = DebugRoutes.Flags
}

@HiltViewModel
class FlagsDebugViewModel @Inject constructor(private val flags: LocalFeatureFlags) : ViewModel() {
    private val _overrides = MutableStateFlow(flags.overrides())
    val overrides: StateFlow<Map<String, Boolean>> = _overrides

    fun set(flag: Flag, value: Boolean?) = viewModelScope.launch {
        flags.override(flag, value)
        _overrides.value = flags.overrides()
    }

    fun reset() = viewModelScope.launch {
        Flags.all.forEach { flags.override(it, null) }
        _overrides.value = flags.overrides()
    }
}

@Composable
fun FlagsDebugScreen(vm: FlagsDebugViewModel, onBack: () -> Unit) {
    val c = PfTheme.colors
    val overrides by vm.overrides.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(c.bg)) {
        PageHeader(title = "Флаги (mock)", onBack = onBack, subtitle = "Значение применяется при следующем открытии экрана")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = PfSpace.s2), verticalArrangement = Arrangement.spacedBy(PfSpace.s3)) {
            Flags.all.forEach { flag ->
                PfCard {
                    Text(flag.key, style = PfTheme.type.bodyStrong, color = c.text)
                    Text(flag.description, style = PfTheme.type.caption, color = c.textMuted)
                    Spacer(Modifier.height(PfSpace.s2))
                    val selected = when (overrides[flag.key]) { null -> 0; true -> 1; false -> 2 }
                    SegmentedControl(
                        options = listOf("По умолчанию (${if (flag.default) "вкл" else "выкл"})", "Вкл", "Выкл"),
                        selected = selected,
                        onSelect = { i -> vm.set(flag, when (i) { 1 -> true; 2 -> false; else -> null }) },
                    )
                }
            }
            PfButton("Сбросить все", onClick = vm::reset, variant = ButtonVariant.Ghost, block = true)
            Spacer(Modifier.height(PfSpace.s6))
        }
    }
}

class FlagsDebugEntry @Inject constructor() : FeatureEntry {
    override fun NavGraphBuilder.install(navigator: Navigator, navController: NavController) {
        composable<DebugRoutes.Flags> {
            val vm: FlagsDebugViewModel = hiltViewModel()
            FlagsDebugScreen(vm = vm, onBack = { navigator.back() })
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugModule {
    @Binds abstract fun debugMenu(impl: DebugMenuImpl): DebugMenu
    @Binds @IntoSet abstract fun entry(impl: FlagsDebugEntry): FeatureEntry
}
