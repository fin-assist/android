package ru.finassist.pf.feature.statements.impl.ui

import java.time.ZoneId
import ru.finassist.pf.core.common.time.Clock
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.ImportEvent
import ru.finassist.pf.core.api.StatementsApi
import ru.finassist.pf.core.api.TokenStore
import ru.finassist.pf.core.api.UploadFile
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.api.model.StatementsList
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.UnreadLinesList
import ru.finassist.pf.core.api.model.UploadAccepted
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.storage.IdempotencyKeys
import ru.finassist.pf.core.toggles.StaticFlags
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.statements.impl.data.StatementsRepositoryImpl
import java.io.ByteArrayInputStream
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Robolectric: the view model reads the picked document through `ContentResolver`. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val config = ImportConfig(
        downloadUrl = "https://www.tbank.ru/export", loginUrl = "https://www.tbank.ru/login/",
        maxFileSizeBytes = 5L * 1024 * 1024, acceptedFormats = listOf("OFX"),
        suggestedPeriod = DateRange(OffsetDateTime.parse("2025-09-26T00:00:00+03:00"), OffsetDateTime.parse("2026-09-26T00:00:00+03:00")),
    )

    private inner class FakeApi : StatementsApi {
        var offline = false
        var configFailure: AppError? = null
        val keys = mutableListOf<String>()
        override suspend fun getImportConfig(): ImportConfig = configFailure?.let { throw it } ?: config
        override suspend fun uploadStatement(key: IdempotencyKey, file: UploadFile): UploadAccepted {
            keys += key.value
            if (offline) throw AppError.Offline()
            return UploadAccepted("u1", file.name)
        }
        // No events: the upload stays «busy» for the assertions.
        override fun streamProgress(uploadId: String): Flow<ImportEvent> = emptyFlow()
        override suspend fun listStatements(): StatementsList = error("unused")
        override suspend fun deleteStatement(uploadId: String) = Unit
        override suspend fun listUnreadLines(uploadId: String): UnreadLinesList = error("unused")
        override suspend fun listUnreadLines(from: OffsetDateTime, to: OffsetDateTime): UnreadLinesList = error("unused")
    }

    private object NoTokens : TokenStore {
        override val session: Flow<TokenStore.Session?> = flowOf(null)
        override suspend fun current(): TokenStore.Session? = null
        override suspend fun save(session: TokenStore.Session) = Unit
        override suspend fun updateTokens(expectedRefreshToken: String, tokens: TokenPair) = false
        override suspend fun clear() = Unit
    }

    private object NoTracker : Tracker {
        override fun track(event: Event, params: Map<String, String>) = Unit
        override fun setUserId(userId: String?) = Unit
    }

    private val api = FakeApi()
    private val uri: Uri = Uri.parse("content://test/operations_2026.ofx")
    private lateinit var vm: UploadViewModel
    private lateinit var store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private val clock = object : Clock {
        override fun now(): OffsetDateTime = OffsetDateTime.parse("2026-10-08T12:00:00+03:00")
        override val zone: ZoneId = ZoneId.of("Europe/Moscow")
    }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream("OFXHEADER:100".toByteArray()) }
        store = PreferenceDataStoreFactory.create { File(context.cacheDir, "upload-test-${System.nanoTime()}.preferences_pb") }
        vm = newViewModel()
    }

    private fun newViewModel() =
        UploadViewModel(context, api, StatementsRepositoryImpl(store, NoTokens), StaticFlags(), NoTracker, IdempotencyKeys(store), clock)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `without the config the guide suggests the last 12 months by the app's clock`() {
        api.configFailure = AppError.Server(500)
        val vm = newViewModel()
        val period = vm.state.value.config!!.suggestedPeriod
        assertEquals(OffsetDateTime.parse("2025-10-08T12:00:00+03:00"), period.from)
        assertEquals(OffsetDateTime.parse("2026-10-09T12:00:00+03:00"), period.to)
    }

    /** The file is read on the IO dispatcher: wait until the state settles. */
    private fun await(what: String, condition: (UploadUiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition(vm.state.value)) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what: ${vm.state.value}" }
            Thread.sleep(20)
        }
    }

    @Test fun `the loaded config is kept in the state for the guide`() {
        assertEquals(UploadPhase.Guide(config), vm.state.value.phase)
        assertEquals(config, vm.state.value.config)
    }

    @Test fun `the fallback sheet opens and closes`() {
        vm.showFallback(true)
        assertTrue(vm.state.value.fallbackOpen)
        vm.showFallback(false)
        assertFalse(vm.state.value.fallbackOpen)
    }

    @Test fun `without the network the guide stays with the retry notice`() {
        api.offline = true
        vm.onFilePicked(uri)
        await("the offline notice") { it.uploadOffline }
        assertEquals(UploadPhase.Guide(config), vm.state.value.phase)
        assertFalse(vm.state.value.askCancel)
    }

    @Test fun `retry sends the same file with the same key`() {
        api.offline = true
        vm.onFilePicked(uri)
        await("the offline notice") { it.uploadOffline }

        api.offline = false
        vm.retryUpload()
        await("the accepted upload") { (it.phase as? UploadPhase.Busy)?.uploadId == "u1" }
        assertFalse(vm.state.value.uploadOffline)
        assertEquals("operations_2026.ofx", assertIs<UploadPhase.Busy>(vm.state.value.phase).fileName)
        assertEquals(2, api.keys.size)
        assertEquals(api.keys[0], api.keys[1])
    }

    @Test fun `retry without a picked file does nothing`() {
        vm.retryUpload()
        assertEquals(UploadPhase.Guide(config), vm.state.value.phase)
        assertTrue(api.keys.isEmpty())
    }

    @Test fun `going back to the guide drops the offline notice`() {
        api.offline = true
        vm.onFilePicked(uri)
        await("the offline notice") { it.uploadOffline }
        vm.backToGuide()
        assertFalse(vm.state.value.uploadOffline)
    }
}
