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
import ru.finassist.pf.feature.statements.impl.ui.StatementFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StatementOpenIntentTest {
    private val file = Uri.parse("content://media/external/downloads/23")

    @Test fun `accepts typed content even if its URI path has no extension`() {
        assertEquals(StatementFile(file), incomingStatementFile(openOfx()))
    }

    @Test fun `ignores Android's replay of the base intent when resuming a task from Recents`() {
        assertNull(incomingStatementFile(openOfx().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
    }

    @Test fun `accepts generic binary files only from the public MediaStore download collection`() {
        val generic = openOfx().setDataAndType(file, BINARY_MIME_TYPE)
        assertEquals(StatementFile(file, validateDownload = true), incomingStatementFile(generic))
        val primary = Uri.parse("content://media/external_primary/downloads/24")
        assertEquals(StatementFile(primary, validateDownload = true),
            incomingStatementFile(Intent(generic).setDataAndType(primary, BINARY_MIME_TYPE)))
        listOf("content://other/external/downloads/23", "content://media/external/file/23",
            "content://media/external/downloads/23/other", "content://media/external/downloads/statement.ofx").forEach {
            assertNull(incomingStatementFile(Intent(generic).setDataAndType(Uri.parse(it), BINARY_MIME_TYPE)))
        }
        assertNull(incomingStatementFile(Intent(generic).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)))
        assertNull(incomingStatementFile(openOfx().setAction(Intent.ACTION_SEND)))
    }

    @Test fun `ignores invalid content and non-content URI schemes`() {
        assertNull(incomingStatementFile(openOfx().setDataAndType(Uri.parse("file:///storage/example.ofx"), OFX_MIME_TYPE)))
        assertNull(incomingStatementFile(openOfx().setDataAndType(Uri.parse("https://example.test/file.ofx"), OFX_MIME_TYPE)))
        assertNull(incomingStatementFile(openOfx().setDataAndType(Uri.parse("content:///23"), OFX_MIME_TYPE)))
        assertNull(incomingStatementFile(openOfx().setDataAndType(null, OFX_MIME_TYPE)))
    }

    private fun openOfx() = Intent(Intent.ACTION_VIEW)
        .setDataAndType(file, OFX_MIME_TYPE)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
