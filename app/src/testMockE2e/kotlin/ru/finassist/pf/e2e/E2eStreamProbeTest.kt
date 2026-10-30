package ru.finassist.pf.e2e

import android.app.Application
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.finassist.pf.R
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class E2eStreamProbeTest {
    @Test
    fun `no marker is visible before a real rate limit and the first refusal reveals it`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val count = MutableStateFlow(0)
        installStreamProbe(controller.get(), count)
        shadowOf(Looper.getMainLooper()).idle()
        val marker = controller.get().findViewById<TextView>(R.id.e2e_stream_rate_limited)
        assertEquals(View.GONE, marker.visibility)

        count.value = 1
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.VISIBLE, marker.visibility)
        assertEquals("e2e.stream.rate_limited=1", marker.text.toString())
        controller.pause().stop().destroy()
    }

    @Test
    fun `counts remain exact and collecting stops with the activity`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val count = MutableStateFlow(1)
        installStreamProbe(controller.get(), count)
        shadowOf(Looper.getMainLooper()).idle()
        val marker = controller.get().findViewById<TextView>(R.id.e2e_stream_rate_limited)
        count.value = 2
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("e2e.stream.rate_limited=2", marker.text.toString())

        controller.pause().stop()
        count.value = 3
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("e2e.stream.rate_limited=2", marker.text.toString())
        controller.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("e2e.stream.rate_limited=3", marker.text.toString())
        controller.pause().stop().destroy()
        count.value = 4
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("e2e.stream.rate_limited=3", marker.text.toString())
    }

    @Test
    fun `resume installs one marker and recreation uses the current process count`() {
        val count = MutableStateFlow(1)
        val first = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        installStreamProbe(first.get(), count)
        installStreamProbe(first.get(), count)
        shadowOf(Looper.getMainLooper()).idle()
        val root = first.get().findViewById<android.view.ViewGroup>(android.R.id.content)
        assertEquals(1, root.childCount)
        first.pause().stop().destroy()

        val second = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        installStreamProbe(second.get(), count)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("e2e.stream.rate_limited=1", second.get().findViewById<TextView>(R.id.e2e_stream_rate_limited).text.toString())
        count.value = 0
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(View.GONE, second.get().findViewById<TextView>(R.id.e2e_stream_rate_limited).visibility)
        second.pause().stop().destroy()
    }
}
