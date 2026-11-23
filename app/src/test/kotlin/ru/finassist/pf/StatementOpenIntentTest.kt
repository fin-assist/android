package ru.finassist.pf

import android.app.Application
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StatementOpenIntentTest {
    private val file = Uri.parse("content://media/external/downloads/23")

    @Test fun `accepts typed content even if its URI path has no extension`() {
        assertEquals(file, incomingStatementUri(openOfx()))
    }

    @Test fun `ignores opaque downloads with a generic type and unrelated actions`() {
        assertNull(incomingStatementUri(openOfx().setDataAndType(file, "application/octet-stream")))
        assertNull(incomingStatementUri(openOfx().setAction(Intent.ACTION_SEND)))
    }

    @Test fun `ignores missing path grants and non-content URI schemes`() {
        assertNull(incomingStatementUri(openOfx().setFlags(0)))
        assertNull(incomingStatementUri(openOfx().setDataAndType(Uri.parse("file:///storage/example.ofx"), OFX_MIME_TYPE)))
        assertNull(incomingStatementUri(openOfx().setDataAndType(Uri.parse("https://example.test/file.ofx"), OFX_MIME_TYPE)))
        assertNull(incomingStatementUri(openOfx().setDataAndType(Uri.parse("content:///23"), OFX_MIME_TYPE)))
        assertNull(incomingStatementUri(openOfx().setDataAndType(null, OFX_MIME_TYPE)))
    }

    private fun openOfx() = Intent(Intent.ACTION_VIEW)
        .setDataAndType(file, OFX_MIME_TYPE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
