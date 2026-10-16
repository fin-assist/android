package ru.finassist.pf.feature.availability.impl.ui

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** «Обновить» on the update screen (FIN-22): the app's page in whatever store handles `market://`, else the site. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppPageIntentTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun `with a store the button opens the app's page in it`() {
        val store = ComponentName("ru.vk.store", "ru.vk.store.Details")
        shadowOf(context.packageManager).apply {
            addActivityIfNotPresent(store)
            addIntentFilterForActivity(store, IntentFilter(Intent.ACTION_VIEW).apply { addCategory(Intent.CATEGORY_DEFAULT); addDataScheme("market") })
        }
        val intent = appPageIntent(context)
        assertEquals(Uri.parse("market://details?id=${context.packageName}"), intent.data)
        assertEquals(Intent.ACTION_VIEW, intent.action)
    }

    @Test fun `without a store the button opens the site`() {
        assertEquals(Uri.parse("https://plainfin.ru"), appPageIntent(context).data)
    }
}
