"""Tests of scripts/ui-coverage.py: tag parsing, scenario coverage, the JaCoCo XML summary and execution data files."""

from __future__ import annotations

import importlib.util
import struct
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("ui_coverage", Path(__file__).with_name("ui-coverage.py"))
ui = importlib.util.module_from_spec(spec)
sys.modules["ui_coverage"] = ui
spec.loader.exec_module(ui)


class TagParsingTest(unittest.TestCase):
    def test_constants_and_builders_of_tags_objects(self):
        text = '''
            internal object ProfileTags {
                const val SCREEN = "profile.screen"
                fun theme(name: String) = "profile.theme.$name"
                // not a tag: an unrelated constant with a dollar
                const val NOT_A_TAG = "x$y"
            }
            object Other { const val SOMETHING = "not.in.a.tags.object" }
            Box(Modifier.testTag("analytics.tile.forecast"))
            Tile("Доходы", tiles.income, chartId = "analytics.tile.income")
        '''
        tags = ui.parse_tags(text)
        self.assertEqual({"profile.screen", "analytics.tile.forecast", "analytics.tile.income"}, tags.exact)
        self.assertEqual({"profile.theme."}, tags.prefixes)

    def test_flow_ids_and_screens(self):
        self.assertEqual("operations.search", ui.screen_of("operations.search.chip.period"))
        self.assertEqual("ds.tab", ui.screen_of("ds.tab"))
        self.assertEqual("auth", ui.screen_of("auth"))


class ScenarioCoverageTest(unittest.TestCase):
    def test_reached_tags_and_screens_per_feature(self):
        tags = ui.Tags(exact={"auth.phone", "auth.phone.input", "auth.call", "ds.back"}, prefixes={"ds.tab."})
        ids = {"auth.phone.input", "ds.tab.profile", "stale.tag"}
        c = ui.scenario_coverage(tags, ids)
        self.assertEqual(({"auth.phone"}, {"auth.phone", "auth.call"}), c.screens["auth"])
        self.assertEqual(({"auth.phone.input"}, {"auth.phone", "auth.phone.input", "auth.call"}), c.tags["auth"])
        self.assertEqual(({"ds.tab"}, {"ds.tab", "ds.back"}), c.screens["ds"])
        self.assertEqual(({"ds.tab.*"}, {"ds.tab.*", "ds.back"}), c.tags["ds"])
        self.assertEqual({"stale.tag"}, c.unknown_ids)

    def test_markdown_lists_totals_and_missing_tags(self):
        tags = ui.Tags(exact={"auth.phone", "auth.call"})
        text = ui.scenario_markdown(ui.scenario_coverage(tags, {"auth.phone", "ghost"}))
        self.assertIn("Screens: **50.0 %** (1/2), test tags: **50.0 %** (1/2)", text)
        self.assertIn("| `auth` | 1/2 | 1/2 | `auth.call` |", text)
        self.assertIn("`ghost`", text)

    def test_repository_tags_are_all_referenced_by_known_ids(self):
        """Every id a flow uses is a tag the app declares — a renamed tag would show up here."""
        c = ui.scenario_coverage(ui.declared_tags(ROOT), ui.flow_ids(ROOT))
        self.assertEqual(set(), c.unknown_ids)
        self.assertGreater(sum(len(a) for _, a in c.tags.values()), 100)


JACOCO_XML = """<?xml version="1.0" encoding="UTF-8"?>
<report name="e2e">
  <package name="ru/finassist/pf/feature/auth/impl/ui">
    <sourcefile name="AuthScreens.kt"><counter type="LINE" missed="10" covered="30"/></sourcefile>
    <sourcefile name="CallViewModel.kt"><counter type="LINE" missed="5" covered="5"/></sourcefile>
    <counter type="LINE" missed="15" covered="35"/>
  </package>
  <package name="ru/finassist/pf/feature/profile/impl/ui">
    <sourcefile name="DeleteAccountScreen.kt"><counter type="LINE" missed="8" covered="0"/></sourcefile>
    <counter type="LINE" missed="8" covered="0"/>
  </package>
  <package name="ru/finassist/pf/ui">
    <counter type="LINE" missed="2" covered="18"/>
  </package>
  <package name="ru/finassist/pf/mock/domain">
    <counter type="LINE" missed="0" covered="0"/>
  </package>
  <counter type="LINE" missed="25" covered="53"/>
  <counter type="BRANCH" missed="10" covered="30"/>
</report>
"""


class CodeCoverageTest(unittest.TestCase):
    def test_modules_and_untouched_screens(self):
        with tempfile.TemporaryDirectory() as tmp:
            xml = Path(tmp) / "report.xml"
            xml.write_text(JACOCO_XML, encoding="utf-8")
            c = ui.code_coverage(xml)
        self.assertEqual((53, 25), (c.lines.covered, c.lines.missed))
        self.assertEqual((30, 10), (c.branches.covered, c.branches.missed))
        self.assertEqual(35, c.modules["feature/auth"].covered)
        self.assertEqual(18, c.modules["app"].covered)
        self.assertEqual(["feature/profile: DeleteAccountScreen.kt"], c.untouched_screens)
        text = ui.code_markdown(c)
        self.assertIn("Lines: **67.9 %** (53/78), branches: **75.0 %** (30/40)", text)
        self.assertIn("| `feature/auth` | 70.0 % | 35/50 |", text)
        self.assertNotIn("mock/backend", text)  # no lines at all: left out of the table

    def test_not_measured_lists_top_level_declarations_named_R_in_e2e_modules(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            def write(rel: str, text: str) -> None:
                path = root / rel
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding="utf-8")
            write("feature/statements/impl/src/main/kotlin/ui/ResultScreen.kt", "@Composable\nfun ResultScreen() {}\nprivate class Row\n")
            write("feature/statements/impl/src/main/kotlin/ui/Texts.kt", "internal object ResultTexts\ndata class Rows(val n: Int)\nclass Summary\n")
            write("core/common/src/main/kotlin/time/RussianDates.kt", "object RussianDates\n")  # no top-level function: no …Kt facade
            write("app/src/mockE2e/kotlin/e2e/Recorder.kt", "internal class Recorder\n")
            write("core/network/src/main/kotlin/RequestId.kt", "class RequestIdInterceptor\n")  # not in the e2e APK
            write("feature/auth/impl/src/test/kotlin/Rig.kt", "class Rig\n")  # tests are not measured anyway
            write("feature/auth/impl/build/generated/R.kt", "class Rx\n")
            self.assertEqual(
                [
                    "app: Recorder",
                    "core/common: RussianDates",
                    "feature/statements: ResultScreenKt",
                    "feature/statements: ResultTexts",
                    "feature/statements: Row",
                    "feature/statements: Rows",
                ],
                ui.not_measured(root),
            )
            xml = root / "report.xml"
            xml.write_text(JACOCO_XML, encoding="utf-8")
            text = ui.code_markdown(ui.code_coverage(xml, root))
            self.assertIn("Not measured — 6 top-level declaration(s)", text)
            self.assertIn("`feature/statements: ResultTexts`", text)

    def test_module_of_package(self):
        self.assertEqual("feature/auth", ui.module_of("ru/finassist/pf/feature/auth/impl/ui"))
        self.assertEqual("core/designsystem", ui.module_of("ru/finassist/pf/core/designsystem/components"))
        self.assertEqual("mock/backend", ui.module_of("ru/finassist/pf/mock"))
        self.assertEqual("app", ui.module_of("ru/finassist/pf"))
        self.assertEqual("app", ui.module_of("ru/finassist/pf/di"))


def utf(text: str) -> bytes:
    data = text.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def exec_file(probes: int = 20) -> bytes:
    header = b"\x01" + struct.pack(">HH", 0xC0C0, 0x1007)
    session = b"\x10" + utf("emulator-5554") + struct.pack(">qq", 1, 2)
    data = b"\x11" + struct.pack(">q", 0x1234) + utf("ru/finassist/pf/Foo") + bytes([probes]) + b"\xff" * ((probes + 7) // 8)
    return header + session + data


class ExecutionDataTest(unittest.TestCase):
    def test_complete_file_is_accepted(self):
        self.assertTrue(ui.exec_file_complete(exec_file()))
        self.assertTrue(ui.exec_file_complete(exec_file(probes=0)))

    def test_var_int_probe_counts(self):
        # 300 probes: var-int 0xAC 0x02, then 38 bytes of probes.
        header = b"\x01" + struct.pack(">HH", 0xC0C0, 0x1007)
        block = b"\x11" + struct.pack(">q", 1) + utf("A") + b"\xac\x02" + b"\x00" * 38
        self.assertTrue(ui.exec_file_complete(header + block))
        self.assertFalse(ui.exec_file_complete(header + block[:-1]))

    def test_truncated_or_foreign_files_are_rejected(self):
        whole = exec_file()
        for cut in (1, 4, len(whole) // 2, len(whole) - 1):
            self.assertFalse(ui.exec_file_complete(whole[:cut]), cut)
        self.assertFalse(ui.exec_file_complete(b""))
        self.assertFalse(ui.exec_file_complete(b"\x01\x00\x00\x10\x07"))  # wrong magic
        self.assertFalse(ui.exec_file_complete(whole[5:]))  # no header

    def test_collect_keeps_the_newest_complete_file_per_process(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = Path(tmp) / "device"
            src.mkdir()
            (src / "pf-100-1-00001.ec").write_bytes(exec_file(8))
            (src / "pf-100-1-00002.ec").write_bytes(exec_file(16)[:-3])  # killed mid-write
            (src / "pf-200-2-00001.ec").write_bytes(exec_file(8))
            (src / "pf-200-2-00002 (1).ec").write_bytes(exec_file(24))  # MediaStore's name for a duplicate
            (src / "other.ec").write_bytes(exec_file(8))
            (src / "pf-300-3-00001.ec").write_bytes(b"\x01\x00")
            dst = Path(tmp) / "ec"
            self.assertEqual((3, 2), ui.collect(src, dst))
            self.assertEqual(exec_file(8), (dst / "100-1.ec").read_bytes())
            self.assertEqual(exec_file(24), (dst / "200-2.ec").read_bytes())
            self.assertEqual({"100-1.ec", "200-2.ec"}, {p.name for p in dst.iterdir()})


if __name__ == "__main__":
    unittest.main()
