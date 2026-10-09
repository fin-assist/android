"""Tests of scripts/qase-report.py and of the case map. Run: python3 -m unittest discover -s scripts -p 'test_*.py'"""

import importlib.util
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location("qase_report", ROOT / "scripts" / "qase-report.py")
qase = importlib.util.module_from_spec(_spec)
sys.modules["qase_report"] = qase  # dataclasses resolve the module by name
_spec.loader.exec_module(qase)

MAESTRO = """<?xml version='1.0' encoding='UTF-8'?>
<testsuites><testsuite name="Test Suite" tests="2" failures="1">
  <testcase id="A" name="A" classname="A" file="/home/runner/work/android/android/.maestro/flows/auth/a.yaml"
            time="12.5" status="SUCCESS"/>
  <testcase id="B" name="B" classname="B" file="/w/.maestro/flows/ops/b.yaml" time="3" status="ERROR">
    <failure message="Assertion is false: id: x is visible">trace line</failure>
  </testcase>
</testsuite></testsuites>"""

NATIVE = """<?xml version='1.0' encoding='UTF-8'?>
<testsuite name="ru.finassist.pf.StatementPickerTest" tests="2">
  <testcase name="picks" classname="ru.finassist.pf.StatementPickerTest" time="21.352" />
  <testcase name="ignored" classname="ru.finassist.pf.StatementPickerTest" time="0"><skipped/></testcase>
</testsuite>"""


class ParseTest(unittest.TestCase):
    def test_maestro_flow_key_is_repo_path_and_failure_is_kept(self):
        a, b = qase.parse_report(MAESTRO)
        self.assertEqual((a.key, a.status, a.time_ms), (".maestro/flows/auth/a.yaml", "passed", 12500))
        self.assertEqual((b.key, b.status), (".maestro/flows/ops/b.yaml", "failed"))
        self.assertEqual(b.message, "Assertion is false: id: x is visible")
        self.assertEqual(b.trace, "trace line")

    def test_maestro_status_attribute_alone_marks_failure(self):
        (r,) = qase.parse_report('<testsuite><testcase name="C" file="/x/.maestro/flows/c.yaml" status="FAILURE"/></testsuite>')
        self.assertEqual(r.status, "failed")

    def test_native_key_is_class_and_method(self):
        picks, ignored = qase.parse_report(NATIVE)
        self.assertEqual(picks.key, "ru.finassist.pf.StatementPickerTest#picks")
        self.assertEqual((picks.status, picks.time_ms), ("passed", 21352))
        self.assertEqual(ignored.status, "skipped")


class MapTest(unittest.TestCase):
    def test_unmapped_tests_are_left_out(self):
        results = qase.parse_report(MAESTRO)
        payload, unmapped = qase.map_results(results, {".maestro/flows/auth/a.yaml": 7})
        self.assertEqual(payload, [{"case_id": 7, "status": "passed", "time_ms": 12500}])
        self.assertEqual([r.key for r in unmapped], [".maestro/flows/ops/b.yaml"])

    def test_failure_carries_comment_and_stacktrace(self):
        results = qase.parse_report(MAESTRO)
        payload, _ = qase.map_results(results, {".maestro/flows/ops/b.yaml": 3})
        self.assertEqual(payload[0]["comment"], "Assertion is false: id: x is visible")
        self.assertEqual(payload[0]["stacktrace"], "trace line")


class SendTest(unittest.TestCase):
    def test_creates_run_with_reported_cases_records_results_and_completes(self):
        calls = []

        def fake(method, path, body):
            calls.append((method, path, body))
            return {"id": 42} if path == "/run/PF" else {}

        payload = [{"case_id": 3, "status": "failed", "time_ms": 1}, {"case_id": 1, "status": "passed", "time_ms": 2}]
        run_id = qase.send("PF", "UI tests", "ci link", payload, fake)
        self.assertEqual(run_id, 42)
        self.assertEqual([c[:2] for c in calls], [
            ("POST", "/run/PF"), ("POST", "/result/PF/42/bulk"), ("POST", "/run/PF/42/complete"),
        ])
        self.assertEqual(calls[0][2]["cases"], [1, 3])
        self.assertTrue(calls[0][2]["is_autotest"])
        self.assertEqual(calls[1][2], {"results": payload})


class CaseMapTest(unittest.TestCase):
    """The map in scripts/qase-cases.json follows the flows: a new flow needs a Qase case (docs/e2e.md)."""

    def setUp(self):
        self.cases = json.loads((ROOT / "scripts" / "qase-cases.json").read_text(encoding="utf-8"))["cases"]

    def test_every_maestro_flow_has_a_case(self):
        flows = {str(p.relative_to(ROOT)) for p in (ROOT / ".maestro" / "flows").rglob("*.yaml")}
        missing = sorted(flows - set(self.cases))
        self.assertEqual(missing, [], "add these flows to scripts/qase-cases.json (and create the cases in Qase)")

    def test_every_mapped_flow_exists(self):
        stale = sorted(k for k in self.cases if k.startswith(".maestro/") and not (ROOT / k).exists())
        self.assertEqual(stale, [], "these flows were renamed or removed: update scripts/qase-cases.json")

    def test_case_ids_are_unique(self):
        ids = list(self.cases.values())
        self.assertEqual(len(ids), len(set(ids)))


if __name__ == "__main__":
    unittest.main()
