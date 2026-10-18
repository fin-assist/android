package ru.finassist.pf.di

import android.app.Application
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The last minimum version seen survives the process (FIN-22: checked at a cold start before any config). */
@RunWith(RobolectricTestRunner::class)
// A plain Application: PfApplication would start Hilt and the SDKs.
@Config(sdk = [35], application = Application::class)
class LastMinVersionPrefsTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun `nothing before the first minimum`() {
        assertNull(LastMinVersionPrefs(context).get())
    }

    @Test fun `a minimum is read back by another instance, the newest wins`() {
        LastMinVersionPrefs(context).set("0.2.8")
        assertEquals("0.2.8", LastMinVersionPrefs(context).get())
        LastMinVersionPrefs(context).set("0.3.0")
        LastMinVersionPrefs(context).set("0.3.0")
        assertEquals("0.3.0", LastMinVersionPrefs(context).get())
        assertEquals("0.3.0", context.getSharedPreferences("pf_min_version", Context.MODE_PRIVATE).getString("app.min_version", null))
    }
}
