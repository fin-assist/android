"""Tests of scripts/coverage-diff.py. Run: python3 -m unittest discover -s scripts -p 'test_*.py'"""

import importlib.util
import io
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location("coverage_diff", ROOT / "scripts" / "coverage-diff.py")
cov = importlib.util.module_from_spec(_spec)
sys.modules["coverage_diff"] = cov  # dataclasses resolve the module by name
_spec.loader.exec_module(cov)

DIFF = """diff --git a/feature/x/impl/src/main/kotlin/ru/pf/x/Vm.kt b/feature/x/impl/src/main/kotlin/ru/pf/x/Vm.kt
--- a/feature/x/impl/src/main/kotlin/ru/pf/x/Vm.kt
+++ b/feature/x/impl/src/main/kotlin/ru/pf/x/Vm.kt
@@ -10,0 +11,3 @@ class Vm {
+    fun a() = 1
+    // comment
+    fun b() = 2
@@ -20 +23 @@ class Vm {
-    old
+    fun c() = 3
@@ -30,2 +33,0 @@ class Vm {
-    removed
-    removed
diff --git a/feature/x/impl/src/test/kotlin/ru/pf/x/VmTest.kt b/feature/x/impl/src/test/kotlin/ru/pf/x/VmTest.kt
--- a/feature/x/impl/src/test/kotlin/ru/pf/x/VmTest.kt
+++ b/feature/x/impl/src/test/kotlin/ru/pf/x/VmTest.kt
@@ -1,0 +1 @@
+test
diff --git a/core/a/src/main/kotlin/ru/pf/a/Gone.kt b/core/a/src/main/kotlin/ru/pf/a/Gone.kt
--- a/core/a/src/main/kotlin/ru/pf/a/Gone.kt
+++ /dev/null
@@ -1 +0,0 @@
-gone
"""

REPORT = """<?xml version="1.0" ?>
<report name="Kover">
  <package name="ru/pf/x">
    <class name="ru/pf/x/Vm" sourcefilename="Vm.kt"/>
    <sourcefile name="Vm.kt">
      <line nr="11" mi="0" ci="2" mb="0" cb="0"/>
      <line nr="13" mi="3" ci="0" mb="0" cb="0"/>
      <line nr="23" mi="1" ci="1" mb="1" cb="1"/>
      <line nr="40" mi="5" ci="0" mb="0" cb="0"/>
    </sourcefile>
  </package>
  <counter type="LINE" missed="30" covered="70"/>
  <counter type="BRANCH" missed="5" covered="5"/>
</report>"""


class ParseTest(unittest.TestCase):
    def test_source_key_strips_module_and_source_set(self):
        self.assertEqual(cov.source_key("feature/x/impl/src/main/kotlin/ru/pf/x/Vm.kt"), "ru/pf/x/Vm.kt")
        self.assertEqual(cov.source_key("app/src/mock/kotlin/ru/pf/M.kt"), "ru/pf/M.kt")
        self.assertEqual(cov.source_key("app/src/mockE2e/java/ru/pf/E.java"), "ru/pf/E.java")

    def test_source_key_skips_tests_and_non_code(self):
        self.assertIsNone(cov.source_key("feature/x/impl/src/test/kotlin/ru/pf/x/VmTest.kt"))
        self.assertIsNone(cov.source_key("feature/x/impl/src/testMock/kotlin/ru/pf/x/VmTest.kt"))
        self.assertIsNone(cov.source_key("app/src/androidTestMock/kotlin/ru/pf/PickerTest.kt"))
        self.assertIsNone(cov.source_key("build-logic/convention/src/main/kotlin/Coverage.txt"))

    def test_diff_collects_added_lines_and_skips_pure_deletions(self):
        changed = cov.parse_diff(DIFF)
        self.assertEqual(changed["feature/x/impl/src/main/kotlin/ru/pf/x/Vm.kt"], {11, 12, 13, 23})
        self.assertIn("feature/x/impl/src/test/kotlin/ru/pf/x/VmTest.kt", changed)
        self.assertNotIn("core/a/src/main/kotlin/ru/pf/a/Gone.kt", changed)

    def test_diff_unquotes_paths_with_special_characters(self):
        diff = ('+++ "b/app/src/main/kotlin/ru/pf/Caf\\303\\251.kt"\n@@ -0,0 +1,2 @@\n+a\n+b\n'
                "+++ b/app/src/main/kotlin/ru/pf/With Space.kt\t\n@@ -1 +1 @@\n+c\n")
        changed = cov.parse_diff(diff)
        self.assertEqual(changed["app/src/main/kotlin/ru/pf/Café.kt"], {1, 2})
        self.assertEqual(changed["app/src/main/kotlin/ru/pf/With Space.kt"], {1})

    def test_unquote_mixed_escapes_and_non_ascii(self):
        self.assertEqual(cov.unquote('"b/x/Caf\\303\\251.kt"'), "b/x/Café.kt")
        self.assertEqual(cov.unquote('"b/x/Café \\"q\\".kt"'), 'b/x/Café "q".kt')
        self.assertEqual(cov.unquote('"b/x/a\\\\b\\tc.kt"'), "b/x/a\\b\tc.kt")
        self.assertEqual(cov.unquote("b/plain.kt"), "b/plain.kt")
        self.assertEqual(cov.unquote('"b/x/\\377.kt"'), "b/x/\ufffd.kt")

    def test_report_counts_partly_covered_line_as_covered(self):
        lines = cov.parse_report(REPORT)["ru/pf/x/Vm.kt"]
        self.assertEqual(lines, {11: True, 13: False, 23: True, 40: False})


class ComputeTest(unittest.TestCase):
    def test_only_executable_changed_lines_of_production_code_count(self):
        files = cov.diff_coverage(cov.parse_diff(DIFF), cov.parse_report(REPORT))
        self.assertEqual(len(files), 1)
        self.assertEqual((files[0].covered, files[0].missed), ([11, 23], [13]))

    def test_file_missing_from_report_is_ignored(self):
        changed = {"app/src/main/kotlin/ru/pf/Unknown.kt": {1, 2}}
        self.assertEqual(cov.diff_coverage(changed, cov.parse_report(REPORT)), [])

    def test_ranges(self):
        self.assertEqual(cov.ranges([3, 4, 5, 9, 11, 12]), "3–5, 9, 11–12")
        self.assertEqual(cov.ranges([]), "")

    def test_render(self):
        files = cov.diff_coverage(cov.parse_diff(DIFF), cov.parse_report(REPORT))
        text = cov.render(files, cov.report_totals(REPORT), "origin/main")
        self.assertIn("lines 70.0 % (70/100), branches 50.0 %", text)
        self.assertIn("**66.7 %** (2/3)", text)
        self.assertIn("| `feature/x/impl/src/main/kotlin/ru/pf/x/Vm.kt` | 2/3 | 13 |", text)

    def test_render_totals_only_without_base(self):
        text = cov.render([], cov.report_totals(REPORT), None)
        self.assertIn("lines 70.0 %", text)
        self.assertNotIn("Changed lines", text)

    def test_render_puts_uncovered_files_first_and_truncates(self):
        files = [cov.FileCoverage(f"m/src/main/kotlin/F{i}.kt", covered=[1], missed=[2] if i % 2 else [])
                 for i in range(6)]
        text = cov.render(files, cov.report_totals(REPORT), "b", max_files=3)
        rows = [l for l in text.splitlines() if l.startswith("| `")]
        self.assertEqual([r.split("`")[1] for r in rows],
                         ["m/src/main/kotlin/F1.kt", "m/src/main/kotlin/F3.kt", "m/src/main/kotlin/F5.kt"])
        self.assertIn("…and 3 more files (0 uncovered lines): the full table is in the CI run summary.", text)

    def test_render_without_changes(self):
        self.assertIn("no executable production lines changed", cov.render([], cov.report_totals(REPORT), "b"))


class LimitsTest(unittest.TestCase):
    def test_code_span_cannot_be_closed_by_a_file_name(self):
        self.assertEqual(cov.code("a`@b.kt"), "`a'@b.kt`")

    def test_cell_is_cut_at_a_separator(self):
        text = cov.ranges(list(range(1, 400, 2)))
        cut = cov.cell(text, 30)
        self.assertLessEqual(len(cut), 33)
        self.assertTrue(cut.endswith(", …"))
        self.assertEqual(cov.cell("1–3", 30), "1–3")

    def test_render_caps_the_cell_and_the_whole_text(self):
        files = [cov.FileCoverage(f"m/src/main/kotlin/{'P' * 200}{i}.kt", missed=list(range(1, 2000, 2)))
                 for i in range(50)]
        text = cov.render(files, cov.report_totals(REPORT), "b")
        self.assertLess(len(text), 65_536)
        self.assertTrue(all(len(line) < 500 for line in text.splitlines()))
        text = cov.render(files, cov.report_totals(REPORT), "b", max_text=2_000)
        self.assertLessEqual(len(text), 2_100)
        self.assertIn("…cut: the full table is in the CI run summary.", text)

    def test_render_full_has_every_row_and_no_cuts(self):
        files = [cov.FileCoverage(f"m/src/main/kotlin/F{i}.kt", missed=list(range(1, 400, 2))) for i in range(60)]
        text = cov.render(files, cov.report_totals(REPORT), "b", None, None, None)
        self.assertEqual(len([l for l in text.splitlines() if l.startswith("| `")]), 60)
        self.assertNotIn("…", text)


class MainTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.report = Path(self.dir.name) / "report.xml"
        self.report.write_text(REPORT, encoding="utf-8")

    def run_main(self, *args, diff=DIFF):
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.object(cov, "git_diff", return_value=diff) as git, redirect_stdout(out), redirect_stderr(err):
            try:
                code = cov.main(["--report", str(self.report), *args])
            except SystemExit as e:
                code = e.code
        return code, out.getvalue(), err.getvalue(), git

    def test_without_base_prints_totals_and_never_runs_git(self):
        code, out, _, git = self.run_main()
        self.assertEqual(code, 0)
        self.assertIn("lines 70.0 %", out)
        self.assertNotIn("Changed lines", out)
        git.assert_not_called()

    def test_summary_is_repeatable(self):
        a, b = Path(self.dir.name) / "a.md", Path(self.dir.name) / "b.md"
        code, out, _, _ = self.run_main("--base", "origin/x", "--summary", str(a), "--summary", str(b))
        self.assertEqual(code, 0)
        self.assertEqual(a.read_text(encoding="utf-8"), out.rstrip("\n") + "\n")
        self.assertEqual(a.read_text(encoding="utf-8"), b.read_text(encoding="utf-8"))

    def test_min_needs_base(self):
        code, _, err, _ = self.run_main("--min", "80")
        self.assertEqual(code, 2)
        self.assertIn("--min checks changed lines and needs --base", err)

    def test_min_fails_below_threshold(self):
        self.assertEqual(self.run_main("--base", "b", "--min", "80")[0], 1)  # 2 of 3 changed lines
        self.assertEqual(self.run_main("--base", "b", "--min", "60")[0], 0)

    def test_max_files_must_be_positive(self):
        for value in ("0", "-1"):
            code, _, err, _ = self.run_main("--base", "b", "--max-files", value)
            self.assertEqual(code, 2)
            self.assertIn("must be at least 1", err)


if __name__ == "__main__":
    unittest.main()
