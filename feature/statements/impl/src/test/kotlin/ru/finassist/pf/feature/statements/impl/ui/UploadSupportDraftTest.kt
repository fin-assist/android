package ru.finassist.pf.feature.statements.impl.ui

import android.content.Intent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadSupportDraftTest {
    @Test fun `only the active user's ID is included in the support draft body`() {
        val draft = importSupportDraft("a-user-id")
        assertEquals(Intent.ACTION_SENDTO, draft.action)
        assertEquals("mailto:", draft.dataString)
        assertContentEquals(arrayOf("admin@plainfin.ru"), draft.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Не получилось загрузить выписку", draft.getStringExtra(Intent.EXTRA_SUBJECT))
        assertEquals("\n\n—\nID пользователя: a-user-id", draft.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(draft.clipData)
        assertFalse(draft.hasExtra(Intent.EXTRA_STREAM))
    }

    @Test fun `the draft is still addressed when there is no current user`() {
        val draft = importSupportDraft(null)
        assertContentEquals(arrayOf("admin@plainfin.ru"), draft.getStringArrayExtra(Intent.EXTRA_EMAIL))
        assertEquals("Не получилось загрузить выписку", draft.getStringExtra(Intent.EXTRA_SUBJECT))
        assertFalse(draft.hasExtra(Intent.EXTRA_TEXT))
    }
}
