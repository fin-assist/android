package ru.finassist.pf.feature.availability.impl.ui

import ru.finassist.pf.core.common.error.ErrorPresentation
import ru.finassist.pf.core.common.error.ErrorSeverity
import ru.finassist.pf.core.common.error.UserMessage
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.designsystem.theme.LocalPfClock
import ru.finassist.pf.core.screenshot.DesignCheck
import java.time.OffsetDateTime
import java.time.ZoneId

/** Artboards `ForceUpdate`, `Maintenance`, `MaintenanceNoTime` and the dark `ForceUpdateDark`, `MaintenanceDark` (FIN-22). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AvailabilityDesignCheckTest {
    private val now = OffsetDateTime.parse("2026-10-15T12:00:00+03:00")
    private val clock = object : Clock {
        override fun now() = now
        override val zone: ZoneId = Clock.MOSCOW
    }

    private fun maintenance(name: String, until: OffsetDateTime?, dark: Boolean = false) = DesignCheck.capture(name, dark = dark) {
        CompositionLocalProvider(LocalPfClock provides clock) {
            MaintenanceContent(until = until, checking = false, onCheck = {}, onSupport = {})
        }
    }

    @Test fun forceUpdate() = DesignCheck.capture("ForceUpdate") { UpdateContent(onUpdate = {}, onSupport = {}) }

    @Test fun maintenance() = maintenance("Maintenance", OffsetDateTime.parse("2026-10-15T14:30:00+03:00"))

    @Test fun maintenanceNoTime() = maintenance("MaintenanceNoTime", null)

    @Test fun forceUpdateDark() = DesignCheck.capture("ForceUpdateDark", dark = true) { UpdateContent(onUpdate = {}, onSupport = {}) }

    @Test fun maintenanceDark() = maintenance("MaintenanceDark", OffsetDateTime.parse("2026-10-15T14:30:00+03:00"), dark = true)
    // FIN-223 fixture: proposed artboard; snapshots are not executed under the current instruction.
    @Test fun serverWarning() = DesignCheck.capture("MaintenanceServerWarning") {
        MaintenanceContent(null, checking = false, onCheck = {}, onSupport = {}, presentation = ErrorPresentation(UserMessage.from("Сервис получил новый тип ошибки. Попробуйте позже"), ErrorSeverity.WARNING))
    }

}
