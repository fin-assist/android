package ru.finassist.pf.feature.profile.impl.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import ru.finassist.pf.core.api.ProfileApi
import ru.finassist.pf.core.api.model.AssistantConsentGrant
import ru.finassist.pf.core.api.model.Profile
import ru.finassist.pf.core.api.model.ProfileUpdate
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.tracking.Event
import ru.finassist.pf.core.tracking.Events
import ru.finassist.pf.core.tracking.Tracker
import ru.finassist.pf.feature.auth.api.AuthRoutes
import ru.finassist.pf.feature.auth.api.SessionRepository
import ru.finassist.pf.feature.auth.api.SessionState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAccountViewModelTest {
    private class FakeProfileApi : ProfileApi {
        var failure: AppError? = null
        var gate: CompletableDeferred<Unit>? = null
        var deletes = 0
        override suspend fun getProfile(): Profile = error("unused")
        override suspend fun updateProfile(update: ProfileUpdate): Profile = error("unused")
        override suspend fun deleteAccount() {
            deletes++
            gate?.await()
            failure?.let { throw it }
        }
        override suspend fun grantAssistantConsent(grant: AssistantConsentGrant): Profile = error("unused")
        override suspend fun revokeAssistantConsent() = Unit
    }

    private class FakeSession : SessionRepository {
        var clearedWith: String? = null
        override val state: Flow<SessionState> = emptyFlow()
        override suspend fun consumeJustRegistered() = false
        override suspend fun signOut(reason: String?) = Unit
        override suspend fun clearLocal(reason: String?) { clearedWith = reason }
        override fun consumeSignOutReason(): String? = null
    }

    private class FakeTracker : Tracker {
        val events = mutableListOf<Event>()
        override fun track(event: Event, params: Map<String, String>) { events += event }
        override fun setUserId(userId: String?) = Unit
    }

    private val api = FakeProfileApi()
    private val session = FakeSession()
    private val tracker = FakeTracker()

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm() = DeleteAccountViewModel(api, session, tracker, ru.finassist.pf.core.common.time.ElapsedClock { 0L })

    @Test fun `the passcode opens the dialog and «Отмена» closes it`() {
        val vm = vm()
        assertFalse(vm.state.value.dialog)
        vm.onPasscodeConfirmed()
        assertTrue(vm.state.value.dialog)
        vm.dismiss()
        assertEquals(DeleteUiState(), vm.state.value)
    }

    @Test fun `deleting ends the session with the deleted reason`() {
        val vm = vm()
        vm.onPasscodeConfirmed()
        vm.delete()
        assertEquals(1, api.deletes)
        assertEquals(AuthRoutes.Phone.REASON_DELETED, session.clearedWith)
        assertEquals(listOf(Events.PROFILE_ACCOUNT_DELETED), tracker.events)
    }

    @Test fun `while deleting the dialog stays and a second tap does nothing`() {
        api.gate = CompletableDeferred()
        val vm = vm()
        vm.onPasscodeConfirmed()
        vm.delete()
        assertTrue(vm.state.value.deleting)
        vm.dismiss()
        assertTrue(vm.state.value.dialog)
        vm.delete()
        assertEquals(1, api.deletes)
        api.gate!!.complete(Unit)
        assertEquals(AuthRoutes.Phone.REASON_DELETED, session.clearedWith)
    }

    @Test fun `no network keeps the dialog with the error`() {
        api.failure = AppError.Offline()
        val vm = vm()
        vm.onPasscodeConfirmed()
        vm.delete()
        assertTrue(vm.state.value.dialog)
        assertFalse(vm.state.value.deleting)
        assertEquals("Нет сети — удалить аккаунт не получилось. Проверьте интернет и повторите", vm.state.value.error)
        assertNull(session.clearedWith)
    }

    @Test fun `another failure says so and a retry clears the error`() {
        api.failure = AppError.Server(500)
        val vm = vm()
        vm.onPasscodeConfirmed()
        vm.delete()
        assertEquals("Не получилось удалить аккаунт. Попробуйте ещё раз", vm.state.value.error)
        api.failure = null
        vm.delete()
        assertEquals(AuthRoutes.Phone.REASON_DELETED, session.clearedWith)
    }
}
