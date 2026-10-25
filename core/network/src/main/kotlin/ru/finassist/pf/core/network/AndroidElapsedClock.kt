package ru.finassist.pf.core.network

import ru.finassist.pf.core.common.time.ElapsedClock

/** Boot-based monotonic clock includes deep sleep; elapsed anchors are never persisted across boots. */
object AndroidElapsedClock : ElapsedClock {
    override fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()
}
