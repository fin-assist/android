package ru.finassist.pf.providers.toggles.local

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.finassist.pf.core.toggles.FLAGS_VERSION_DEFAULTS
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.flagsVersion
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The test asset (`src/test/assets/flags.json`): registration off, assistant on, a string and an unknown key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalFeatureFlagsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val root: File = createTempDirectory("flags").toFile()
    private val file = File(root, "flags.preferences_pb")
    private val jobs = mutableListOf<Job>()

    @After
    fun tearDown() {
        jobs.forEach { it.cancel() }
        root.deleteRecursively()
    }

    private fun dataStore() =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Job().also { jobs += it } + Dispatchers.IO)) { file }

    private fun flags(context: Context = this.context) = LocalFeatureFlags(context, dataStore()).also {
        runBlocking { it.awaitReady() }
    }

    private val assetVersion = flagsVersion(mapOf("auth.registration" to false, "assistant" to true))

    @Test
    fun `asset values win over code defaults, other flags keep theirs`() {
        val flags = flags()

        assertFalse(flags.isEnabled(Flag.AUTH_REGISTRATION))
        assertTrue(flags.isEnabled(Flag.ASSISTANT))
        assertTrue(flags.isEnabled(Flag.STATEMENTS_UPLOAD))
        assertEquals(assetVersion, flags.version)
    }

    @Test
    fun `a value that is not a boolean and an unknown key are ignored`() {
        val flags = flags()

        assertTrue(flags.isEnabled(Flag.SEARCH_FILTER_KIND))
    }

    @Test
    fun `without the asset every flag has its code default`() {
        val noAsset = object : ContextWrapper(context) {
            override fun getAssets(): AssetManager = throw FileNotFoundException("flags.json")
        }

        val flags = flags(noAsset)

        Flag.entries.forEach { assertEquals(it.defaultValue, flags.isEnabled(it), it.key) }
        assertEquals(FLAGS_VERSION_DEFAULTS, flags.version)
    }

    @Test
    fun `an override wins over the asset as soon as it is set`() = runBlocking {
        val flags = flags()

        flags.set(Flag.AUTH_REGISTRATION, true)
        flags.set(Flag.STATEMENTS_UPLOAD, false)

        assertTrue(flags.isEnabled(Flag.AUTH_REGISTRATION))
        assertFalse(flags.isEnabled(Flag.STATEMENTS_UPLOAD))
        assertEquals(mapOf(Flag.AUTH_REGISTRATION to true, Flag.STATEMENTS_UPLOAD to false), flags.overrides.first())
        assertEquals(
            flagsVersion(mapOf("auth.registration" to true, "assistant" to true, "statements.upload" to false)),
            flags.version,
        )
    }

    @Test
    fun `setting null removes one override, clear removes them all`() = runBlocking {
        val flags = flags()
        flags.set(Flag.AUTH_REGISTRATION, true)
        flags.set(Flag.ASSISTANT, false)
        flags.set(Flag.STATEMENTS_UPLOAD, false)

        flags.set(Flag.AUTH_REGISTRATION, null)
        assertFalse(flags.isEnabled(Flag.AUTH_REGISTRATION))
        assertEquals(mapOf(Flag.ASSISTANT to false, Flag.STATEMENTS_UPLOAD to false), flags.overrides.first())

        flags.clear()
        assertTrue(flags.isEnabled(Flag.ASSISTANT))
        assertTrue(flags.isEnabled(Flag.STATEMENTS_UPLOAD))
        assertEquals(emptyMap(), flags.overrides.first())
        assertEquals(assetVersion, flags.version)
    }

    @Test
    fun `overrides of the previous process are in effect once ready`() = runBlocking {
        val job = Job()
        PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { file }
            .edit { it[booleanPreferencesKey("flags.override.assistant")] = false }
        job.cancel()
        job.join()

        val flags = flags()

        assertFalse(flags.isEnabled(Flag.ASSISTANT))
        assertEquals(mapOf(Flag.ASSISTANT to false), flags.overrides.first())
    }

    @Test
    fun `changes fires when an override is set or cleared, not when nothing changed`() = runBlocking {
        val flags = flags()
        flags.changes.test {
            flags.set(Flag.ASSISTANT, false)
            awaitItem()
            flags.set(Flag.ASSISTANT, false)
            flags.clear()
            awaitItem()
            flags.clear()
            expectNoEvents()
        }
    }

    @Test
    fun `refresh rereads the asset and fires changes, setUser does nothing`() = runBlocking {
        val flags = flags()
        flags.changes.test {
            flags.setUser("user_1")
            expectNoEvents()
            flags.refresh()
            awaitItem()
        }
        assertFalse(flags.isEnabled(Flag.AUTH_REGISTRATION))
    }

    @Test
    fun `a failed read after a write neither fails the write nor leaves the overrides behind`() = runBlocking<Unit> {
        val store = dataStore()
        val flaky = object : DataStore<Preferences> {
            @Volatile var failReads = false
            override val data: Flow<Preferences> = flow {
                if (failReads) throw IOException("disk read failed")
                emitAll(store.data)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = store.updateData(transform)
        }
        val flags = LocalFeatureFlags(context, flaky).also { it.awaitReady() }

        flaky.failReads = true
        flags.set(Flag.ASSISTANT, false) // the write lands; the read after it fails and must not surface here
        assertTrue(flags.isEnabled(Flag.ASSISTANT), "the last read stays while reads fail")

        flaky.failReads = false // no further write: the retried read publishes the override
        withTimeout(5_000) { while (flags.isEnabled(Flag.ASSISTANT)) delay(10) }
    }

    @Test
    fun `writes that fail to read back share one retry`() = runBlocking<Unit> {
        val store = dataStore()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val flaky = object : DataStore<Preferences> {
            @Volatile var failReads = false
            override val data: Flow<Preferences> = flow {
                if (failReads) {
                    reads.incrementAndGet()
                    throw IOException("disk read failed")
                }
                emitAll(store.data)
            }
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = store.updateData(transform)
        }
        val flags = LocalFeatureFlags(context, flaky).also { it.awaitReady() }
        flaky.failReads = true
        repeat(10) { flags.set(Flag.ASSISTANT, it % 2 == 0) }

        // A second of retries: one background retry and the store's own trigger read about once each, ten separate
        // retries would read at least ten times.
        reads.set(0)
        delay(1_100)
        assertTrue(reads.get() <= 4, "reads in a second: ${reads.get()}")

        flaky.failReads = false // the retry ends on the last write's value
        withTimeout(5_000) { while (flags.isEnabled(Flag.ASSISTANT)) delay(10) }
    }
}
