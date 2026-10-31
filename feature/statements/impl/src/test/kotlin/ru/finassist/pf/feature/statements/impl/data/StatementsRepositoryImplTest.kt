package ru.finassist.pf.feature.statements.impl.data

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import ru.finassist.pf.feature.statements.api.StatementsEvent
import ru.finassist.pf.feature.statements.impl.FakeTokens
import ru.finassist.pf.feature.statements.impl.importResult
import ru.finassist.pf.feature.statements.impl.testDataStore
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatementsRepositoryImplTest {
    private val store = testDataStore()
    private val tokens = FakeTokens(userId = "user-1")
    private val repository = StatementsRepositoryImpl(store, tokens)

    /**
     * The hints flag is written off the caller's thread: wait for it to land. Polls fresh reads in real time: a
     * DataStore collector that starts while a write is in flight can miss that write and never emit it (FIN-86).
     */
    private suspend fun awaitHintsWritten() = withContext(Dispatchers.Default) {
        withTimeout(10_000) {
            while (store.data.first().asMap().isEmpty()) delay(10)
        }
    }

    @Test fun `a finished import is kept for the result screen and announced`() = runTest {
        val result = importResult()
        repository.events.test {
            repository.importCompleted("u1", result)
            assertEquals(StatementsEvent.ImportCompleted("u1", result), awaitItem())
        }
        assertEquals(result, repository.lastResult("u1"))
        assertNull(repository.lastResult("u2"))
    }

    @Test fun `a deleted upload drops its result and is announced`() = runTest {
        repository.importCompleted("u1", importResult())
        repository.events.test {
            repository.uploadDeleted("u1")
            assertEquals(StatementsEvent.UploadDeleted("u1"), awaitItem())
        }
        assertNull(repository.lastResult("u1"))
    }

    @Test fun `nonempty import readiness belongs to its user and survives an absent subscriber`() = runTest {
        repository.importCompleted("u1", importResult(newCount = 5))
        val revision = repository.pendingAnalyticsImport()!!
        tokens.userId = "user-2"
        assertNull(repository.pendingAnalyticsImport())
        repository.analyticsImportReady(revision)
        tokens.userId = "user-1"
        assertEquals(revision, repository.pendingAnalyticsImport())
        repository.analyticsImportReady(revision)
        assertNull(repository.pendingAnalyticsImport())
    }

    @Test fun `an older analytics response cannot acknowledge a newer import`() = runTest {
        repository.importCompleted("u1", importResult(newCount = 5))
        val before = repository.pendingAnalyticsImport()!!
        repository.importCompleted("u2", importResult(newCount = 1))
        val after = repository.pendingAnalyticsImport()!!
        assertTrue(after > before)
        repository.analyticsImportReady(before)
        assertEquals(after, repository.pendingAnalyticsImport())
        repository.uploadDeleted("u1")
        assertEquals(after, repository.pendingAnalyticsImport())
        repository.uploadDeleted("u2")
        assertNull(repository.pendingAnalyticsImport())
    }

    @Test fun `a duplicate or empty import does not invent pending analytics`() = runTest {
        repository.importCompleted("u1", importResult(newCount = 0))
        assertNull(repository.pendingAnalyticsImport())
        tokens.userId = null
        repository.importCompleted("u2", importResult(newCount = 1))
        assertNull(repository.pendingAnalyticsImport())
    }

    @Test fun `the first import with new operations turns the hints on once`() = runTest {
        repository.importCompleted("u1", importResult(newCount = 5, isFirstImport = true))
        awaitHintsWritten()
        assertTrue(repository.consumeFirstImportHints())
        assertFalse(repository.consumeFirstImportHints())
    }

    @Test fun `the hints belong to the user who imported`() = runTest {
        repository.importCompleted("u1", importResult(newCount = 5, isFirstImport = true))
        awaitHintsWritten()
        tokens.userId = "user-2"
        assertFalse(repository.consumeFirstImportHints())
        tokens.userId = "user-1"
        assertTrue(repository.consumeFirstImportHints())
    }

    @Test fun `a first import without new operations shows no hints`() = runTest {
        repository.importCompleted("u1", importResult(newCount = 0, isFirstImport = true))
        assertFalse(repository.consumeFirstImportHints())
    }

    @Test fun `a later import shows no hints`() = runTest {
        repository.importCompleted("u1", importResult(newCount = 5, isFirstImport = false))
        assertFalse(repository.consumeFirstImportHints())
    }

    @Test fun `without a signed-in user there are no hints`() = runTest {
        tokens.userId = null
        assertFalse(repository.consumeFirstImportHints())
        tokens.userId = ""
        assertFalse(repository.consumeFirstImportHints())
    }
}
