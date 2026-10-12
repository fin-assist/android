package ru.finassist.pf.core.tracking

import kotlin.test.Test
import kotlin.test.assertEquals

class CrashContextSyncTest {
    private val calls = mutableListOf<String>()
    private val reporter = object : CrashReporter {
        override fun report(error: NonFatal) = Unit
        override fun breadcrumb(crumb: Breadcrumb) { calls += crumb.render() }
        override fun setUserId(userId: String?) = Unit
        override fun setKey(key: CrashKey, value: CrashValue?) { calls += "${key.name}=${value?.value}" }
    }
    private var version = "defaults"
    private val sync = CrashContextSync(reporter) { version }

    @Test fun `the first config sets the key and leaves a breadcrumb`() {
        sync.onConfigApplied()
        assertEquals(listOf("rc_ver=defaults", "config defaults"), calls)
    }

    @Test fun `an unchanged config is not reported twice`() {
        sync.onConfigApplied()
        sync.onConfigApplied()
        assertEquals(2, calls.size)
    }

    @Test fun `a new config replaces the key`() {
        sync.onConfigApplied()
        version = "0a1b2c3d"
        sync.onConfigApplied()
        assertEquals(listOf("rc_ver=defaults", "config defaults", "rc_ver=0a1b2c3d", "config 0a1b2c3d"), calls)
    }

    @Test fun `a version that is not a valid value clears the key and leaves no breadcrumb`() {
        version = "Config for +7 916 123-45-67"
        sync.onConfigApplied()
        assertEquals(listOf("rc_ver=null"), calls)
    }

    @Test fun `opened screens become breadcrumbs by route pattern`() {
        sync.onScreenOpened("ru.finassist.pf.feature.operations.api.OperationsRoutes.Detail/{id}")
        assertEquals(listOf("screen operations.detail"), calls)
    }

    @Test fun `the session the root composed on becomes a breadcrumb, an unknown one does not`() {
        sync.onSessionShown(null)
        sync.onSessionShown(Breadcrumb.SessionCrumb.SIGNED_IN)
        assertEquals(listOf("ui session signed_in"), calls)
    }

    @Test fun `a route without a name is skipped`() {
        sync.onScreenOpened(null)
        sync.onScreenOpened("home")
        assertEquals(emptyList(), calls)
    }
}

class CrashContextSyncFollowTest {
    private val keys = mutableListOf<String?>()
    private val reporter = object : CrashReporter {
        override fun report(error: NonFatal) = Unit
        override fun breadcrumb(crumb: Breadcrumb) = Unit
        override fun setUserId(userId: String?) = Unit
        override fun setKey(key: CrashKey, value: CrashValue?) { keys += value?.value }
    }

    @Test fun `the version in effect is set at once and every later one follows`() = kotlinx.coroutines.test.runTest {
        var version = "defaults"
        val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>()
        val job = CrashContextSync(reporter) { version }.follow(backgroundScope, changes)

        assertEquals(listOf<String?>("defaults"), keys)
        version = "0a1b2c3d"
        changes.emit(Unit)
        assertEquals(listOf<String?>("defaults", "0a1b2c3d"), keys)
        job.cancel()
    }

    @Test fun `an update right after start is not lost`() = kotlinx.coroutines.test.runTest {
        // A provider's `changes` does not replay. If the subscription were made after the initial read (in a
        // coroutine that has not started yet), this update would be emitted to nobody and rc_ver would stay stale.
        var version = "defaults"
        val changes = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val job = CrashContextSync(reporter) { version }.follow(backgroundScope, changes)

        version = "0a1b2c3d"
        changes.tryEmit(Unit)
        kotlinx.coroutines.yield()

        assertEquals(listOf<String?>("defaults", "0a1b2c3d"), keys)
        job.cancel()
    }
}
