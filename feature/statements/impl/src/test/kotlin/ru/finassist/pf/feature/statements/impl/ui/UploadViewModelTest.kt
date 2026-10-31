package ru.finassist.pf.feature.statements.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import java.time.ZoneId
import ru.finassist.pf.core.common.time.Clock
import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
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
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.ImportProgress
import ru.finassist.pf.core.api.model.ImportStage
import ru.finassist.pf.core.api.model.ImportConfig
import ru.finassist.pf.core.api.model.StatementsList
import ru.finassist.pf.core.api.model.TokenPair
import ru.finassist.pf.core.api.model.UnreadLinesList
import ru.finassist.pf.core.api.model.UploadAccepted
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.storage.IdempotencyKeys
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.toggles.StaticFlags
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.feature.statements.impl.RecordingTracker
import ru.finassist.pf.feature.statements.impl.importResult
import ru.finassist.pf.feature.statements.impl.data.StatementsRepositoryImpl
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Robolectric: the view model reads the picked document through `ContentResolver`. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UploadViewModelTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val config = ImportConfig(
        downloadUrl = "https://www.tbank.ru/export", loginUrl = "https://tbank-online.com/login",
        maxFileSizeBytes = 5L * 1024 * 1024, acceptedFormats = listOf("OFX"),
        suggestedPeriod = DateRange(OffsetDateTime.parse("2025-09-26T00:00:00+03:00"), OffsetDateTime.parse("2026-09-26T00:00:00+03:00")),
    )

    private inner class FakeApi : StatementsApi {
        var offline = false
        var configFailure: AppError? = null
        var currentConfig = config
        var configCalls = 0
        val keys: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        /** `file.name` of every upload attempt, in the order of [keys]. */
        val names: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        /** Thrown by the next uploads, one each, before the file is accepted. */
        val uploadFailures = ArrayDeque<AppError>()
        /** While set, the upload waits for it: the request is «in flight». */
        var uploadGate: CompletableDeferred<Unit>? = null
        /** Each opening of the progress stream takes the next flow; none left — no events. */
        val streams = ArrayDeque<Flow<ImportEvent>>()
        val streamOpens = AtomicInteger()
        var deleteGate: CompletableDeferred<Unit>? = null
        var deleteFailure: AppError? = null
        val deleted = mutableListOf<String>()

        override suspend fun getImportConfig(): ImportConfig {
            configCalls++
            return configFailure?.let { throw it } ?: currentConfig
        }
        override suspend fun uploadStatement(key: IdempotencyKey, file: UploadFile): UploadAccepted {
            keys += key.value
            names += file.name
            uploadGate?.await()
            if (offline) throw AppError.Offline()
            synchronized(uploadFailures) { uploadFailures.removeFirstOrNull() }?.let { throw it }
            return UploadAccepted("u1", file.name)
        }
        // No events by default: the upload stays «busy» for the assertions.
        override fun streamProgress(uploadId: String): Flow<ImportEvent> {
            streamOpens.incrementAndGet()
            return synchronized(streams) { streams.removeFirstOrNull() } ?: emptyFlow()
        }
        override suspend fun listStatements(): StatementsList = error("unused")
        override suspend fun deleteStatement(uploadId: String) {
            deleted += uploadId
            deleteGate?.await()
            deleteFailure?.let { throw it }
        }
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

    private val dispatcher = UnconfinedTestDispatcher()
    private val api = FakeApi()
    private val tracker = RecordingTracker()
    private lateinit var repository: StatementsRepositoryImpl
    private val uri: Uri = Uri.parse("content://test/operations_2026.ofx")
    private lateinit var vm: UploadViewModel
    private lateinit var store: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private val clock = object : Clock {
        override fun now(): OffsetDateTime = OffsetDateTime.parse("2026-10-08T12:00:00+03:00")
        override val zone: ZoneId = ZoneId.of("Europe/Moscow")
    }

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream("OFXHEADER:100".toByteArray()) }
        store = PreferenceDataStoreFactory.create { File(context.cacheDir, "upload-test-${System.nanoTime()}.preferences_pb") }
        repository = StatementsRepositoryImpl(store, NoTokens)
        vm = newViewModel()
    }

    private fun newViewModel(flags: StaticFlags = StaticFlags()) =
        UploadViewModel(context, api, repository, flags, tracker, IdempotencyKeys(store), clock, ElapsedClock { dispatcher.scheduler.currentTime })

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `without the config the guide suggests the last 12 months by the app's clock`() {
        api.configFailure = AppError.Server(500)
        val vm = newViewModel()
        val period = vm.state.value.config!!.suggestedPeriod
        assertEquals(OffsetDateTime.parse("2025-10-08T12:00:00+03:00"), period.from)
        assertEquals(OffsetDateTime.parse("2026-10-09T12:00:00+03:00"), period.to)
    }

    @Test fun `cold config failure never opens the obsolete login address`() {
        api.configFailure = AppError.Server(500)
        val state = newViewModel().state.value
        assertEquals("https://tbank-online.com/login", assertIs<UploadPhase.Guide>(state.phase).config.loginUrl)
        assertEquals("https://tbank-online.com/login", state.config!!.downloadUrl)
    }

    @Test fun `server destination changes apply on reload and survive a later config failure`() {
        val destination = "https://bank.example/new-login"
        api.currentConfig = config.copy(loginUrl = destination)
        vm.load()
        assertEquals(destination, assertIs<UploadPhase.Guide>(vm.state.value.phase).config.loginUrl)
        api.configFailure = AppError.Server(500)
        vm.load()
        assertEquals(destination, assertIs<UploadPhase.Guide>(vm.state.value.phase).config.loginUrl)
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
        awaitIdle()
        vm.retryUpload()
        await("the accepted upload") { (it.phase as? UploadPhase.Busy)?.uploadId == "u1" }
        assertFalse(vm.state.value.uploadOffline)
        assertEquals("operations_2026.ofx", assertIs<UploadPhase.Busy>(vm.state.value.phase).fileName)
        assertEquals(2, api.keys.size)
        assertEquals(api.keys[0], api.keys[1])
        // The server compares the name too (FIN-297): the retry sends the name of the first attempt.
        assertEquals(listOf(name, name), api.names)
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

    // ---- picking a file ----

    private val name = "operations_2026.ofx"

    /** The progress stream is re-opened after a delay of the test clock: move it on until [condition] holds. */
    private fun advanceUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what: ${vm.state.value}" }
            dispatcher.scheduler.advanceTimeBy(1_000)
            dispatcher.scheduler.runCurrent()
            Thread.sleep(20)
        }
    }

    private fun awaitAccepted() = await("the accepted upload") { (it.phase as? UploadPhase.Busy)?.uploadId == "u1" }

    /**
     * Waits until the previous attempt has finished. Its job ends on the IO thread just after the last state update,
     * and a pick before that is ignored on purpose as «still sending» (in the app both run on the main thread).
     */
    private fun awaitIdle() {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.uploadInFlight) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for the upload job to end: ${vm.state.value}" }
            Thread.sleep(20)
        }
    }

    /** A progress stream the test emits into once the view model listens. */
    private fun liveStream(): MutableSharedFlow<ImportEvent> {
        val live = MutableSharedFlow<ImportEvent>(extraBufferCapacity = 8)
        api.streams += live
        return live
    }

    /**
     * The view model listens from the IO thread the upload resumed on: an event is not always handled inside
     * `tryEmit`, so the test waits for its effect.
     */
    private fun MutableSharedFlow<ImportEvent>.awaitSubscribed() {
        val deadline = System.currentTimeMillis() + 10_000
        while (subscriptionCount.value == 0) {
            check(System.currentTimeMillis() < deadline) { "the progress stream was not opened" }
            Thread.sleep(20)
        }
    }

    @Test fun `with the upload toggle off the screen is disabled and loads nothing`() {
        val before = api.configCalls
        vm = newViewModel(StaticFlags(mapOf(Flag.STATEMENTS_UPLOAD to false)))
        assertEquals(UploadPhase.Disabled, vm.state.value.phase)
        assertEquals(before, api.configCalls)
    }

    @Test fun `no network for the config shows the offline screen until a reload succeeds`() {
        api.configFailure = AppError.Offline()
        vm = newViewModel()
        assertEquals(UploadPhase.Offline, vm.state.value.phase)
        api.configFailure = null
        vm.load()
        assertEquals(UploadPhase.Guide(config), vm.state.value.phase)
    }

    @Test fun `a file over the size limit is rejected without sending it`() {
        val small = config.copy(maxFileSizeBytes = 4)
        api.currentConfig = small
        vm = newViewModel()
        vm.onFilePicked(uri)
        await("the size error") { it.phase is UploadPhase.Error }
        assertEquals(UploadPhase.Error(ErrorCodes.FILE_TOO_LARGE, name, small), vm.state.value.phase)
        assertTrue(api.keys.isEmpty())
    }

    @Test fun `an unreadable document is an error and nothing is sent`() {
        val broken = Uri.parse("content://test/broken.ofx")
        shadowOf(context.contentResolver).registerInputStreamSupplier(broken) { throw IOException("revoked") }
        vm.onFilePicked(broken)
        await("the read error") { it.phase is UploadPhase.Error }
        assertEquals(UploadPhase.Error(ErrorCodes.PROCESSING_FAILED, "broken.ofx", config), vm.state.value.phase)
        assertTrue(api.keys.isEmpty())
    }

    @Test fun `a rejected file is a final answer - picking it again is a new upload`() {
        api.uploadFailures += AppError.Api(ErrorCodes.WRONG_FORMAT, 400)
        vm.onFilePicked(uri)
        await("the rejection") { it.phase is UploadPhase.Error }
        assertEquals(UploadPhase.Error(ErrorCodes.WRONG_FORMAT, name, config, presentation = ru.finassist.pf.core.common.error.ErrorPresentation()), vm.state.value.phase)
        assertEquals(mapOf("code" to ErrorCodes.WRONG_FORMAT), tracker.events.last { it.first == Events.STATEMENTS_UPLOAD_FAILED }.second)

        vm.backToGuide()
        assertEquals(UploadPhase.Guide(config), vm.state.value.phase)
        awaitIdle()
        vm.onFilePicked(uri)
        awaitAccepted()
        assertTrue(api.keys[0] != api.keys[1])
    }

    @Test fun `a server failure keeps the key - the same file is the same upload`() {
        api.uploadFailures += AppError.Server(500)
        vm.onFilePicked(uri)
        await("the failure") { it.phase is UploadPhase.Error }
        assertEquals(UploadPhase.Error(ErrorCodes.PROCESSING_FAILED, name, config, presentation = ru.finassist.pf.core.common.error.ErrorPresentation()), vm.state.value.phase)

        vm.backToGuide()
        awaitIdle()
        vm.onFilePicked(uri)
        awaitAccepted()
        assertEquals(api.keys[0], api.keys[1])
    }

    @Test fun `explicit REST retry permission reuses the original upload key even for an unknown 400`() {
        api.uploadFailures += AppError.Api("FUTURE_UPLOAD_ERROR", 400,
            response = ru.finassist.pf.core.common.error.ErrorResponseInfo("FUTURE_UPLOAD_ERROR", 400, requestRetryable = true))
        vm.onFilePicked(uri)
        await("the controlled refusal") { it.phase is UploadPhase.Error }
        awaitIdle()
        vm.onFilePicked(uri)
        awaitAccepted()
        assertEquals(2, api.keys.size)
        assertEquals(api.keys.first(), api.keys.last(), "retry=true applies to the same original key, not a fresh upload")
    }

    private fun uploadBodyHash(fileName: String = name, text: String = "OFXHEADER:100"): String {
        val content = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return java.security.MessageDigest.getInstance("SHA-256").digest(content + fileName.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    @Test fun `a legacy content-only pending action cannot silently become a fresh named action`() {
        val contentHash = java.security.MessageDigest.getInstance("SHA-256").digest("OFXHEADER:100".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val legacyAction = "statements.upload:$contentHash"
        val currentAction = "statements.upload:${uploadBodyHash()}"
        val keys = IdempotencyKeys(store)
        val old = kotlinx.coroutines.runBlocking { keys.keyFor(legacyAction) }
        vm.onFilePicked(uri)
        await("legacy unknown outcome is preserved") { it.phase is UploadPhase.RecoveryRequired }
        awaitIdle()
        assertTrue(api.keys.isEmpty())
        assertEquals(old, kotlinx.coroutines.runBlocking { keys.pendingKey(legacyAction) })
        assertNull(kotlinx.coroutines.runBlocking { keys.pendingKey(currentAction) }, "upgrade must not create a fresh alias")
        vm.startNewUpload()
        awaitAccepted()
        assertTrue(api.keys.single() != old.value)
        assertNull(kotlinx.coroutines.runBlocking { keys.pendingKey(legacyAction) })
    }

    @Test fun `manual retry preserves the picked name when the document provider changes its display name`() {
        var displayedName = name
        val provider = object : android.content.ContentProvider() {
            override fun onCreate() = true
            override fun getType(uri: Uri): String = "application/x-ofx"
            override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor =
                android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf(displayedName)) }
        }
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("test", provider)
        api.uploadFailures += AppError.Server(500)
        vm.onFilePicked(uri)
        await("the first named attempt fails") { it.phase is UploadPhase.Error }
        awaitIdle()
        displayedName = "provider_renamed.ofx"
        vm.retryUpload()
        awaitAccepted()
        assertEquals(listOf(name, name), api.names)
        assertEquals(api.keys.first(), api.keys.last())
    }

    @Test fun `manual retry cannot silently send changed file content under a new action`() {
        api.uploadFailures += AppError.Server(500)
        vm.onFilePicked(uri)
        await("the uncertain failure") { it.phase is UploadPhase.Error }
        awaitIdle()
        val original = api.keys.single()
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream("OFXHEADER:100\n".toByteArray()) }
        vm.retryUpload()
        await("changed content requires explicit new intent") { it.phase is UploadPhase.RecoveryRequired }
        awaitIdle()
        assertEquals(listOf(original), api.keys)
        vm.startNewUpload()
        awaitAccepted()
        assertEquals(2, api.keys.size)
        assertTrue(api.keys.last() != original)
    }

    @Test fun `a restored upload UUID is kept without resending the same file`() {
        val hash = uploadBodyHash()
        val keys = IdempotencyKeys(store)
        val oldKey = kotlinx.coroutines.runBlocking { keys.keyFor("statements.upload:$hash") }
        vm.onFilePicked(uri)
        await("unsafe replay blocked") { it.phase is UploadPhase.RecoveryRequired }
        awaitIdle()
        assertTrue(api.keys.isEmpty())
        assertFalse(vm.state.value.uploadRetryReady)
        assertEquals(oldKey, kotlinx.coroutines.runBlocking { keys.keyFor("statements.upload:$hash") })
        vm.startNewUpload()
        vm.startNewUpload() // double tap cannot create two new logical uploads
        awaitAccepted()
        assertEquals(1, api.keys.size)
        assertTrue(api.keys.single() != oldKey.value)
        assertEquals(name, assertIs<UploadPhase.Busy>(vm.state.value.phase).fileName)
    }

    @Test fun `queued initial retry readiness cannot enable an unknown upload key`() {
        // Cache hits need not suspend. Keep storage immediate and queue Main so a newly launched readiness
        // collector runs only after the upload continuation has published its unknown-outcome state.
        val cached = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            private var preferences: androidx.datastore.preferences.core.Preferences = androidx.datastore.preferences.core.emptyPreferences()
            override val data get() = flowOf(preferences)
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences): androidx.datastore.preferences.core.Preferences =
                transform(preferences).also { preferences = it }
        }
        val action = "statements.upload:${uploadBodyHash()}"
        val keys = IdempotencyKeys(cached)
        val original = kotlinx.coroutines.runBlocking { keys.keyFor(action) }
        Dispatchers.setMain(StandardTestDispatcher(dispatcher.scheduler))
        vm = UploadViewModel(context, api, repository, StaticFlags(), tracker, IdempotencyKeys(cached), clock,
            ElapsedClock { dispatcher.scheduler.currentTime })
        dispatcher.scheduler.runCurrent()

        vm.onFilePicked(uri)
        advanceUntil("the unknown-key recovery") { vm.state.value.phase is UploadPhase.RecoveryRequired }
        dispatcher.scheduler.runCurrent()
        assertFalse(vm.state.value.uploadRetryReady, "a ready REST gate is not proof of a replayable key")
        vm.retryUpload()
        dispatcher.scheduler.runCurrent()
        assertTrue(api.keys.isEmpty())
        assertEquals(original, kotlinx.coroutines.runBlocking { keys.pendingKey(action) })

        vm.startNewUpload()
        advanceUntil("the explicitly new upload") { (vm.state.value.phase as? UploadPhase.Busy)?.uploadId == "u1" }
        dispatcher.scheduler.runCurrent()
        assertTrue(vm.state.value.uploadRetryReady, "the new proven action must observe its own readiness")
        assertEquals(1, api.keys.size)
        assertTrue(api.keys.single() != original.value)
    }

    @Test fun `expired upload proof shows unknown outcome and renews the key only on explicit new upload`() {
        api.offline = true
        vm.onFilePicked(uri)
        await("the lost response") { it.uploadOffline }
        awaitIdle()
        val originalKey = api.keys.single()
        dispatcher.scheduler.advanceTimeBy(24L * 60 * 60 * 1000)
        dispatcher.scheduler.runCurrent()
        api.offline = false
        vm.retryUpload()
        await("unknown previous outcome") { it.phase is UploadPhase.RecoveryRequired }
        awaitIdle()
        assertEquals(listOf(originalKey), api.keys)
        assertIs<UploadPhase.RecoveryRequired>(vm.state.value.phase)
        vm.startNewUpload()
        awaitAccepted()
        assertEquals(2, api.keys.size)
        assertTrue(api.keys.last() != originalKey)
    }

    @Test fun `the same content under another name is another upload with its own key (FIN-340)`() {
        // The server compares the content and the name under a key: reusing the key for a renamed copy would be
        // 409 IDEMPOTENCY_CONFLICT. Every attempt of one pick sends the name it was picked with.
        val renamed: Uri = Uri.parse("content://test/operations_2026_copy.ofx")
        shadowOf(context.contentResolver).registerInputStreamSupplier(renamed) { ByteArrayInputStream("OFXHEADER:100".toByteArray()) }
        api.uploadFailures += AppError.Server(500)
        vm.onFilePicked(uri)
        await("the failure") { it.phase is UploadPhase.Error }

        vm.backToGuide()
        awaitIdle()
        vm.onFilePicked(renamed)
        awaitAccepted()
        assertTrue(api.keys[0] != api.keys[1])
        assertEquals(listOf("operations_2026.ofx", "operations_2026_copy.ofx"), api.names)
        assertEquals("operations_2026_copy.ofx", assertIs<UploadPhase.Busy>(vm.state.value.phase).fileName)
    }

    @Test fun `after process death the same file and name keep an unknown key while a renamed copy is a new action (FIN-340)`() {
        // A store of its own over one file: the first «process» ends by cancelling its scope, the second reads the file again.
        val file = File(context.cacheDir, "upload-death-${System.nanoTime()}.preferences_pb")
        val firstScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        vm = UploadViewModel(context, api, StatementsRepositoryImpl(firstStore, NoTokens), StaticFlags(), tracker, IdempotencyKeys(firstStore), clock, ElapsedClock { dispatcher.scheduler.currentTime })
        api.uploadFailures += AppError.Server(500)
        vm.onFilePicked(uri)
        await("the failure") { it.phase is UploadPhase.Error }
        awaitIdle()
        kotlinx.coroutines.runBlocking { firstScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin() }

        val secondStore = PreferenceDataStoreFactory.create { file }
        vm = UploadViewModel(context, api, StatementsRepositoryImpl(secondStore, NoTokens), StaticFlags(), tracker, IdempotencyKeys(secondStore), clock, ElapsedClock { dispatcher.scheduler.currentTime })
        val renamed: Uri = Uri.parse("content://test/operations_2026_copy.ofx")
        shadowOf(context.contentResolver).registerInputStreamSupplier(renamed) { ByteArrayInputStream("OFXHEADER:100".toByteArray()) }
        api.uploadFailures += AppError.Server(500)
        vm.onFilePicked(renamed)
        await("the copy's failure") { it.phase is UploadPhase.Error }
        awaitIdle()
        vm.backToGuide()
        vm.onFilePicked(uri)
        await("the original action has unknown age") { it.phase is UploadPhase.RecoveryRequired }
        awaitIdle()
        assertEquals(listOf(name, "operations_2026_copy.ofx"), api.names)
        val retained = kotlinx.coroutines.runBlocking { IdempotencyKeys(secondStore).pendingKey("statements.upload:${uploadBodyHash()}") }
        assertEquals(api.keys[0], retained?.value)
        assertNull(retained?.replayWindow)
        vm.retryUpload()
        awaitIdle()
        assertEquals(2, api.keys.size, "restoration does not authorize another POST")
        vm.startNewUpload()
        awaitAccepted()
        assertEquals(listOf(name, "operations_2026_copy.ofx", name), api.names)
        assertTrue(api.keys[0] != api.keys[2], "only explicit new intent replaces the retained unknown-age UUID")
        assertTrue(api.keys[1] != api.keys[0], "a renamed copy is another action")
    }

    @Test fun `an accepted file is a final answer - the same file later is a new upload`() {
        vm.onFilePicked(uri)
        awaitAccepted()
        assertTrue(Events.STATEMENTS_UPLOAD_STARTED in tracker.names())
        awaitIdle()
        vm.onFilePicked(uri)
        await("the second upload") { api.keys.size == 2 }
        assertTrue(api.keys[0] != api.keys[1])
    }

    @Test fun `a request still in progress is repeated with the same key`() {
        api.uploadFailures += AppError.InProgress(retryAfterSeconds = 1)
        vm.onFilePicked(uri)
        await("the first attempt") { api.keys.size == 1 }
        advanceUntil("the repeated request") { (vm.state.value.phase as? UploadPhase.Busy)?.uploadId == "u1" }
        assertEquals(2, api.keys.size)
        assertEquals(api.keys[0], api.keys[1])
    }

    @Test fun `a second pick while a file is being sent is ignored`() {
        val gate = CompletableDeferred<Unit>()
        api.uploadGate = gate
        vm.onFilePicked(uri)
        await("the upload in flight") { api.keys.size == 1 }
        vm.onFilePicked(uri)
        gate.complete(Unit)
        awaitAccepted()
        assertEquals(1, api.keys.size)
    }

    // ---- progress ----

    @Test fun `progress moves the bar and complete opens the result`() {
        val live = liveStream()
        vm.onFilePicked(uri)
        awaitAccepted()
        assertEquals(0.05f, assertIs<UploadPhase.Busy>(vm.state.value.phase).progress)
        live.awaitSubscribed()

        live.tryEmit(ImportEvent.Progress(ImportProgress(ImportStage.DEDUP, 40)))
        await("40 %") { (it.phase as? UploadPhase.Busy)?.progress == 0.4f }
        // The bar never goes back to empty.
        live.tryEmit(ImportEvent.Progress(ImportProgress(ImportStage.PARSING, 1)))
        await("the floor of the bar") { (it.phase as? UploadPhase.Busy)?.progress == 0.05f }

        val result = importResult(newCount = 7)
        live.tryEmit(ImportEvent.Complete(result))
        await("the result") { it.doneUploadId == "u1" }
        assertEquals(result, repository.lastResult("u1"))
        assertEquals(mapOf("new_count" to "7"), tracker.events.single { it.first == Events.STATEMENTS_UPLOAD_COMPLETED }.second)

        vm.consumeDone()
        assertNull(vm.state.value.doneUploadId)
    }

    @Test fun `a parsing failure shows the error`() {
        api.streams += flowOf(ImportEvent.Error(ErrorCodes.PROCESSING_FAILED, "failed"))
        vm.onFilePicked(uri)
        await("the parsing error") { it.phase is UploadPhase.Error }
        assertEquals(UploadPhase.Error(ErrorCodes.PROCESSING_FAILED, name, config, presentation = ru.finassist.pf.core.common.error.ErrorPresentation()), vm.state.value.phase)
        assertNull(vm.state.value.doneUploadId)
    }

    @Test fun `parsing cancelled on the server returns to the guide`() {
        api.streams += flowOf(ImportEvent.Error(ErrorCodes.CANCELLED, "cancelled"))
        vm.onFilePicked(uri)
        // The guide is on screen before the pick as well: wait for the stream to end. The event is tracked on the IO
        // thread just before the state changes, so the guide is awaited after it, not read at once.
        await("the cancellation") { Events.STATEMENTS_UPLOAD_CANCELLED in tracker.names() }
        await("the guide") { it.phase is UploadPhase.Guide }
        assertEquals(UploadPhase.Guide(config), vm.state.value.phase)
    }

    @Test fun `a stream error other than the network fails the upload`() {
        api.streams += flow { throw AppError.Api(ErrorCodes.NOT_FOUND, 404) }
        vm.onFilePicked(uri)
        await("the error") { it.phase is UploadPhase.Error }
        assertEquals(UploadPhase.Error(ErrorCodes.NOT_FOUND, name, config, presentation = ru.finassist.pf.core.common.error.ErrorPresentation()), vm.state.value.phase)
    }

    // ---- maintenance and an unsupported version (FIN-177) ----

    @Test fun `false maintenance resumes an existing upload stream once after its original wait`() {
        api.streams += flow { throw AppError.Maintenance(null, 15,
            ru.finassist.pf.core.common.error.ErrorResponseInfo("MAINTENANCE", 503, requestRetryable = false,
                timing = ru.finassist.pf.core.common.time.ResponseTiming(receivedElapsedMillis = 0))) }
        api.streams += flowOf(ImportEvent.Complete(importResult()))
        vm.onFilePicked(uri)
        awaitAccepted()
        await("known maintenance interruption") { Events.STATEMENTS_UPLOAD_INTERRUPTED in tracker.names() }
        assertIs<UploadPhase.Busy>(vm.state.value.phase)
        vm.stopWatching()
        vm.watch()
        dispatcher.scheduler.advanceTimeBy(14_000)
        dispatcher.scheduler.runCurrent()
        assertEquals(1, api.streamOpens.get())
        vm.stopWatching()
        vm.watch()
        dispatcher.scheduler.advanceTimeBy(999)
        dispatcher.scheduler.runCurrent()
        assertEquals(1, api.streamOpens.get())
        dispatcher.scheduler.advanceTimeBy(1)
        dispatcher.scheduler.runCurrent()
        await("the existing upload result") { it.doneUploadId == "u1" }
        assertEquals(2, api.streamOpens.get())
        assertEquals(1, api.keys.size, "only the read resumes; original upload POST is not resent")
    }

    @Test fun `maintenance on re-opening the stream is not a parsing failure - the result shows once the screen is back`() {
        api.streams += flowOf(ImportEvent.Progress(ImportProgress(ImportStage.PARSING, 30)))
        api.streams += flow { throw AppError.Maintenance(until = null, retryAfterSeconds = 600) }
        vm.onFilePicked(uri)
        awaitAccepted()
        advanceUntil("the interruption") { Events.STATEMENTS_UPLOAD_INTERRUPTED in tracker.names() }
        assertEquals(2, api.streamOpens.get())
        val busy = assertIs<UploadPhase.Busy>(vm.state.value.phase, "the upload stays on screen")
        assertEquals("u1", busy.uploadId)
        assertEquals(mapOf("reason" to "maintenance"), tracker.events.single { it.first == Events.STATEMENTS_UPLOAD_INTERRUPTED }.second)
        assertTrue(Events.STATEMENTS_UPLOAD_FAILED !in tracker.names())

        // After the maintenance the screen is started again: the same upload, its result.
        api.streams += flowOf(ImportEvent.Complete(importResult(newCount = 3)))
        vm.stopWatching()
        vm.watch()
        await("the result") { it.doneUploadId == "u1" }
        assertEquals(3, api.streamOpens.get())
        assertTrue(Events.STATEMENTS_UPLOAD_COMPLETED in tracker.names())
        assertTrue(Events.STATEMENTS_UPLOAD_FAILED !in tracker.names())
    }

    @Test fun `the screen started again under the same maintenance tracks the interruption once`() {
        api.streams += flow { throw AppError.Maintenance(until = null, retryAfterSeconds = 600) }
        api.streams += flow { throw AppError.Maintenance(until = null, retryAfterSeconds = 600) }
        vm.onFilePicked(uri)
        awaitAccepted()
        await("the interruption") { Events.STATEMENTS_UPLOAD_INTERRUPTED in tracker.names() }
        await("the first stream ended") { api.streamOpens.get() == 1 }
        vm.stopWatching()
        vm.watch()
        await("the second opening") { api.streamOpens.get() == 2 }
        vm.stopWatching()
        assertEquals(1, tracker.names().count { it == Events.STATEMENTS_UPLOAD_INTERRUPTED })
        assertIs<UploadPhase.Busy>(vm.state.value.phase)
    }

    @Test fun `an unsupported version on opening the stream is not a parsing failure`() {
        api.streams += flow { throw AppError.UpdateRequired() }
        vm.onFilePicked(uri)
        awaitAccepted()
        await("the interruption") { Events.STATEMENTS_UPLOAD_INTERRUPTED in tracker.names() }
        assertIs<UploadPhase.Busy>(vm.state.value.phase)
        assertEquals(mapOf("reason" to "update_required"), tracker.events.single { it.first == Events.STATEMENTS_UPLOAD_INTERRUPTED }.second)
        assertTrue(Events.STATEMENTS_UPLOAD_FAILED !in tracker.names())
    }

    @Test fun `maintenance before the file was accepted returns to the guide and keeps the key`() {
        api.uploadFailures += AppError.Maintenance(until = null, retryAfterSeconds = null)
        vm.onFilePicked(uri)
        await("the interruption") { Events.STATEMENTS_UPLOAD_INTERRUPTED in tracker.names() }
        // Tracked just before the state changes (IO thread): await the guide instead of reading it at once.
        await("the guide") { it.phase is UploadPhase.Guide }
        assertEquals(UploadPhase.Guide(config), vm.state.value.phase)
        assertFalse(vm.state.value.uploadOffline)
        assertTrue(Events.STATEMENTS_UPLOAD_FAILED !in tracker.names())

        // A 503 is not a final answer: the same file after the maintenance is the same upload.
        awaitIdle()
        vm.onFilePicked(uri)
        awaitAccepted()
        assertEquals(api.keys[0], api.keys[1])
    }

    @Test fun `a dropped stream is re-opened and picks up the result`() {
        api.streams += flow { throw AppError.Offline() }
        api.streams += flowOf(ImportEvent.Complete(importResult()))
        vm.onFilePicked(uri)
        awaitAccepted()
        advanceUntil("the result") { vm.state.value.doneUploadId == "u1" }
        assertEquals(2, api.streamOpens.get())
    }

    @Test fun `a stream closed after progress without a terminal event is re-opened, no error is shown`() {
        val seen = mutableListOf<UploadPhase>()
        api.streams += flowOf(ImportEvent.Progress(ImportProgress(ImportStage.PARSING, 30)))
        api.streams += flow {
            emit(ImportEvent.Progress(ImportProgress(ImportStage.RULES, 80)))
            throw AppError.Server(502)
        }
        api.streams += flowOf(ImportEvent.Complete(importResult()))
        vm.onFilePicked(uri)
        awaitAccepted()
        advanceUntil("the result") {
            seen += vm.state.value.phase
            vm.state.value.doneUploadId == "u1"
        }
        assertEquals(3, api.streamOpens.get())
        assertTrue(seen.none { it is UploadPhase.Error || it is UploadPhase.Offline }, "no error between the openings: $seen")
    }

    @Test fun `coming back to the screen during a 429 pause waits again instead of failing, one stream at a time`() {
        var active = 0
        var maxActive = 0
        fun tracked(f: Flow<ImportEvent>) = f.onStart { synchronized(this) { active++; maxActive = maxOf(maxActive, active) } }
            .onCompletion { synchronized(this) { active-- } }
        val limitedAt = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val limited = { tracked(flow { limitedAt += dispatcher.scheduler.currentTime; throw AppError.RateLimited(null, retryAfterSeconds = 60) }) }
        api.streams += tracked(flowOf(ImportEvent.Progress(ImportProgress(ImportStage.PARSING, 30))))
        api.streams += limited()
        // The next opening after the retained wait gets a fresh 429 with its own wait.
        api.streams += limited()
        api.streams += tracked(flowOf(ImportEvent.Complete(importResult())))
        vm.onFilePicked(uri)
        awaitAccepted()
        await("the first stream") { api.streamOpens.get() == 1 }
        // The re-open pause starts only once the first stream has ended on the IO thread: move the test clock on
        // until it has, not once (a single step could run before the pause was scheduled).
        advanceUntil("the drop was re-opened and got 429") { api.streamOpens.get() == 2 }
        dispatcher.scheduler.advanceTimeBy(4_000)
        dispatcher.scheduler.runCurrent()
        vm.stopWatching()
        vm.watch()
        assertEquals(2, api.streamOpens.get(), "resume must not bypass the retained Retry-After")
        assertIs<UploadPhase.Busy>(vm.state.value.phase, "a 429 on opening it again is not an error")
        fun advanceTo(target: Long) {
            dispatcher.scheduler.advanceTimeBy(target - dispatcher.scheduler.currentTime)
            dispatcher.scheduler.runCurrent()
        }
        val firstRefusal = limitedAt.single()
        advanceTo(firstRefusal + 59_999)
        assertEquals(2, api.streamOpens.get(), "not before the original Retry-After")
        advanceTo(firstRefusal + 62_001)
        await("the retained wait ends") { api.streamOpens.get() == 3 }
        val secondRefusal = limitedAt.last()
        advanceTo(secondRefusal + 59_999)
        assertEquals(3, api.streamOpens.get(), "a fresh 429 gets its own Retry-After")
        advanceTo(secondRefusal + 62_001)
        await("the result") { vm.state.value.doneUploadId == "u1" }
        assertEquals(4, api.streamOpens.get())
        assertTrue(Events.STATEMENTS_UPLOAD_FAILED !in tracker.names())
        assertEquals(1, maxActive, "never two streams at once")
    }

    @Test fun `a malformed progress event fails the upload instead of re-opening for ever`() {
        api.streams += flow { throw AppError.Server(200) }
        vm.onFilePicked(uri)
        await("the error") { it.phase is UploadPhase.Error }
        assertEquals(1, api.streamOpens.get())
    }

    @Test fun `the stream stops with the screen and is re-opened once on its return`() {
        liveStream()
        vm.onFilePicked(uri)
        awaitAccepted()
        await("the stream") { api.streamOpens.get() == 1 }
        // Already listening: no second stream.
        vm.watch()
        assertEquals(1, api.streamOpens.get())
        vm.stopWatching()
        liveStream()
        vm.watch()
        await("the stream opened again") { api.streamOpens.get() == 2 }
    }

    @Test fun `watch outside of an accepted upload does nothing`() {
        vm.watch()
        assertEquals(0, api.streamOpens.get())
    }

    // ---- «Прервать разбор» ----

    @Test fun `cancel before the server accepted the file stops the request`() {
        val gate = CompletableDeferred<Unit>()
        api.uploadGate = gate
        vm.onFilePicked(uri)
        await("the upload in flight") { api.keys.size == 1 }
        assertNull(assertIs<UploadPhase.Busy>(vm.state.value.phase).uploadId)
        vm.askCancel(true)
        vm.cancelImport()
        assertEquals(UploadPhase.Guide(config), vm.state.value.phase)
        assertFalse(vm.state.value.askCancel)
        // A late answer of the cancelled request changes nothing.
        gate.complete(Unit)
        assertEquals(UploadPhase.Guide(config), vm.state.value.phase)
        assertTrue(api.deleted.isEmpty())
    }

    @Test fun `cancel after acceptance deletes the upload and returns to the guide`() {
        liveStream()
        vm.onFilePicked(uri)
        awaitAccepted()
        vm.askCancel(true)
        assertTrue(vm.state.value.askCancel)
        vm.cancelImport()
        assertEquals(listOf("u1"), api.deleted)
        assertEquals(UploadPhase.Guide(config), vm.state.value.phase)
        assertFalse(vm.state.value.askCancel)
        assertTrue(Events.STATEMENTS_UPLOAD_CANCELLED in tracker.names())
    }

    @Test fun `a failed cancel keeps the parsing on screen`() {
        api.deleteFailure = AppError.Offline()
        liveStream()
        vm.onFilePicked(uri)
        awaitAccepted()
        vm.askCancel(true)
        vm.cancelImport()
        val busy = assertIs<UploadPhase.Busy>(vm.state.value.phase)
        assertFalse(busy.cancelling)
        assertFalse(vm.state.value.askCancel)
    }

    @Test fun `a result that raced a successful cancel is dropped`() {
        val live = liveStream()
        val gate = CompletableDeferred<Unit>()
        api.deleteGate = gate
        vm.onFilePicked(uri)
        awaitAccepted()
        live.awaitSubscribed()
        vm.cancelImport()
        assertTrue(assertIs<UploadPhase.Busy>(vm.state.value.phase).cancelling)

        live.tryEmit(ImportEvent.Complete(importResult()))
        assertNull(vm.state.value.doneUploadId)
        gate.complete(Unit)
        assertEquals(UploadPhase.Guide(config), vm.state.value.phase)
        assertNull(vm.state.value.doneUploadId)
        assertNull(repository.lastResult("u1"))
    }

    @Test fun `a result that raced a failed cancel is shown`() {
        val live = liveStream()
        val gate = CompletableDeferred<Unit>()
        api.deleteGate = gate
        api.deleteFailure = AppError.Server(500)
        vm.onFilePicked(uri)
        awaitAccepted()
        live.awaitSubscribed()
        vm.cancelImport()
        val result = importResult()
        live.tryEmit(ImportEvent.Complete(result))
        gate.complete(Unit)
        await("the result") { it.doneUploadId == "u1" }
        assertEquals(result, repository.lastResult("u1"))
    }

    @Test fun `maintenance on the stream while the cancel is in flight - a successful cancel still returns to the guide`() {
        val cut = CompletableDeferred<Unit>()
        api.streams += flow { cut.await(); throw AppError.Maintenance(until = null, retryAfterSeconds = 600) }
        val gate = CompletableDeferred<Unit>()
        api.deleteGate = gate
        vm.onFilePicked(uri)
        awaitAccepted()
        await("the stream") { api.streamOpens.get() == 1 }
        vm.cancelImport()
        cut.complete(Unit)
        await("the interruption") { Events.STATEMENTS_UPLOAD_INTERRUPTED in tracker.names() }
        assertTrue(assertIs<UploadPhase.Busy>(vm.state.value.phase).cancelling, "the cancel is still in flight")

        gate.complete(Unit)
        await("the guide") { it.phase is UploadPhase.Guide }
        assertEquals(UploadPhase.Guide(config), vm.state.value.phase)
        assertNull(vm.state.value.doneUploadId)
        assertTrue(Events.STATEMENTS_UPLOAD_CANCELLED in tracker.names())
        assertTrue(Events.STATEMENTS_UPLOAD_FAILED !in tracker.names())
    }

    @Test fun `maintenance on the stream and on the cancel - the upload stays and its result shows once the screen is back`() {
        val cut = CompletableDeferred<Unit>()
        api.streams += flow { cut.await(); throw AppError.Maintenance(until = null, retryAfterSeconds = 600) }
        val gate = CompletableDeferred<Unit>()
        api.deleteGate = gate
        api.deleteFailure = AppError.Maintenance(until = null, retryAfterSeconds = 600)
        vm.onFilePicked(uri)
        awaitAccepted()
        await("the stream") { api.streamOpens.get() == 1 }
        vm.cancelImport()
        cut.complete(Unit)
        await("the interruption") { Events.STATEMENTS_UPLOAD_INTERRUPTED in tracker.names() }
        gate.complete(Unit)
        await("the failed cancel") { (it.phase as? UploadPhase.Busy)?.cancelling == false }

        api.streams += flowOf(ImportEvent.Complete(importResult()))
        vm.stopWatching()
        vm.watch()
        await("the result") { it.doneUploadId == "u1" }
        assertTrue(Events.STATEMENTS_UPLOAD_FAILED !in tracker.names())
        assertTrue(Events.STATEMENTS_UPLOAD_CANCELLED !in tracker.names())
    }

    @Test fun `cancel with nothing being parsed does nothing`() {
        vm.cancelImport()
        assertEquals(UploadPhase.Guide(config), vm.state.value.phase)
        assertTrue(api.deleted.isEmpty())
    }
}
