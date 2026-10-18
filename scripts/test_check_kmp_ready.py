"""Tests for scripts/check-kmp-ready.py: python3 -m unittest discover -s scripts -p 'test_*.py'"""

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("check-kmp-ready.py")
_spec = importlib.util.spec_from_file_location("check_kmp_ready", SCRIPT)
kmp = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(kmp)

API_BUILD = 'plugins {\n    id("pf.jvm.library")\n}\ndependencies {\n    api(libs.kotlinx.serialization.json)\n}\n'
BACKEND_BUILD = 'plugins {\n    id("pf.android.library")\n}\n'


class JvmReferencesTest(unittest.TestCase):
    def test_imports_and_qualified_names_by_class(self):
        text = (
            "package x\n"
            "import java.time.LocalDate\n"
            "import java.time.temporal.ChronoUnit as Unit\n"
            "import kotlinx.coroutines.flow.Flow\n"
            "fun f() = java.time.Duration.between(a, b).seconds + java.math.RoundingMode.HALF_UP.ordinal\n"
        )
        self.assertEqual(kmp.jvm_references(text), [
            (2, "java.time.LocalDate"),
            (3, "java.time.temporal.ChronoUnit"),
            (5, "java.time.Duration"),
            (5, "java.math.RoundingMode"),
        ])

    def test_star_import_and_every_jvm_root(self):
        text = "import java.time.*\nimport javax.inject.Singleton\nimport android.util.Log\n" \
               "import androidx.annotation.Keep\nimport dagger.Module\n"
        self.assertEqual([name for _, name in kmp.jvm_references(text)], [
            "java.time.*", "javax.inject.Singleton", "android.util.Log", "androidx.annotation.Keep", "dagger.Module",
        ])

    def test_comments_strings_and_member_access_are_not_references(self):
        text = (
            "/** Replaces java.time.LocalDate, see [java.util.UUID]. */\n"
            "// java.io.File\n"
            'val s = "java.io.File"\n'
            "val t = \"\"\"\njava.net.URL\n\"\"\"\n"
            "val u = config.android.version\n"
            "val androidName = 1\n"
        )
        self.assertEqual(kmp.jvm_references(text), [])

    def test_multiplatform_imports_are_not_references(self):
        text = "import kotlin.uuid.Uuid\nimport kotlinx.datetime.LocalDate\nimport ru.finassist.pf.core.api.PfApi\n"
        self.assertEqual(kmp.jvm_references(text), [])


    def test_string_templates_are_code(self):
        text = (
            'val id = "op-${java.util.UUID.randomUUID()}"\n'
            'val t = "${java.io.File("x").readText()} and ${"java.net.URL"}"\n'
            'val r = """\n${java.time.LocalDate.now()}\n"""\n'
            'val n = "${listOf(1).map { "${java.time.Instant.now()}" }}"\n'
            "val after = java.math.BigDecimal.ONE\n"
        )
        self.assertEqual(kmp.jvm_references(text), [
            (1, "java.util.UUID"),
            (2, "java.io.File"),
            (4, "java.time.LocalDate"),
            (6, "java.time.Instant"),
            (7, "java.math.BigDecimal"),
        ])

    def test_string_text_and_simple_templates_are_not_code(self):
        text = (
            'val a = "java.io.File $java.io.File \\"${\'$\'}{java.io.File}"\n'
            'val b = """java.time.LocalDate""""\n'
            "val c = '\"'\n"
            "val d = 1\n"
        )
        self.assertEqual(kmp.jvm_references(text), [])

    def test_nested_block_comments(self):
        text = (
            "/* outer /* inner */ java.io.File */\n"
            "val x = 1 /* a /* b /* c */ */ java.net.URL */\n"
            "import java.util.UUID\n"
        )
        self.assertEqual(kmp.jvm_references(text), [(3, "java.util.UUID")])

    def test_jdk_packages_outside_java(self):
        text = (
            "import org.w3c.dom.Document\n"
            "import org.xml.sax.XMLReader\n"
            "import org.ietf.jgss.GSSManager\n"
            "import sun.misc.Unsafe\n"
            "import jdk.internal.misc.VM\n"
            "fun f(): Any = com.sun.net.httpserver.HttpServer.create() ?: org.omg.CORBA.ORB.init()\n"
            "import org.jetbrains.annotations.NotNull\n"
        )
        self.assertEqual([name for _, name in kmp.jvm_references(text)], [
            "org.w3c.dom.Document", "org.xml.sax.XMLReader", "org.ietf.jgss.GSSManager", "sun.misc.Unsafe",
            "jdk.internal.misc.VM", "com.sun.net.httpserver.HttpServer", "org.omg.CORBA",
        ])

    def test_jvm_only_kotlin_stdlib_packages(self):
        text = (
            "import kotlin.io.path.readText\n"
            "import kotlin.streams.asSequence\n"
            "import kotlin.reflect.full.memberProperties\n"
            "import kotlin.reflect.jvm.javaField\n"
            "import kotlin.jvm.optionals.getOrNull\n"
            'val p = kotlin.io.path.Path("x")\n'
            "import kotlin.io.encoding.Base64\n"
            "import kotlin.reflect.KClass\n"
            "import kotlin.jvm.JvmInline\n"
        )
        self.assertEqual(kmp.jvm_references(text), [
            (1, "kotlin.io.path.readText"),
            (2, "kotlin.streams.asSequence"),
            (3, "kotlin.reflect.full.memberProperties"),
            (4, "kotlin.reflect.jvm.javaField"),
            (5, "kotlin.jvm.optionals.getOrNull"),
            (6, "kotlin.io.path.Path"),
        ])

    def test_java_lang_without_import(self):
        text = (
            "val a = Math.floorDiv(7, 2)\n"
            'val b = System.currentTimeMillis() + Integer.parseInt("1")\n'
            "val c = Thread { }.also { Thread.sleep(1) }\n"
            'val d = Class.forName("x") to Runtime.getRuntime()\n'
            "val e = Character.isDigit('1') || StrictMath.abs(1) > 0\n"
            'val f = ThreadLocal<Int>() to ProcessBuilder("ls") to ClassLoader.getSystemClassLoader()\n'
        )
        self.assertEqual(kmp.jvm_references(text), [
            (1, "java.lang.Math"),
            (2, "java.lang.System"),
            (2, "java.lang.Integer"),
            (3, "java.lang.Thread"),
            (3, "java.lang.Thread"),
            (4, "java.lang.Class"),
            (4, "java.lang.Runtime"),
            (5, "java.lang.Character"),
            (5, "java.lang.StrictMath"),
            (6, "java.lang.ThreadLocal"),
            (6, "java.lang.ProcessBuilder"),
            (6, "java.lang.ClassLoader"),
        ])

    def test_java_lang_in_type_positions_and_references(self):
        text = (
            "fun start(worker: Thread) = worker.start()\n"
            "fun empty(): Thread? = null\n"
            "val k = Thread::class\n"
            "val now: () -> Long = System::currentTimeMillis\n"
            "class Job : Runnable { override fun run() = Unit }\n"
            "fun f() = try { 1 } catch (e: InterruptedException) { 0 }\n"
            "val b: StringBuffer? = null; val o: Object? = null; val p: Process? = null\n"
        )
        self.assertEqual(kmp.jvm_references(text), [
            (1, "java.lang.Thread"),
            (2, "java.lang.Thread"),
            (3, "java.lang.Thread"),
            (4, "java.lang.System"),
            (5, "java.lang.Runnable"),
            (6, "java.lang.InterruptedException"),
            (7, "java.lang.StringBuffer"),
            (7, "java.lang.Object"),
            (7, "java.lang.Process"),
        ])

    def test_java_lang_names_with_a_common_typealias_are_not_references(self):
        text = (
            "class E : RuntimeException()\n"
            "fun f(e: Exception, b: StringBuilder, c: Comparable<Int>, a: AutoCloseable) = Unit\n"
            "val j = buildJsonObject { }\n"
        )
        self.assertEqual(kmp.jvm_references(text), [])

    def test_backticks_do_not_hide_a_reference(self):
        text = "import java.io.`File`\nval f = java.`io`.File(\"x\")\nval t = `Thread`.currentThread()\n"
        self.assertEqual(kmp.jvm_references(text), [
            (1, "java.io.File"),
            (2, "java.io.File"),
            (3, "java.lang.Thread"),
        ])

    def test_jvm_only_members_of_kotlin_concurrent_and_coroutines(self):
        text = (
            "import kotlin.concurrent.thread\n"
            "import kotlin.concurrent.withLock\n"
            "import kotlinx.coroutines.future.future\n"
            "import kotlinx.coroutines.time.delay\n"
            "import kotlinx.coroutines.stream.consumeAsFlow\n"
            "import kotlinx.coroutines.android.HandlerDispatcher\n"
            "import kotlin.concurrent.Volatile\n"
            "import kotlin.concurrent.atomics.AtomicInt\n"
            "import kotlin.concurrent.threadLocal\n"
            "import kotlinx.coroutines.flow.Flow\n"
            "import kotlinx.coroutines.Dispatchers\n"
        )
        self.assertEqual([name for _, name in kmp.jvm_references(text)], [
            "kotlin.concurrent.thread", "kotlin.concurrent.withLock", "kotlinx.coroutines.future.future",
            "kotlinx.coroutines.time.delay", "kotlinx.coroutines.stream.consumeAsFlow",
            "kotlinx.coroutines.android.HandlerDispatcher",
        ])

    def test_java_lang_names_that_are_not_java_lang(self):
        text = (
            "import ru.finassist.pf.core.common.Math\n"
            "import ru.finassist.pf.time.Clock as System\n"
            "object Thread { fun sleep() = Unit }\n"
            "val a = Math.round(1) + System.now() + Thread.sleep()\n"
            "val b = SystemCategories.UNCATEGORIZED + config.Math.x + kotlin.math.floor(1.0) + MathUtils.x\n"
            "val c = Int.MAX_VALUE + Char.MIN_VALUE.code + 7.floorDiv(2)\n"
            "// Math.floorDiv\n"
            'val d = "System.exit"\n'
        )
        self.assertEqual(kmp.jvm_references(text), [])

    def test_local_names_like_packages_are_not_references(self):
        text = (
            "val android = cfg.android\n"
            "val v = android.version + java.size + dagger.name\n"
            "val androidName = android.sdk.level\n"
        )
        self.assertEqual(kmp.jvm_references(text), [])


class BuildLinesTest(unittest.TestCase):
    def test_normalized_without_comments_and_blank_lines(self):
        text = (
            "// In-process fake backend.\n"
            'plugins {\n    id("pf.android.library")   // the base\n\n'
            '    /* nested /* comment */ id("pf.hilt") */\n}\n'
            "dependencies {\n    implementation( libs.kotlinx.coroutines.android )\n"
            '    implementation("com.squareup.okio:okio:3.9.0") // not "a string"\n}\n'
        )
        self.assertEqual(kmp.build_lines(text), [
            "plugins {",
            'id("pf.android.library")',
            "}",
            "dependencies {",
            "implementation( libs.kotlinx.coroutines.android )",
            'implementation("com.squareup.okio:okio:3.9.0")',
            "}",
        ])

    def test_comment_and_whitespace_changes_keep_the_lines(self):
        reformatted = "// the contract\n" + API_BUILD.replace("    ", "\t").replace("}\n", "}\n\n")
        self.assertEqual(kmp.build_lines(reformatted), kmp.build_lines(API_BUILD))


class ProblemsTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.write("core/api/build.gradle.kts", API_BUILD)
        self.write("mock/backend/build.gradle.kts", BACKEND_BUILD)
        self.write("core/api/src/main/kotlin/Auth.kt", "import java.time.Instant\nclass Auth(val at: Instant)\n")
        self.write("mock/backend/src/main/kotlin/Ledger.kt", "import java.time.LocalDate\n")
        self.baseline = {
            "core/api": {"jvm_apis": ["java.time.Instant"], "build": kmp.build_lines(API_BUILD)},
            "mock/backend": {"jvm_apis": ["java.time.LocalDate"], "build": kmp.build_lines(BACKEND_BUILD)},
        }

    def tearDown(self):
        self._tmp.cleanup()

    def write(self, path: str, text: str):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text, encoding="utf-8")

    def problems(self):
        return kmp.problems(self.root, self.baseline)

    def test_baseline_as_is_passes(self):
        self.assertEqual(self.problems(), [])

    def test_new_forbidden_import_fails_with_its_place(self):
        self.write("mock/backend/src/main/kotlin/Files.kt", "package x\nimport java.io.File\n")
        self.assertEqual(self.problems(), [
            "mock/backend/src/main/kotlin/Files.kt:2: new JVM-only API java.io.File in mock/backend "
            "(use a multiplatform one, docs/kmp-readiness.md)",
        ])

    def test_new_qualified_name_without_import_fails(self):
        self.write("core/api/src/main/kotlin/Ids.kt", "fun id() = java.util.UUID.randomUUID().toString()\n")
        self.assertEqual(len(self.problems()), 1)
        self.assertIn("core/api/src/main/kotlin/Ids.kt:1: new JVM-only API java.util.UUID", self.problems()[0])

    def test_baseline_is_per_module(self):
        # LocalDate is allowed in mock/backend only; core/api gets no exception from it.
        self.write("core/api/src/main/kotlin/Day.kt", "import java.time.LocalDate\n")
        self.assertEqual(len(self.problems()), 1)
        self.assertIn("java.time.LocalDate in core/api", self.problems()[0])

    def test_listed_api_in_another_file_of_the_module_passes(self):
        self.write("mock/backend/src/main/kotlin/Scope.kt", "import java.time.LocalDate\n")
        self.assertEqual(self.problems(), [])

    def test_other_production_source_sets_are_checked(self):
        self.write("mock/backend/src/debug/kotlin/Debug.kt", "import android.util.Log\n")
        self.assertIn("new JVM-only API android.util.Log", self.problems()[0])

    def test_test_sources_are_not_checked(self):
        self.write("mock/backend/src/test/kotlin/LedgerTest.kt", "import java.io.File\nimport org.junit.Test\n")
        self.write("mock/backend/src/androidTest/kotlin/UiTest.kt", "import androidx.test.core.app.ActivityScenario\n")
        self.write("core/api/src/testFixtures/kotlin/Fixture.kt", "import java.nio.file.Files\n")
        self.assertEqual(self.problems(), [])

    def add_dependency(self, line: str):
        self.write("core/api/build.gradle.kts", API_BUILD.replace(
            "api(libs.kotlinx.serialization.json)", "api(libs.kotlinx.serialization.json)\n    " + line))

    def test_new_dependency_fails(self):
        self.add_dependency("implementation(libs.okhttp)")
        self.assertEqual(self.problems(), [
            "core/api/build.gradle.kts differs from scripts/kmp-baseline.json (only multiplatform-ready plugins and "
            "dependencies; give the reason in the PR and run scripts/check-kmp-ready.py --write):\n"
            "  + implementation(libs.okhttp)",
        ])

    def test_dependency_in_any_form_fails(self):
        for line in (
            'add("implementation", "com.google.code.gson:gson:2.11.0")',
            '"implementation"(libs.okhttp)',
            'apply(plugin = "com.google.dagger.hilt.android")',
            "val net = libs.okhttp; implementation(net)",
            'configurations["implementation"].dependencies.add(project.dependencies.create(libs.okhttp.get()))',
        ):
            with self.subTest(line=line):
                self.add_dependency(line)
                problems = self.problems()
                self.assertEqual(len(problems), 1)
                self.assertIn("core/api/build.gradle.kts differs", problems[0])
                self.assertIn("+ " + line, problems[0])

    def test_new_plugin_fails(self):
        self.write("core/api/build.gradle.kts", API_BUILD.replace('id("pf.jvm.library")', 'id("pf.jvm.library")\n    id("pf.hilt")'))
        self.assertIn('+ id("pf.hilt")', self.problems()[0])

    def test_removed_dependency_fails_until_the_baseline_is_rewritten(self):
        self.write("core/api/build.gradle.kts", API_BUILD.replace("    api(libs.kotlinx.serialization.json)\n", ""))
        self.assertIn("- api(libs.kotlinx.serialization.json)", self.problems()[0])

    def test_comment_in_build_file_passes(self):
        self.write("core/api/build.gradle.kts", "// Client API contract.\n" + API_BUILD.replace(
            "dependencies {", "dependencies { // nothing JVM-only\n    // implementation(libs.okhttp)"))
        self.assertEqual(self.problems(), [])

    def test_new_jvm_api_inside_a_string_template_fails(self):
        self.write("core/api/src/main/kotlin/Probe.kt", 'fun probe() = "${java.io.File("/tmp/a").exists()}"\n')
        self.assertIn("Probe.kt:1: new JVM-only API java.io.File in core/api", self.problems()[0])

    def test_new_java_lang_type_in_the_contract_fails(self):
        self.write("core/api/src/main/kotlin/Probe.kt", "fun start(worker: Thread) = worker.start()\n")
        self.assertIn("Probe.kt:1: new JVM-only API java.lang.Thread in core/api", self.problems()[0])

    def test_new_java_lang_api_without_import_fails(self):
        self.write("mock/backend/src/main/kotlin/Engine.kt", "fun typical(sum: Long) = Math.floorDiv(sum, 3)\n")
        self.assertIn("Engine.kt:1: new JVM-only API java.lang.Math in mock/backend", self.problems()[0])

    def test_stale_baseline_entries_fail(self):
        self.write("mock/backend/src/main/kotlin/Ledger.kt", "import kotlinx.datetime.LocalDate\n")
        self.assertEqual(self.problems(), [
            "scripts/kmp-baseline.json: mock/backend no longer uses java.time.LocalDate; remove it from the baseline",
        ])

    def test_current_baseline_round_trips(self):
        self.write("mock/backend/src/main/kotlin/Ids.kt", "fun id() = java.util.UUID.randomUUID()\n")
        written = kmp.current_baseline(self.root)
        self.assertEqual(written["mock/backend"]["jvm_apis"], ["java.time.LocalDate", "java.util.UUID"])
        self.assertEqual(kmp.problems(self.root, written), [])


if __name__ == "__main__":
    unittest.main()
