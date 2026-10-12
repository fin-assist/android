package ru.finassist.pf.feature.statements.impl.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The picker contract: the system document picker by default, the activity named by the manifest meta-data in
 * the mock e2e build (where the instrumented tests run — they cannot check the system intent, so this does).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PickStatementDocumentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun `without an override the system picker opens openable documents of the given types`() {
        val intent = PickStatementDocument().createIntent(context, arrayOf("application/x-ofx", "*/*"))
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals(listOf("application/x-ofx", "*/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        assertNull(intent.component)
    }

    @Test fun `the manifest can name a replacement activity`() {
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        val meta = Bundle().apply { putString("ru.finassist.pf.DOCUMENT_PICKER_OVERRIDE", "ru.finassist.pf.e2e.Picker") }
        shadowOf(context.packageManager).installPackage(
            shadowOf(context.packageManager).getInternalMutablePackageInfo(context.packageName).apply {
                applicationInfo = ApplicationInfo(info).apply { metaData = meta }
            },
        )
        val intent = PickStatementDocument().createIntent(context, arrayOf("*/*"))
        assertEquals("ru.finassist.pf.e2e.Picker", intent.component?.className)
        assertNull(intent.action)
        assertEquals(listOf("*/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
    }

    @Test fun `the result is the picked uri, nothing when cancelled`() {
        val contract = PickStatementDocument()
        val uri = Uri.parse("content://docs/1")
        assertEquals(uri, contract.parseResult(Activity.RESULT_OK, Intent().setData(uri)))
        assertNull(contract.parseResult(Activity.RESULT_CANCELED, Intent().setData(uri)))
        assertNull(contract.parseResult(Activity.RESULT_OK, null))
    }
}
