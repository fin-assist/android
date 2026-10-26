package ru.finassist.pf.feature.profile.impl.data

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BootCounterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val counter = AndroidBootCounter(context)

    @Test fun `reads actual platform boot counter including zero`() {
        for (value in listOf(0, 7, Int.MAX_VALUE)) {
            Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, value)
            assertEquals(value, counter.current())
        }
    }

    @Test fun `missing and invalid counters are unknown`() {
        Settings.Global.putString(context.contentResolver, Settings.Global.BOOT_COUNT, null)
        assertNull(counter.current())
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        assertNull(counter.current())
    }
}
