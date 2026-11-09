package ru.finassist.pf.feature.analytics.impl.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.finassist.pf.core.api.AnalyticsApi
import ru.finassist.pf.core.api.ApiJson
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.model.*
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.error.ErrorResponseInfo
import ru.finassist.pf.core.common.time.DateRange
import ru.finassist.pf.core.common.time.ElapsedClock
import ru.finassist.pf.core.common.time.PeriodKey
import ru.finassist.pf.core.common.time.ReportZone
import ru.finassist.pf.core.blocks.BlockContext
import ru.finassist.pf.core.blocks.BlockTags
import ru.finassist.pf.core.blocks.CatalogV1
import ru.finassist.pf.core.designsystem.theme.PfTheme
import ru.finassist.pf.core.toggles.Experiment
import ru.finassist.pf.core.toggles.FeatureFlags
import ru.finassist.pf.core.toggles.Flag
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import ru.finassist.pf.feature.operations.api.OperationsRepository
import ru.finassist.pf.feature.statements.api.StatementsEvent
import ru.finassist.pf.feature.statements.api.StatementsRepository
import java.time.YearMonth
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Qase73/82/89 connect the real screen to its ViewModel; Qase83 combines VM paging and a catalog chart component.
 * Explicit replies and virtual polling time keep these obligations independent from emulator gesture timing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w390dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalyticsReliabilityComponentTest {
    @get:Rule val compose = createComposeRule()
    private val scheduler = TestCoroutineScheduler()
    private val stores = mutableListOf<ViewModelStore>()
    private val original by lazy {
        ApiJson.decodeFromString(Analytics.serializer(), javaClass.getResource("/analytics/september.json")!!.readText())
            .let { it.copy(blocks = it.blocks.filter { block -> block.id in setOf("tiles", "expense_categories", "regular_payments") }) }
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    @After fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    @Test fun qase73PeriodRetryAndDeferredRefusal() {
        val september = analyticsFixture("september")
        val year = analyticsFixture("year_2026")
        val expectedWrite = DismissRequest("r1", PeriodKey("2026-09"), TransferMode.WITH)
        val yearTarget = ReadRequest(PeriodTypeCode.YEAR, PeriodKey("2026-09"), TransferMode.WITH)
        val api = FakeApi(september)
        var active by mutableStateOf(opened(api))
        render { active }
        periodLabel("Сентябрь 2026").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ds.segment.0").assertIsOn()
        compose.onNodeWithTag("analytics.block.tiles").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { api.read = { _, _, _ -> throw AppError.Offline() } }
        compose.onNodeWithTag("ds.segment.2").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Не удалось сменить период", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("analytics.period.retry").assertIsDisplayed().assertIsEnabled()
        periodLabel("Сентябрь 2026").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ds.segment.0").assertIsOn()
        compose.onNodeWithTag("analytics.block.tiles").performScrollTo().assertIsDisplayed()
        val periodFailure = compose.runOnIdle {
            assertEquals(september, active.state.value.data, "keep the whole response, including money and source-zone ranges")
            assertEquals(2, api.reads)
            assertEquals(yearTarget, api.readRequests.last(), "the failed request keeps the finer September anchor")
            api.mutate = { throw knownRefusal() }
            assertNotNull(active.state.value.periodChangeErrorPresentation)
        }
        compose.onNodeWithContentDescription("Не подписка — Спортзал").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(REFUSAL_MESSAGE).assertIsDisplayed()
        compose.onNodeWithText("Не удалось сменить период", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("analytics.period.retry").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("Не подписка — Спортзал").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("analytics.unknown_mutation").assertDoesNotExist()
        val failedRequests = compose.runOnIdle {
            assertEquals(september, active.state.value.data)
            assertEquals(periodFailure, active.state.value.periodChangeErrorPresentation)
            assertEquals(listOf(expectedWrite), api.dismissRequests, "count API entry before the known refusal throws")
            active.onResume(null)
            api.readRequests.toList()
        }
        advance(30_000)
        compose.runOnIdle {
            assertEquals(2, api.reads, "resume after both failures cannot submit a hidden GET")
            assertEquals(failedRequests, api.readRequests)
            assertEquals(listOf(expectedWrite), api.dismissRequests, "resume cannot replay the PUT")
            assertEquals(september, active.state.value.data)
            assertEquals(periodFailure, active.state.value.periodChangeErrorPresentation)
            api.read = { type, _, _ -> if (type == PeriodTypeCode.YEAR) year else september }
        }
        compose.onNodeWithText(REFUSAL_MESSAGE).assertIsDisplayed()
        compose.onNodeWithTag("analytics.period.retry").performClick()
        periodLabel("2026").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ds.segment.2").assertIsOn()
        compose.onNodeWithTag("analytics.period.retry").assertDoesNotExist()
        compose.onNodeWithTag("ds.error.error").assertDoesNotExist()
        compose.onNodeWithText(REFUSAL_MESSAGE).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(failedRequests + yearTarget, api.readRequests, "the actual period retry must resend the exact failed triple")
            assertEquals(year, active.state.value.data)
            assertEquals(listOf(expectedWrite), api.dismissRequests)
        }
        compose.onNodeWithTag("ds.segment.0").performClick()
        periodLabel("Сентябрь 2026").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ds.segment.0").assertIsOn()
        compose.runOnIdle {
            assertEquals(september, active.state.value.data)
            assertEquals(failedRequests + listOf(yearTarget, ReadRequest(PeriodTypeCode.MONTH, PeriodKey("2026-09"), TransferMode.WITH)), api.readRequests)
            assertEquals(listOf(expectedWrite), api.dismissRequests)
        }

        // Independent command/selection phase: real UI callbacks, with replies held at the API boundary.
        val deferredApi = FakeApi(september)
        val heldWrite = CompletableDeferred<AnalyticsBlockUpdate>()
        val heldYear = CompletableDeferred<Analytics>()
        compose.runOnIdle {
            active = opened(deferredApi)
            deferredApi.mutate = { heldWrite.await() }
            deferredApi.read = { _, _, _ -> heldYear.await() }
        }
        compose.onNodeWithContentDescription("Не подписка — Спортзал").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(september, active.state.value.data)
            assertEquals(listOf(expectedWrite), deferredApi.dismissRequests)
            assertEquals(1, deferredApi.reads)
        }
        compose.onNodeWithText(REFUSAL_MESSAGE).assertDoesNotExist()
        // The segment has its own horizontal scroll parent. Reveal the vertical picker first.
        periodLabel("Сентябрь 2026").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ds.segment.2").performScrollTo().assertIsDisplayed().performClick()
        periodLabel("Сентябрь 2026").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ds.segment.0").assertIsOn()
        compose.onNodeWithTag("analytics.block.tiles").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("analytics.period.retry").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(heldWrite.isCompleted, "Year must be selected while the PUT is still in flight")
            assertEquals(september, active.state.value.data)
            assertEquals(1, deferredApi.reads, "selection cannot send GET before the PUT completes")
            assertEquals(listOf(expectedWrite), deferredApi.dismissRequests)
            heldWrite.completeExceptionally(knownRefusal())
            scheduler.runCurrent()
        }
        // Completing a suspended Main coroutine and observing its rendered state are separate UI turns.
        compose.runOnIdle {
            assertEquals(2, deferredApi.reads, "a known refusal resumes exactly one deferred GET without another tap")
            assertEquals(yearTarget, deferredApi.readRequests.last())
            assertFalse(heldYear.isCompleted)
            assertFalse(active.state.value.unknownMutation, "known refusal must not take Qase89's unknown-result branch")
        }
        // A quiet deferred read keeps the ready September until the held Year actually arrives.
        periodLabel("Сентябрь 2026").performScrollTo().assertIsDisplayed()
        periodLabel("2026").assertDoesNotExist()
        compose.onNodeWithTag("analytics.period.retry").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(september, active.state.value.data)
            heldYear.complete(year)
            scheduler.runCurrent()
        }
        periodLabel("2026").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ds.segment.2").assertIsOn()
        compose.onNodeWithTag("analytics.period.rolling_year").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("analytics.period.retry").assertDoesNotExist()
        compose.onNodeWithTag("analytics.unknown_mutation").assertDoesNotExist()
        advance(30_000)
        compose.runOnIdle {
            assertEquals(year, active.state.value.data)
            assertEquals(2, deferredApi.reads, "completion cannot schedule a repeated read")
            assertEquals(listOf(expectedWrite), deferredApi.dismissRequests, "no retry of the refused command")
            assertEquals(1, deferredApi.writes)
        }
    }

    @Test fun qase82RetainedSnapshotsAndRefusedMutation() {
        val api = FakeApi(original)
        val vm = opened(api)
        render { vm }
        val unavailable = original.copy(blocks = original.blocks.map { block ->
            if (block.id == "expense_categories") block.copy(status = BlockStatus.UNAVAILABLE,
                emptyText = UNAVAILABLE) else block
        })
        compose.runOnIdle {
            api.read = { _, _, _ -> marked(unavailable) }
            vm.load(quiet = true)
            assertEquals(original, vm.state.value.data)
        }
        expenseRow().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(UNAVAILABLE).assertDoesNotExist()
        advance(30_000)
        compose.onNodeWithTag("analytics.retry").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            assertTrue(vm.state.value.recalculationPaused)
            val pausedReads = api.reads
            vm.onResume(null)
            assertEquals(pausedReads, api.reads, "resume must not restart the exhausted polling budget")
            var replies = 0
            api.read = { _, _, _ -> if (replies++ < 4) marked(unavailable) else unavailable }
        }
        compose.onNodeWithTag("analytics.retry").performClick()
        advance(12_000)
        compose.onNodeWithText(UNAVAILABLE).performScrollTo().assertIsDisplayed()
        expenseRow().assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(unavailable, vm.state.value.data)
            assertFalse(vm.state.value.recalculating)
            api.read = { _, _, _ -> marked(original) }
            vm.load(quiet = true)
            assertEquals(unavailable, vm.state.value.data, "a marked recovery must keep the whole unavailable snapshot")
        }
        compose.onNodeWithText(UNAVAILABLE).performScrollTo().assertIsDisplayed()
        expenseRow().assertDoesNotExist()
        advance(30_000)
        compose.runOnIdle { api.read = { _, _, _ -> original } }
        compose.onNodeWithTag("analytics.retry").performScrollTo().performClick()
        expenseRow().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(UNAVAILABLE).assertDoesNotExist()

        val year = original.copy(params = original.params!!.copy(period = PeriodTypeCode.YEAR, date = PeriodKey("2026")),
            period = original.period!!.copy(range = range("2026-01", 12)))
        val yearReply = CompletableDeferred<Analytics>()
        compose.runOnIdle {
            var replies = 0
            api.read = { _, _, _ -> if (replies++ == 0) marked(year) else yearReply.await() }
            vm.setPeriodType(PeriodTypeCode.YEAR)
        }
        advance(3_000)
        compose.onNodeWithTag("analytics.updating").assertIsDisplayed()
        compose.onNodeWithTag("analytics.empty").assertDoesNotExist()
        compose.onNodeWithTag("analytics.block.tiles").assertDoesNotExist()
        compose.runOnIdle { yearReply.complete(year); scheduler.runCurrent() }
        compose.onNodeWithText("2026", substring = false).performScrollTo().assertIsDisplayed()

        compose.runOnIdle {
            api.read = { _, _, _ -> original }
            vm.setPeriodType(PeriodTypeCode.MONTH)
            api.read = { _, _, _ -> marked(original) }
            vm.load(quiet = true)
            api.mutate = { throw AppError.Api("FUTURE_POLICY", 429,
                response = ErrorResponseInfo("FUTURE_POLICY", 429, requestRetryable = false)) }
        }
        compose.onNodeWithContentDescription("Не подписка — Спортзал").performScrollTo().performClick()
        advance(30_000)
        compose.onNodeWithTag("analytics.retry").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(1, api.writes)
            assertEquals(original, vm.state.value.data)
            assertTrue(vm.state.value.recalculationPaused, "a refused mutation must not cancel the independent bounded read")
            api.read = { _, _, _ -> original }
        }
        compose.onNodeWithTag("analytics.retry").performClick()
        compose.runOnIdle {
            assertEquals(1, api.writes)
            assertEquals(original, vm.state.value.data)
            assertFalse(vm.state.value.recalculating)
        }
    }

    @Test fun qase83HistoryPagingAndSelection() {
        val api = FakeApi(history("2026-10"))
        api.read = { _, key, _ -> history(key?.value ?: "2026-10") }
        api.pageRead = { cursor -> AnalyticsChartPage(historyBlock(cursor.removePrefix("page:").toInt()), ZONE) }
        val vm = opened(api)
        val observedMonths = chart(vm).points.map(::month).toMutableSet()
        for (index in 1..5) {
            vm.loadChartPage(older = true)
            scheduler.runCurrent()
            assertNull(vm.state.value.chartError, "page $index must be accepted before the next edge request")
            assertEquals("page:$index", api.cursors.last())
            assertTrue(chart(vm).points.size <= 180, "only three neighbouring pages may remain")
            observedMonths += chart(vm).points.map(::month)
            assertEquals(PeriodKey("2026-10"), vm.state.value.data?.params?.date, "paging cannot select a month")
        }
        assertEquals((0 until 322).map { YearMonth.of(2000, 1).plusMonths(it.toLong()).toString() }.toSet(), observedMonths,
            "every calendar month from January 2000 through October 2026 must be reachable, without gaps")
        assertEquals("2000-01", month(chart(vm).points.first()))
        assertEquals(0.0, chart(vm).points.first().value, "the earliest covered zero must be retained")
        vm.onAction(Block(id = "monthly_chart", type = BlockType.CHART), selection("2000-01")) { error("chart selection stays in Analytics") }
        assertEquals(PeriodKey("2000-01"), vm.state.value.data?.params?.date)
        assertEquals(0, chart(vm).highlightIndex)
        // Pages 5/4/3 are still resident; the next newer cursor reloads evicted page 2.
        for (index in 2 downTo 0) {
            vm.loadChartPage(older = false)
            scheduler.runCurrent()
            assertNull(vm.state.value.chartError, "newer page $index must be accepted")
            assertEquals("page:$index", api.cursors.last())
            assertTrue(chart(vm).points.size <= 180)
            assertEquals(PeriodKey("2000-01"), vm.state.value.data?.params?.date)
            assertEquals("Selected 2000-01", chart(vm).notes.single().text, "evicted selection notes belong to the full response")
        }
        assertNull(chart(vm).highlightIndex, "eviction hides the point without changing the selected period")
        assertEquals("2026-10", month(chart(vm).points.last()))
        // Paging is checked above; this component boundary tests the catalog's actual selection capability.
        // The Maestro companion retains the real virtualized pager, gestures and period header.
        compose.setContent {
            val state by vm.state.collectAsState()
            val block = assertNotNull(state.chartBlock)
            PfTheme {
                Column {
                    assertNotNull(CatalogV1.registry().prepare(block)).draw(BlockContext(
                        "RUB", object : BlockTags("pilot") { override fun chart(blockId: String) = "pilot.history" },
                        onAction = { _, _ -> error("period selection uses its narrow capability") },
                        sourceZone = ZoneId.of(ZONE),
                        onSelectAnalyticsPeriod = { source, action ->
                            vm.onAction(source, action) { error("chart selection stays in Analytics") }
                        },
                    ))
                }
            }
        }
        compose.onNodeWithTag("pilot.history.point.2026-10").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("pilot.history.point.2026-10").assertIsSelected()
        compose.runOnIdle {
            assertEquals(PeriodKey("2026-10"), vm.state.value.data?.params?.date)
            assertEquals(0, api.writes)
        }
    }

    @Test fun qase89UnknownReadRecoveryAndDeferredSelection() {
        val api = FakeApi(original)
        val statements = FakeStatements()
        var active by mutableStateOf(opened(api, statements))
        render { active }
        fun failMutation() {
            api.mutate = { throw AppError.Offline() }
            active.dismissRegular("r1")
        }
        val year = original.copy(params = original.params!!.copy(period = PeriodTypeCode.YEAR, date = PeriodKey("2026")),
            period = original.period!!.copy(range = range("2026-01", 12)))
        compose.runOnIdle {
            failMutation()
            api.read = { _, _, _ -> year }
            active.setPeriodType(PeriodTypeCode.YEAR)
        }
        compose.onNodeWithTag("analytics.unknown_read_recovery").assertIsDisplayed().assertIsEnabled()
        val heldYear = CompletableDeferred<Analytics>()
        compose.runOnIdle { api.read = { _, _, _ -> heldYear.await() } }
        compose.onNodeWithTag("analytics.unknown_read_recovery").performClick()
        compose.onNodeWithTag("analytics.updating").assertIsDisplayed()
        compose.onNodeWithTag("analytics.empty").assertDoesNotExist()
        compose.onNodeWithText("Загрузите выписку", substring = true).assertDoesNotExist()
        compose.runOnIdle { heldYear.complete(year); scheduler.runCurrent() }
        compose.onNodeWithText("2026").performScrollTo().assertIsDisplayed()
        assertUnknown()

        compose.runOnIdle {
            api.read = { _, _, _ -> original }
            active = opened(api, statements)
            failMutation()
            statements.events.tryEmit(StatementsEvent.UploadDeleted("synthetic-upload"))
            scheduler.runCurrent()
        }
        compose.onNodeWithTag("analytics.unknown_read_recovery").assertIsDisplayed()
        compose.onNodeWithTag("analytics.empty").assertDoesNotExist()
        val heldEmpty = CompletableDeferred<Analytics>()
        compose.runOnIdle { api.read = { _, _, _ -> heldEmpty.await() } }
        compose.onNodeWithTag("analytics.unknown_read_recovery").performClick()
        compose.onNodeWithTag("analytics.updating").assertIsDisplayed()
        compose.onNodeWithTag("analytics.empty").assertDoesNotExist()
        compose.runOnIdle { heldEmpty.complete(Analytics(false, calculation = AnalyticsCalculation(10, false))); scheduler.runCurrent() }
        compose.onNodeWithTag("analytics.empty").assertIsDisplayed()
        assertUnknown()

        val heldWrite = CompletableDeferred<AnalyticsBlockUpdate>()
        compose.runOnIdle {
            api.read = { _, _, _ -> original }
            active = opened(api)
            api.mutate = { heldWrite.await() }
            active.dismissRegular("r1")
            val before = api.reads
            active.setPeriodType(PeriodTypeCode.YEAR)
            assertEquals(before, api.reads, "a deferred selection cannot send GET while PUT is in flight")
            heldWrite.completeExceptionally(AppError.Server(500))
            scheduler.runCurrent()
            assertEquals(before, api.reads, "unknown completion cannot automatically load the deferred Year")
            assertEquals(PeriodKey("2026-09"), active.state.value.data?.params?.date)
        }
        compose.onNodeWithText("Сентябрь 2026").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ds.segment.0").assertIsOn()
        compose.onNodeWithTag("ds.refresh.indicator").assertDoesNotExist()
        val deferredYear = CompletableDeferred<Analytics>()
        compose.runOnIdle {
            var replies = 0
            api.read = { _, _, _ -> if (replies++ == 0) year else deferredYear.await() }
        }
        compose.onNodeWithTag("ds.segment.2").performClick()
        compose.onNodeWithTag("analytics.unknown_read_recovery").assertIsDisplayed().performClick()
        compose.onNodeWithTag("analytics.updating").assertIsDisplayed()
        compose.runOnIdle { deferredYear.complete(year); scheduler.runCurrent() }
        compose.onNodeWithText("2026").performScrollTo().assertIsDisplayed()
        assertUnknown()
        compose.runOnIdle { assertEquals(3, api.writes, "exactly one command in each independent scenario; no recovery PUT") }
    }

    private fun analyticsFixture(name: String) = ApiJson.decodeFromString(Analytics.serializer(),
        javaClass.getResource("/analytics/$name.json")!!.readText())

    private fun periodLabel(text: String) = compose.onNode(hasText(text, substring = false) and
        hasAnyAncestor(hasTestTag("ds.period.pick")), useUnmergedTree = true)

    private fun knownRefusal(): AppError.Api {
        val wire = ApiJson.decodeFromString(ApiError.serializer(),
            """{"code":"FUTURE_OPERATION_FAILURE","message":"internal diagnostic must not be displayed","user_message":"$REFUSAL_MESSAGE","severity":"warning"}""")
        return AppError.Api(wire.code, 422, response = ErrorResponseInfo(wire.code, 422,
            presentation = wire.presentation, requestRetryable = wire.retryable))
    }

    private data class ReadRequest(val period: PeriodTypeCode?, val date: PeriodKey?, val transferMode: TransferMode?)
    private data class DismissRequest(val id: String, val date: PeriodKey, val transferMode: TransferMode)

    private fun assertUnknown() {
        compose.onNodeWithTag("analytics.unknown_mutation").assertIsDisplayed()
        compose.onNodeWithText("Убрали из регулярных платежей").assertDoesNotExist()
    }

    private fun expenseRow() = compose.onAllNodesWithTag("analytics.expense_category")[0]
    private fun advance(millis: Long) = compose.runOnIdle { scheduler.advanceTimeBy(millis); scheduler.runCurrent() }
    private fun marked(value: Analytics) = value.copy(calculation = value.calculation!!.copy(recalculating = true),
        state = value.state!!.copy(recalculating = true))
    private fun render(vm: () -> AnalyticsViewModel) = compose.setContent {
        val selected = vm()
        val state by selected.state.collectAsState()
        PfTheme { AnalyticsContent(state, AnalyticsActions({}, {}, {}, { _, _ -> }, null), selected) }
    }

    private fun opened(api: FakeApi, statements: FakeStatements = FakeStatements()): AnalyticsViewModel {
        // Independent phases must not leave old API/event consumers attached to the reused fake.
        stores.forEach(ViewModelStore::clear)
        stores.clear()
        val vm = AnalyticsViewModel(api, NoAssistant, statements, object : OperationsRepository {
            override val categoryChanges = MutableSharedFlow<Unit>()
        }, NoAssistantFlags, NoTracking, ReportZone { ZoneId.of(ZONE) }, ElapsedClock { scheduler.currentTime }, object : SessionRepository {
            override val state = MutableStateFlow<SessionState>(SessionState.SignedIn("pilot-owner", "pilot-login"))
            override suspend fun consumeJustRegistered() = false
            override suspend fun signOut(reason: String?) { state.value = SessionState.SignedOut }
            override suspend fun clearLocal(reason: String?) = signOut(reason)
            override fun consumeSignOutReason(): String? = null
        })
        stores += ViewModelStore().also { it.put("pilot", vm) }
        return vm.apply { init(null) }
    }

    private fun history(key: String): Analytics {
        val selected = YearMonth.parse(key)
        val page = (java.time.temporal.ChronoUnit.MONTHS.between(selected, YearMonth.of(2026, 10)) / 60).toInt()
        return original.copy(params = AnalyticsParams(PeriodTypeCode.MONTH, PeriodKey(key), TransferMode.WITH),
            period = original.period!!.copy(range = range(key)),
            blocks = listOf(historyBlock(page, key)))
    }

    private fun historyBlock(page: Int, selected: String? = null): Block {
        val end = YearMonth.of(2026, 10).minusMonths(page * 60L)
        val start = maxOf(YearMonth.of(2000, 1), end.minusMonths(59))
        val points = (0..java.time.temporal.ChronoUnit.MONTHS.between(start, end).toInt()).map { index ->
            val key = start.plusMonths(index.toLong()).toString()
            ChartPoint(range(key), value = 0.0, coverage = Coverage.COMPLETE, action = selection(key))
        }
        return Block.of(BlockType.CHART, ChartData.serializer(), ChartData(ChartKind.BAR, ValueKind.AMOUNT, points,
            highlightIndex = points.indexOfFirst { month(it) == selected }.takeIf { it >= 0 },
            notes = listOf(ChartNote("Selected ${selected ?: "2026-10"}")),
            page = ChartPageInfo("pilot-history", if (page < 5) "page:${page + 1}" else null, if (page > 0) "page:${page - 1}" else null)))
            .copy(id = "monthly_chart", title = "Расходы по месяцам")
    }

    private fun chart(vm: AnalyticsViewModel) = assertNotNull(vm.state.value.chartBlock?.decode(ChartData.serializer()))
    private fun month(point: ChartPoint) = YearMonth.from(point.range.from).toString()
    private fun range(key: String, months: Long = 1): DateRange {
        val month = YearMonth.parse(key)
        val zone = ZoneId.of(ZONE)
        // Source-zone offsets changed in this 27-year history; derive both civil boundaries separately.
        return DateRange(month.atDay(1).atStartOfDay(zone).toOffsetDateTime(),
            month.plusMonths(months).atDay(1).atStartOfDay(zone).toOffsetDateTime())
    }
    private fun selection(key: String) = BlockAction(ActionType.SELECT_ANALYTICS_PERIOD, period = PeriodTypeCode.MONTH, date = PeriodKey(key))

    private class FakeApi(initial: Analytics) : AnalyticsApi {
        var reads = 0
        var writes = 0
        val cursors = mutableListOf<String>()
        val readRequests = mutableListOf<ReadRequest>()
        val dismissRequests = mutableListOf<DismissRequest>()
        var read: suspend (PeriodTypeCode?, PeriodKey?, TransferMode?) -> Analytics = { _, _, _ -> initial }
        var pageRead: suspend (String) -> AnalyticsChartPage = { error("unexpected page") }
        var mutate: suspend () -> AnalyticsBlockUpdate = { error("unexpected mutation") }
        override suspend fun getAnalytics(period: PeriodTypeCode?, date: PeriodKey?, transferMode: TransferMode?, supports: String?, exp: String?): Analytics {
            reads++
            readRequests += ReadRequest(period, date, transferMode)
            return read(period, date, transferMode)
        }
        override suspend fun listPeriods(period: PeriodTypeCode?) = AnalyticsPeriodsList(emptyList(), ZONE)
        override suspend fun getMonthlyChartPage(cursor: String): AnalyticsChartPage { cursors += cursor; return pageRead(cursor) }
        override suspend fun dismissRegularPayment(id: String, date: PeriodKey, transferMode: TransferMode): AnalyticsBlockUpdate {
            writes++
            dismissRequests += DismissRequest(id, date, transferMode)
            return mutate()
        }
        override suspend fun restoreRegularPayment(id: String) = Unit
    }

    private class FakeStatements : StatementsRepository {
        override val events = MutableSharedFlow<StatementsEvent>(extraBufferCapacity = 4)
        override suspend fun pendingAnalyticsImport(): Long? = null
        override suspend fun analyticsImportReady(revision: Long) = Unit
        override fun lastResult(uploadId: String): ImportResult? = null
        override suspend fun consumeFirstImportHints() = false
    }

    private object NoAssistantFlags : FeatureFlags {
        override val changes = MutableSharedFlow<Unit>()
        override fun isEnabled(flag: Flag) = flag != Flag.ASSISTANT && flag.defaultValue
        override fun variant(experiment: Experiment) = experiment.defaultVariant
        override val version = "pilot"
        override suspend fun setUser(userId: String?) = Unit
        override suspend fun refresh() = Unit
    }
    private object NoTracking : Tracker {
        override fun track(event: Event, params: Map<String, String>) = Unit
        override fun setUserId(userId: String?) = Unit
    }
    private object NoAssistant : AssistantApi {
        override suspend fun getLimit() = AssistantLimit(5, 5, java.time.OffsetDateTime.parse("2026-10-09T00:00:00+03:00"))
        override suspend fun listMessages(limit: Int?, beforeId: String?): MessagesList = error("unused")
        override suspend fun ask(key: IdempotencyKey, request: AskRequest): AskResponse = error("unused")
        override fun streamAnswer(answerId: String): Flow<AnswerEvent> = error("unused")
        override suspend fun retry(key: IdempotencyKey, answerId: String): RetryResponse = error("unused")
    }
    private companion object {
        const val ZONE = "Europe/Moscow"
        const val REFUSAL_MESSAGE = "Сервис получил новый тип ошибки. Попробуйте позже"
        const val UNAVAILABLE = "Не получилось посчитать категории"
    }
}
