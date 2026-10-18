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


class BuildDeclarationsTest(unittest.TestCase):
    def test_plugins_and_dependencies(self):
        text = (
            "plugins {\n    id(\"pf.android.library\")\n    alias(libs.plugins.kotlin.serialization)\n}\n"
            "dependencies {\n    implementation(projects.core.api)\n"
            "    implementation( libs.kotlinx.coroutines.android )\n"
            "    // implementation(libs.okhttp)\n"
            "    implementation(\"com.squareup.okio:okio:3.9.0\")\n"
            "    testImplementation(project(\":core:common\"))\n}\n"
        )
        self.assertEqual(kmp.build_declarations(text), [
            'id("pf.android.library")',
            "alias(libs.plugins.kotlin.serialization)",
            "implementation(projects.core.api)",
            "implementation(libs.kotlinx.coroutines.android)",
            'implementation("com.squareup.okio:okio:3.9.0")',
            'testImplementation(project(":core:common"))',
        ])


class ProblemsTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.write("core/api/build.gradle.kts", API_BUILD)
        self.write("mock/backend/build.gradle.kts", BACKEND_BUILD)
        self.write("core/api/src/main/kotlin/Auth.kt", "import java.time.Instant\nclass Auth(val at: Instant)\n")
        self.write("mock/backend/src/main/kotlin/Ledger.kt", "import java.time.LocalDate\n")
        self.baseline = {
            "core/api": {"jvm_apis": ["java.time.Instant"],
                         "build": ['id("pf.jvm.library")', "api(libs.kotlinx.serialization.json)"]},
            "mock/backend": {"jvm_apis": ["java.time.LocalDate"], "build": ['id("pf.android.library")']},
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

    def test_new_dependency_fails(self):
        self.write("core/api/build.gradle.kts", API_BUILD.replace(
            "api(libs.kotlinx.serialization.json)", "api(libs.kotlinx.serialization.json)\n    implementation(libs.okhttp)"))
        self.assertEqual(self.problems(), [
            "core/api/build.gradle.kts: new declaration implementation(libs.okhttp) (only multiplatform-ready "
            "dependencies; add it to scripts/kmp-baseline.json with a reason in the PR)",
        ])

    def test_new_plugin_fails(self):
        self.write("core/api/build.gradle.kts", API_BUILD.replace('id("pf.jvm.library")', 'id("pf.jvm.library")\n    id("pf.hilt")'))
        self.assertIn('new declaration id("pf.hilt")', self.problems()[0])

    def test_stale_baseline_entries_fail(self):
        self.write("mock/backend/src/main/kotlin/Ledger.kt", "import kotlinx.datetime.LocalDate\n")
        self.baseline["mock/backend"]["build"].append("implementation(libs.kotlinx.coroutines.android)")
        self.assertEqual(self.problems(), [
            "scripts/kmp-baseline.json: mock/backend no longer uses java.time.LocalDate; remove it from the baseline",
            "scripts/kmp-baseline.json: mock/backend no longer declares implementation(libs.kotlinx.coroutines.android); "
            "remove it from the baseline",
        ])

    def test_current_baseline_round_trips(self):
        self.write("mock/backend/src/main/kotlin/Ids.kt", "fun id() = java.util.UUID.randomUUID()\n")
        written = kmp.current_baseline(self.root)
        self.assertEqual(written["mock/backend"]["jvm_apis"], ["java.time.LocalDate", "java.util.UUID"])
        self.assertEqual(kmp.problems(self.root, written), [])


if __name__ == "__main__":
    unittest.main()
