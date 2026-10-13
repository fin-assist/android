package ru.finassist.pf.core.designsystem.theme

import androidx.compose.runtime.staticCompositionLocalOf
import ru.finassist.pf.core.common.time.Clock
import ru.finassist.pf.core.common.time.SystemClock

/**
 * The app's [Clock] for composables: «Сегодня», «Вчера», the year next to a date. Provided by `MainActivity` from
 * Hilt, so screens follow the same "now" as the view models and the mock backend (pinned in the e2e build, FIN-71).
 * Without a provider (previews, design-check snapshots) — the system clock; snapshots pass `today` explicitly.
 */
val LocalPfClock = staticCompositionLocalOf<Clock> { SystemClock() }
