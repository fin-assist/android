package ru.finassist.pf.feature.assistant.impl.ui

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.api.AssistantApi
import ru.finassist.pf.core.api.model.AnswerEvent
import ru.finassist.pf.core.api.model.AskRequest
import ru.finassist.pf.core.api.model.AskResponse
import ru.finassist.pf.core.api.model.AssistantLimit
import ru.finassist.pf.core.api.model.ErrorCodes
import ru.finassist.pf.core.api.model.IdempotencyKey
import ru.finassist.pf.core.api.model.MessagesList
import ru.finassist.pf.core.api.model.RetryResponse
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.tracking.NoopTracker
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals

/** The view model's use of the app's [Clock] (FIN-71). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatViewModelTest {
    private val now = OffsetDateTime.parse("2026-10-08T12:00:00+03:00")
    private val clock = object : Clock {
        override fun now(): OffsetDateTime = this@ChatViewModelTest.now
        override val zone: ZoneId = Clock.MOSCOW
    }

    private class FakeApi : AssistantApi {
        var askFailure: AppError? = null
        override suspend fun listMessages(limit: Int?, beforeId: String?) = MessagesList(emptyList(), hasOlder = false)
        override suspend fun ask(key: IdempotencyKey, request: AskRequest): AskResponse = throw askFailure!!
        override fun streamAnswer(answerId: String): Flow<AnswerEvent> = error("unused")
        override suspend fun retry(key: IdempotencyKey, answerId: String): RetryResponse = error("unused")
        // No limit known yet: the 429 path builds one.
        override suspend fun getLimit(): AssistantLimit = throw AppError.Offline()
    }

    private val api = FakeApi()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `the limit hit without resets_at resets at the app's now, not the device's`() {
        api.askFailure = AppError.Api(ErrorCodes.LIMIT_EXCEEDED, 429)
        val vm = ChatViewModel(SavedStateHandle(), api, NoopTracker, clock)
        vm.setDraft("Сколько я трачу на такси?")
        vm.send()
        assertEquals(AssistantLimit(0, 5, now), vm.state.value.limit)
    }
}
