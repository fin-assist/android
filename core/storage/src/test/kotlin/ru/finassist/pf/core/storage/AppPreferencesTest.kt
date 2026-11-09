package ru.finassist.pf.core.storage

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppPreferencesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "test.preferences_pb") }
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `theme is unset until chosen and follows every change`() = runBlocking {
        val prefs = AppPreferences(dataStore)

        prefs.theme.test {
            assertNull(awaitItem())
            prefs.setTheme("dark")
            assertEquals("dark", awaitItem())
            prefs.setTheme("system")
            assertEquals("system", awaitItem())
        }
    }

    @Test
    fun `server theme initializes only an absent preference`() = runBlocking {
        val prefs = AppPreferences(dataStore)
        assertNull(prefs.theme.first())

        prefs.initializeTheme("system")
        assertEquals("system", prefs.theme.first())
        prefs.initializeTheme("dark")
        assertEquals("system", prefs.theme.first())

        prefs.setTheme("light")
        prefs.initializeTheme("dark")
        assertEquals("light", prefs.theme.first())
        prefs.setTheme("dark")
        prefs.initializeTheme("system")
        assertEquals("dark", prefs.theme.first())
    }

    @Test
    fun `concurrent server initialization cannot replace an explicit theme choice`() = runBlocking {
        val prefs = AppPreferences(dataStore)
        listOf(
            async(Dispatchers.IO) { prefs.initializeTheme("system") },
            async(Dispatchers.IO) { prefs.setTheme("light") },
            async(Dispatchers.IO) { prefs.initializeTheme("dark") },
        ).awaitAll()

        assertEquals("light", prefs.theme.first())
    }

    @Test
    fun `device id is a uuid generated once and kept`() = runBlocking {
        val id = AppPreferences(dataStore).deviceId()

        assertEquals(id, UUID.fromString(id).toString())
        assertEquals(id, AppPreferences(dataStore).deviceId())
    }

    @Test
    fun `concurrent first reads agree on one device id`() = runBlocking {
        val prefs = AppPreferences(dataStore)

        val ids = List(16) { async(Dispatchers.IO) { prefs.deviceId() } }.awaitAll()

        assertEquals(1, ids.toSet().size, "ids: $ids")
        assertEquals(ids.first(), prefs.deviceId())
    }

    @Test
    fun `theme and device id do not overwrite each other`() = runBlocking {
        val prefs = AppPreferences(dataStore)
        val id = prefs.deviceId()

        prefs.setTheme("light")

        assertEquals(id, prefs.deviceId())
        prefs.theme.test {
            assertEquals("light", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
