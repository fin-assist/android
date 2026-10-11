package ru.finassist.pf.core.tracking

import ru.finassist.pf.core.common.error.AppError
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SlugTest {
    @Test fun `identifiers are accepted`() {
        listOf("analytics", "regular_payments", "analytics.block.tiles", "server_500", "a").forEach {
            assertEquals(it, Slug.of(it).value)
        }
    }

    @Test fun `user data never passes`() {
        val rejected = listOf(
            "", "Пятёрочка", "Samokat", "coffee shop", "+79161234567", "79161234567", "1520", "1 520,50",
            "analytics.", ".analytics", "analytics..tiles", "analytics.9tiles", "a-b", "a".repeat(Slug.MAX_LENGTH + 1),
        )
        rejected.forEach { assertNull(Slug.orNull(it), "accepted: $it") }
    }

    @Test fun `prints as the bare value in logs`() {
        assertEquals("code=server_503", "code=${Slug.of("server_503")}")
        assertEquals("v=0a1b2c3d", "v=${CrashValue.of("0a1b2c3d")}")
    }

    @Test fun `the longest slug is allowed`() {
        assertNotNull(Slug.orNull("a".repeat(Slug.MAX_LENGTH)))
    }

    @Test fun `of fails without echoing the value`() {
        val error = assertFailsWith<IllegalArgumentException> { Slug.of("+79161234567") }
        assertEquals(false, error.message!!.contains("7916"))
    }
}

class CrashValueTest {
    @Test fun `hashes and variant names are accepted`() {
        listOf("0a1b2c3d", "defaults", "variant-b", "v1.2").forEach { assertEquals(it, CrashValue.of(it).value) }
    }

    @Test fun `free text and long values are rejected`() {
        listOf("", "Variant B", "вариант", "+7916", "a".repeat(CrashValue.MAX_LENGTH + 1)).forEach {
            assertNull(CrashValue.orNull(it), "accepted: $it")
        }
        assertFailsWith<IllegalArgumentException> { CrashValue.of("two words") }
    }

    @Test fun `the provider limit of 31 characters is the boundary`() {
        assertNotNull(CrashValue.orNull("a".repeat(31)))
        assertNull(CrashValue.orNull("a".repeat(32)))
    }
}

class CrashKeyTest {
    @Test fun `config version key`() = assertEquals("rc_ver", CrashKey.RC_VERSION.name)

    @Test fun `experiment keys are prefixed and flat`() {
        assertEquals("exp_new_onboarding", CrashKey.experiment(Slug.of("new_onboarding")).name)
        assertEquals("exp_analytics_tiles", CrashKey.experiment(Slug.of("analytics.tiles")).name)
    }
}

class NonFatalTest {
    @Test fun `issue key is screen dot part`() {
        val error = NonFatal(Slug.of("analytics"), Slug.of("regular_payments"), IllegalStateException("x"))
        assertEquals("analytics.regular_payments", error.issueKey)
    }

    @Test fun `a degradation is a warning unless said otherwise`() {
        assertEquals(Severity.WARNING, NonFatal(Slug.of("a"), Slug.of("b"), RuntimeException()).severity)
    }
}

class BreadcrumbTest {
    @Test fun `rendered lines`() {
        assertEquals("screen analytics.home", Breadcrumb.ScreenOpened(Slug.of("analytics.home")).render())
        assertEquals("config 0a1b2c3d", Breadcrumb.ConfigApplied(CrashValue.of("0a1b2c3d")).render())
    }
}

class ScreenSlugTest {
    @Test fun `route object`() =
        assertEquals("analytics.home", screenSlug("ru.finassist.pf.feature.analytics.api.AnalyticsRoutes.Home")?.value)

    @Test fun `arguments are cut off, path and query`() {
        assertEquals("analytics.period", screenSlug("ru.finassist.pf.feature.analytics.api.AnalyticsRoutes.Period/{params}")?.value)
        assertEquals("auth.phone", screenSlug("ru.finassist.pf.feature.auth.api.AuthRoutes.Phone?reason={reason}")?.value)
    }

    @Test fun `camel case becomes snake case`() =
        assertEquals("statements.unread_lines", screenSlug("ru.finassist.pf.feature.statements.api.StatementsRoutes.UnreadLines/{id}")?.value)

    @Test fun `a top level route class`() = assertEquals("category_picker", screenSlug("ru.finassist.pf.CategoryPicker")?.value)

    @Test fun `unnamed routes give nothing`() {
        assertNull(screenSlug(null))
        assertNull(screenSlug("home"))
        assertNull(screenSlug(""))
    }
}

class CrashCodeTest {
    @Test fun `app errors map to machine codes`() {
        assertEquals("offline", AppError.Offline().crashCode().value)
        assertEquals("invalid_token", AppError.Unauthorized("INVALID_TOKEN").crashCode().value)
        assertEquals("rate_limited", AppError.RateLimited(null).crashCode().value)
        assertEquals("request_in_progress", AppError.InProgress(3).crashCode().value)
        assertEquals("wrong_bank", AppError.Api("WRONG_BANK", 422).crashCode().value)
        assertEquals("server_503", AppError.Server(503).crashCode().value)
        assertEquals("unknown", AppError.Unknown(IOException("boom")).crashCode().value)
        assertEquals("unexpected", IllegalStateException("Пятёрочка 2 340 ₽").crashCode().value)
    }

    @Test fun `a code that is not an identifier falls back to the status`() {
        assertEquals("api_422", AppError.Api("bad code!", 422).crashCode().value)
        assertEquals("unauthorized", AppError.Unauthorized("").crashCode().value)
    }
}

class CompositeCrashReporterTest {
    private class Recording : CrashReporter {
        val calls = mutableListOf<String>()
        override fun report(error: NonFatal) { calls += "report ${error.issueKey}" }
        override fun breadcrumb(crumb: Breadcrumb) { calls += "crumb ${crumb.render()}" }
        override fun setUserId(userId: String?) { calls += "user $userId" }
        override fun setKey(key: CrashKey, value: CrashValue?) { calls += "key ${key.name}=${value?.value}" }
    }

    private object Broken : CrashReporter {
        override fun report(error: NonFatal) = error("sdk")
        override fun breadcrumb(crumb: Breadcrumb) = error("sdk")
        override fun setUserId(userId: String?) = error("sdk")
        override fun setKey(key: CrashKey, value: CrashValue?) = error("sdk")
    }

    @Test fun `every provider gets every call`() {
        val first = Recording()
        val second = Recording()
        val composite = CompositeCrashReporter(listOf(first, second))

        composite.setUserId("u1")
        composite.setKey(CrashKey.RC_VERSION, CrashValue.of("0a1b2c3d"))
        composite.breadcrumb(Breadcrumb.ScreenOpened(Slug.of("analytics.home")))
        composite.report(NonFatal(Slug.of("analytics"), Slug.of("tiles"), RuntimeException()))
        composite.setUserId(null)
        composite.setKey(CrashKey.RC_VERSION, null)

        val expected = listOf(
            "user u1", "key rc_ver=0a1b2c3d", "crumb screen analytics.home", "report analytics.tiles",
            "user null", "key rc_ver=null",
        )
        assertEquals(expected, first.calls)
        assertEquals(expected, second.calls)
    }

    @Test fun `a failing provider neither throws nor blocks the next one`() {
        val healthy = Recording()
        val composite = CompositeCrashReporter(listOf(Broken, healthy))

        composite.setUserId("u1")
        composite.setKey(CrashKey.RC_VERSION, null)
        composite.breadcrumb(Breadcrumb.ScreenOpened(Slug.of("a")))
        composite.report(NonFatal(Slug.of("a"), Slug.of("b"), RuntimeException()))

        assertEquals(4, healthy.calls.size)
    }

    @Test fun `no providers is fine`() {
        CompositeCrashReporter(emptyList()).setUserId("u1")
    }
}
