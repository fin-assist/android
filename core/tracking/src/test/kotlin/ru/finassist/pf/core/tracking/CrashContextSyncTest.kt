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

    @Test fun `a route without a name is skipped`() {
        sync.onScreenOpened(null)
        sync.onScreenOpened("home")
        assertEquals(emptyList(), calls)
    }
}
