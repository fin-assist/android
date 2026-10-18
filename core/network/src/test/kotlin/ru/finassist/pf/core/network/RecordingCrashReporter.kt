package ru.finassist.pf.core.network

import ru.finassist.pf.core.tracking.Breadcrumb
import ru.finassist.pf.core.tracking.CrashKey
import ru.finassist.pf.core.tracking.CrashReporter
import ru.finassist.pf.core.tracking.CrashValue
import ru.finassist.pf.core.tracking.NonFatal
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps the non-fatals the network layer reports. */
internal class RecordingCrashReporter : CrashReporter {
    val reports = CopyOnWriteArrayList<NonFatal>()

    override fun report(error: NonFatal) {
        reports += error
    }

    override fun breadcrumb(crumb: Breadcrumb) = Unit
    override fun setUserId(userId: String?) = Unit
    override fun setKey(key: CrashKey, value: CrashValue?) = Unit
}
