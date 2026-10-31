package ru.finassist.pf.feature.statements.impl.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.model.UnreadLine
import ru.finassist.pf.core.api.model.UnreadLinesList
import ru.finassist.pf.core.api.model.UnreadReason
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.feature.statements.impl.FakeStatementsApi
import ru.finassist.pf.feature.statements.impl.FakeTokens
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Robolectric: the route is read from [SavedStateHandle] through a `Bundle`. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnreadLinesViewModelTest {
    private val api = FakeStatementsApi()
    private val line = UnreadLine("u1", "u1.ofx", lineNumber = 42, reason = UnreadReason.BAD_AMOUNT, reasonName = "Нет суммы")

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun newVm(vararg route: Pair<String, Any?>) =
        UnreadLinesViewModel(SavedStateHandle(mapOf(*route)), api, FakeTokens("user-1"), ru.finassist.pf.core.common.time.ElapsedClock { 0L })

    @Test fun `the lines of one upload are loaded with the user id for the report`() {
        api.unreadResult = UnreadLinesList(listOf(line))
        val s = newVm("uploadId" to "u1").state.value
        assertEquals(listOf<Any>("u1"), api.unreadCalls)
        assertFalse(s.loading)
        assertFalse(s.error)
        assertEquals(listOf(line), s.lines)
        assertEquals("user-1", s.userId)
        assertTrue(s.othersLoaded)
    }

    @Test fun `the lines of a period are requested by its bounds`() {
        newVm("from" to "2026-09-01T00:00:00+03:00", "to" to "2026-10-01T00:00:00+03:00")
        assertEquals(
            listOf<Any>(OffsetDateTime.parse("2026-09-01T00:00:00+03:00") to OffsetDateTime.parse("2026-10-01T00:00:00+03:00")),
            api.unreadCalls,
        )
    }

    @Test fun `an upload that read nothing does not claim the rest was loaded`() {
        assertFalse(newVm("uploadId" to "u1", "othersLoaded" to false).state.value.othersLoaded)
    }

    @Test fun `a failed load shows the error and the retry loads again`() {
        api.unreadResult = AppError.Offline()
        val vm = newVm("uploadId" to "u1")
        assertTrue(vm.state.value.error)
        assertTrue(vm.state.value.errorPresentation?.connectionFailure == true)
        assertFalse(vm.state.value.loading)

        api.unreadResult = UnreadLinesList(listOf(line))
        vm.load()
        assertFalse(vm.state.value.error)
        assertEquals(listOf(line), vm.state.value.lines)
    }
    @Test fun `a failed unread-lines server response does not claim a local connection failure`() {
        api.unreadResult = AppError.Server(503)
        val state = newVm("uploadId" to "u1").state.value
        assertTrue(state.error)
        assertEquals(false, state.errorPresentation?.connectionFailure)
    }

    @Test fun `equal physical line and reason occurrences remain separate list entries`() {
        val duplicate = line.copy()
        api.unreadResult = UnreadLinesList(listOf(line, duplicate))
        val state = newVm("uploadId" to "u1").state.value
        assertFalse(state.loading)
        assertFalse(state.error)
        assertEquals(2, state.lines.size)
        assertEquals(listOf(line, duplicate), state.lines)
        assertEquals(state.lines[0].lineNumber, state.lines[1].lineNumber)
        assertEquals(state.lines[0].reason, state.lines[1].reason)
        assertEquals(state.lines[0].reasonName, state.lines[1].reasonName)
    }

}
