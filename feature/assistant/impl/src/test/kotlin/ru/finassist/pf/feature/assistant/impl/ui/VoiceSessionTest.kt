package ru.finassist.pf.feature.assistant.impl.ui

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceSessionTest {
    @Test fun `preparation is not ready and only real readiness permits finishing`() {
        val session = VoiceSession()
        val id = session.begin()!!
        assertEquals(VoicePhase.PREPARING, session.state.value.phase)
        assertFalse(session.finish(id))
        assertNull(session.begin())
        assertTrue(session.ready(id))
        assertFalse(session.ready(id), "readiness and its haptic occur once")
        assertEquals(VoicePhase.LISTENING, session.state.value.phase)
        assertTrue(session.finish(id))
        assertFalse(session.finish(id))
        assertFalse(session.ready(id))
        assertEquals(VoicePhase.RECOGNIZING, session.state.value.phase)
        assertEquals("траты", session.result(id, " траты "))
        assertEquals(VoicePhase.IDLE, session.state.value.phase)
        assertNull(session.result(id, "duplicate"))
    }

    @Test fun `sound level is bounded finite and changes only while listening`() {
        val session = VoiceSession()
        val id = session.begin()!!
        session.level(id, 9f)
        assertEquals(0f, session.state.value.level)
        session.ready(id)
        for ((rms, level) in listOf(-10f to 0f, 4f to 0.5f, 100f to 1f, Float.NaN to 0f, Float.POSITIVE_INFINITY to 0f)) {
            session.level(id, rms)
            assertEquals(level, session.state.value.level)
        }
        session.finish(id)
        session.level(id, 100f)
        assertEquals(0f, session.state.value.level)
    }

    @Test fun `cancel rejects late permission readiness errors and results even after a new attempt`() {
        val session = VoiceSession()
        val old = session.begin()!!
        session.cancel()
        assertFalse(session.accepts(old))
        val current = session.begin()!!
        assertFalse(session.ready(old))
        session.fail(old, VoiceFailure.PERMISSION)
        assertNull(session.result(old, "must not enter draft"))
        assertEquals(VoicePhase.PREPARING, session.state.value.phase)
        assertTrue(session.ready(current))
        session.cancel()
        assertNull(session.result(current, "cancelled speech"))
        assertEquals(VoiceState(), session.state.value)
    }

    @Test fun `panel text distinguishes preparation readiness recognition and each failure`() {
        assertTrue(VoiceTexts.description(VoiceState(VoicePhase.PREPARING)).contains("черновике"))
        assertTrue(VoiceTexts.description(VoiceState(VoicePhase.ERROR, failure = VoiceFailure.PERMISSION)).contains("настройках"))
        assertTrue(VoiceTexts.description(VoiceState(VoicePhase.ERROR, failure = VoiceFailure.NO_SPEECH)).contains("сохранён"))
        assertEquals("", VoiceTexts.title(VoiceState()))
        assertEquals("Подготавливаем микрофон…", VoiceTexts.title(VoiceState(VoicePhase.PREPARING)))
        assertEquals("Говорите, я слушаю", VoiceTexts.title(VoiceState(VoicePhase.LISTENING)))
        assertEquals("Распознаём речь…", VoiceTexts.title(VoiceState(VoicePhase.RECOGNIZING)))
        assertEquals("Нужен доступ к микрофону", VoiceTexts.title(VoiceState(VoicePhase.ERROR, failure = VoiceFailure.PERMISSION)))
        assertEquals("Не удалось расслышать речь", VoiceTexts.title(VoiceState(VoicePhase.ERROR, failure = VoiceFailure.NO_SPEECH)))
        assertEquals("Распознавание речи недоступно", VoiceTexts.title(VoiceState(VoicePhase.ERROR, failure = VoiceFailure.UNAVAILABLE)))
        assertEquals("Не получилось распознать речь", VoiceTexts.title(VoiceState(VoicePhase.ERROR, failure = VoiceFailure.RECOGNITION)))
    }

    @Test fun `empty recognition and all failures preserve retryable idle input`() {
        for (text in listOf(null, "", "  ")) {
            val session = VoiceSession()
            assertNull(session.result(session.begin()!!, text))
            assertEquals(VoiceFailure.NO_SPEECH, session.state.value.failure)
            assertFalse(session.state.value.active)
        }
        for (failure in VoiceFailure.entries) {
            val session = VoiceSession()
            val id = session.begin()!!
            session.fail(id, failure)
            assertEquals(VoiceState(VoicePhase.ERROR, failure = failure), session.state.value)
            assertNull(session.result(id, "late result"))
            assertTrue(session.begin() != null)
        }
    }
}
