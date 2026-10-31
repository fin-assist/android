package ru.finassist.pf.core.designsystem.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import ru.finassist.pf.core.common.error.ConnectionEnvironment
import ru.finassist.pf.core.common.error.ConnectionEnvironmentReader
import ru.finassist.pf.core.common.error.ConnectionFailureTexts
import ru.finassist.pf.core.designsystem.icons.PfIcons

/** Optional presentation dependency for previews and tests; real screens use the Android system observation. */
val LocalConnectionEnvironmentReader = staticCompositionLocalOf<ConnectionEnvironmentReader?> { null }

/** Stateless system adapter. No network identifiers or traffic leave the device. */
internal class AndroidConnectionEnvironmentReader(private val context: Context) : ConnectionEnvironmentReader {
    override fun read(): ConnectionEnvironment = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        when {
            manager == null -> ConnectionEnvironment.UNKNOWN
            network == null -> ConnectionEnvironment.NO_NETWORK
            else -> manager.getNetworkCapabilities(network)?.let {
                if (it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) ConnectionEnvironment.VPN
                else ConnectionEnvironment.OTHER_NETWORK
            } ?: ConnectionEnvironment.UNKNOWN
        }
    } catch (_: RuntimeException) {
        // A disappearing network or unavailable system service must not prevent recovery.
        ConnectionEnvironment.UNKNOWN
    }
}

/** Called only while rendering a connection failure; every new attempt reads current connectivity again. */
@Composable
fun connectionFailureTexts(): ConnectionFailureTexts {
    val reader = LocalConnectionEnvironmentReader.current ?: AndroidConnectionEnvironmentReader(LocalContext.current)
    return ConnectionFailureTexts.forEnvironment(reader.read())
}

/** Keeps the feature's existing retry callback and its restrictions unchanged. */
@Composable
fun ConnectionFailureState(
    modifier: Modifier = Modifier,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val texts = connectionFailureTexts()
    PfEmptyState(PfIcons.ALERT, texts.title, texts.hint, modifier = modifier, actions = actions)
}
