package ru.finassist.pf.core.designsystem.components

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import ru.finassist.pf.core.common.error.ConnectionEnvironment
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidConnectionEnvironmentReaderTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val reader = AndroidConnectionEnvironmentReader(context)

    @Test fun `VPN is read again after its transport changes`() {
        val network = requireNotNull(manager.activeNetwork)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        shadowOf(manager).setNetworkCapabilities(network, capabilities)
        assertEquals(ConnectionEnvironment.VPN, reader.read())
        shadowOf(capabilities).removeTransportType(NetworkCapabilities.TRANSPORT_VPN)
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        assertEquals(ConnectionEnvironment.OTHER_NETWORK, reader.read())
    }

    @Test fun `missing active network is not a VPN`() {
        shadowOf(manager).setActiveNetworkInfo(null)
        assertEquals(ConnectionEnvironment.NO_NETWORK, reader.read())
    }

    @Test fun `vanished capabilities are unknown`() {
        shadowOf(manager).setNetworkCapabilities(requireNotNull(manager.activeNetwork), null)
        assertEquals(ConnectionEnvironment.UNKNOWN, reader.read())
    }

    @Test fun `unavailable system service is unknown`() {
        val unavailable = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? = null
        }
        assertEquals(ConnectionEnvironment.UNKNOWN, AndroidConnectionEnvironmentReader(unavailable).read())
    }

    @Test fun `denied observation is unknown and cannot break recovery`() {
        val denied = object : ContextWrapper(context) {
            override fun getSystemService(name: String): Any? = throw SecurityException("denied")
        }
        assertEquals(ConnectionEnvironment.UNKNOWN, AndroidConnectionEnvironmentReader(denied).read())
    }
}
