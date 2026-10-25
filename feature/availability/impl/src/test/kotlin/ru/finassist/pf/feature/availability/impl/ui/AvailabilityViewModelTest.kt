package ru.finassist.pf.feature.availability.impl.ui

import ru.finassist.pf.core.common.time.ElapsedClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import ru.finassist.pf.core.api.AuthApi
import ru.finassist.pf.core.api.model.ConsentDocument
import ru.finassist.pf.core.api.model.ConsentType
import ru.finassist.pf.core.common.availability.ServiceGate
import ru.finassist.pf.core.common.error.AppError
import ru.finassist.pf.core.common.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Maintenance asks the server again once `Retry-After` passes and on «Проверить сейчас», never by polling (FIN-22). */
@OptIn(ExperimentalCoroutinesApi::class)
class AvailabilityViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val start = OffsetDateTime.parse("2026-10-15T12:00:00+03:00")
    private val clock = object : Clock {
        override fun now(): OffsetDateTime = start.plusNanos(scheduler.currentTime * 1_000_000)
        override val zone: ZoneId = Clock.MOSCOW
    }
    private val elapsedClock = ElapsedClock { scheduler.currentTime }
    private val gate = ServiceGate(elapsedClock)

    /** Each probe takes the next answer: `null` — success; an [AppError] — thrown (and reported like the network layer). */
    private val answers = ArrayDeque<AppError?>()
    private var probes = 0
    /** Runs before a successful answer: what happened on the server while the probe was on its way. */
    private var beforeSuccess: () -> Unit = {}
    private val auth = object : AuthApi by unused() {
        override suspend fun getConsentDocument(type: ConsentType): ConsentDocument {
            probes++
            when (val e = answers.removeFirstOrNull()) {
                null -> return beforeSuccess().let { ConsentDocument(type = ConsentType.PERSONAL_DATA, version = "1", url = "https://plainfin.ru", title = "Согласие") }
                is AppError.Maintenance -> { gate.maintenance(e.until, e.retryAfterSeconds, clock.now()); throw e }
                else -> throw e
            }
        }
    }

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(scheduler))

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `one automatic probe respects Retry-After and a fresh refusal needs a guarded manual check`() {
        val vm = AvailabilityViewModel(gate, auth, clock, elapsedClock)
        gate.maintenance(null, 60, clock.now())
        answers += AppError.Maintenance(null, 120)
        scheduler.advanceTimeBy(59_000); scheduler.runCurrent()
        assertEquals(0, probes, "not before Retry-After")
        scheduler.advanceTimeBy(1_000); scheduler.runCurrent()
        assertEquals(1, probes)
        assertIs<ServiceGate.State.Maintenance>(gate.state.value)

        answers += null
        scheduler.advanceTimeBy(119_000); scheduler.runCurrent()
        assertEquals(1, probes, "the new Retry-After counts")
        scheduler.advanceTimeBy(1_000); scheduler.runCurrent()
        assertEquals(1, probes, "a new refusal must not start an automatic loop")
        vm.checkNow(); scheduler.runCurrent()
        assertEquals(2, probes)
        assertEquals(ServiceGate.State.Open, gate.state.value)
        vm.hashCode()
    }

    @Test fun `a maintenance that began during the probe stays after its success`() {
        val vm = AvailabilityViewModel(gate, auth, clock, elapsedClock)
        gate.maintenance(null, 15, clock.now())
        answers += null
        beforeSuccess = { gate.maintenance(null, 600, clock.now()) }
        scheduler.advanceTimeBy(15_000); scheduler.runCurrent()
        assertEquals(1, probes)
        assertEquals(clock.now().plusSeconds(600), assertIs<ServiceGate.State.Maintenance>(gate.state.value).retryAt)
        vm.hashCode()
    }

    @Test fun `the check button cannot bypass Retry-After and network failure does not start a loop`() {
        val vm = AvailabilityViewModel(gate, auth, clock, elapsedClock)
        gate.maintenance(null, 600, clock.now())
        answers += AppError.Offline()
        vm.checkNow(); scheduler.runCurrent()
        assertEquals(0, probes)
        assertFalse(vm.canCheck.value)
        scheduler.advanceTimeBy(600_000); scheduler.runCurrent()
        assertEquals(1, probes)
        assertTrue(vm.canCheck.value, "a failed one-shot probe leaves the guarded manual action available")
        assertIs<ServiceGate.State.Maintenance>(gate.state.value)
        scheduler.advanceTimeBy(600_000); scheduler.runCurrent()
        assertEquals(1, probes)
    }

    @Test fun `manual readiness follows each refusal deadline without an automatic loop`() {
        val vm = AvailabilityViewModel(gate, auth, clock, elapsedClock)
        gate.maintenance(null, 15, clock.now())
        answers += AppError.Maintenance(null, 30)
        scheduler.runCurrent()
        assertFalse(vm.canCheck.value)
        scheduler.advanceTimeBy(15_000); scheduler.runCurrent()
        assertEquals(1, probes)
        assertFalse(vm.canCheck.value, "the fresh refusal has its own wait")
        scheduler.advanceTimeBy(29_999); scheduler.runCurrent()
        assertFalse(vm.canCheck.value)
        vm.checkNow(); scheduler.runCurrent()
        assertEquals(1, probes)
        scheduler.advanceTimeBy(1); scheduler.runCurrent()
        assertTrue(vm.canCheck.value)
        assertEquals(1, probes, "readiness does not start a second automatic probe")
        answers += null
        vm.checkNow(); scheduler.runCurrent()
        assertEquals(2, probes)
        assertEquals(ServiceGate.State.Open, gate.state.value)
        assertFalse(vm.canCheck.value)
    }

    @Test fun `the time of the end reads as a promise for today or another day`() {
        val now = OffsetDateTime.parse("2026-10-15T12:00:00+03:00")
        val msk = Clock.MOSCOW
        assertEquals("Закончим примерно к 14:30", untilText(OffsetDateTime.parse("2026-10-15T11:30:00Z"), now, msk))
        assertEquals("Закончим примерно 16\u00a0октября к 09:00", untilText(OffsetDateTime.parse("2026-10-16T09:00:00+03:00"), now, msk))
        assertEquals("Пока не знаем, когда закончим", untilText(null, now, msk))
        // In the zone, not the offset of «now»: New York in summer, the end after the autumn clock change (review of #81).
        val ny = ZoneId.of("America/New_York")
        val summer = OffsetDateTime.parse("2026-10-31T12:00:00-04:00")
        assertEquals("Закончим примерно 2\u00a0ноября к 09:00", untilText(OffsetDateTime.parse("2026-11-02T09:00:00-05:00"), summer, ny))
    }

    @Test fun `the button during a scheduled check sends one request, not two`() {
        val vm = AvailabilityViewModel(gate, auth, clock, elapsedClock)
        gate.maintenance(null, 15, clock.now())
        scheduler.runCurrent()
        answers += null
        scheduler.advanceTimeBy(14_999)
        vm.checkNow()
        scheduler.advanceTimeBy(5_000); scheduler.runCurrent()
        assertEquals(1, probes)
        assertEquals(ServiceGate.State.Open, gate.state.value)
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        inline fun <reified T> unused(): T = java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
            error("unused: ${m.name}")
        } as T
    }
}
