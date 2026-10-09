"""Tests of scripts/qase-report.py and of the case map. Run: python3 -m unittest discover -s scripts -p 'test_*.py'"""

import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
import urllib.error
from contextlib import redirect_stdout
from unittest import mock
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

    def test_error_and_empty_failure_count_as_failed(self):
        # <error> is an unexpected exception in the test (often an app crash), not an infrastructure problem.
        xml = """<testsuite>
          <testcase name="crash" classname="T"><error message="boom"/></testcase>
          <testcase name="empty" classname="T"><failure/></testcase>
        </testsuite>"""
        crash, empty = qase.parse_report(xml)
        self.assertEqual((crash.status, crash.message, crash.trace), ("failed", "boom", ""))
        self.assertEqual((empty.status, empty.message), ("failed", ""))

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

    def test_same_case_twice_keeps_the_worst_result(self):
        passed = qase.TestResult("k", "k", "passed", 1)
        failed = qase.TestResult("k", "k", "failed", 2, message="m")
        payload, _ = qase.map_results([passed, failed, passed], {"k": 5})
        self.assertEqual(payload, [{"case_id": 5, "status": "failed", "time_ms": 2, "comment": "m"}])

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


    def test_run_is_deleted_when_results_cannot_be_recorded(self):
        calls = []

        def fake(method, path, body):
            calls.append((method, path))
            if path.endswith("/bulk"):
                raise qase.QaseError("HTTP 500")
            return {"id": 7} if path == "/run/PF" else {}

        with self.assertRaises(qase.QaseError):
            qase.send("PF", "t", "", [{"case_id": 1, "status": "passed", "time_ms": 0}], fake)
        self.assertEqual(calls, [("POST", "/run/PF"), ("POST", "/result/PF/7/bulk"), ("DELETE", "/run/PF/7")])


class HttpTest(unittest.TestCase):
    @staticmethod
    def ok(result):
        resp = mock.MagicMock()
        resp.__enter__.return_value.read.return_value = json.dumps({"status": True, "result": result}).encode()
        return resp

    def test_retries_once_after_a_server_error(self):
        error = urllib.error.HTTPError("u", 503, "busy", {}, io.BytesIO(b"busy"))
        with mock.patch.object(qase.urllib.request, "urlopen", side_effect=[error, self.ok({"id": 1})]) as urlopen:
            with redirect_stdout(io.StringIO()):
                result = qase.http_request("t", sleep=lambda s: None)("POST", "/run/PF", {})
        self.assertEqual(result, {"id": 1})
        self.assertEqual(urlopen.call_count, 2)

    def test_client_error_is_not_retried(self):
        error = urllib.error.HTTPError("u", 422, "bad", {}, io.BytesIO(b"bad"))
        with mock.patch.object(qase.urllib.request, "urlopen", side_effect=[error]) as urlopen:
            with self.assertRaises(qase.QaseError):
                qase.http_request("t", sleep=lambda s: None)("POST", "/run/PF", {})
        self.assertEqual(urlopen.call_count, 1)

    def test_network_error_becomes_qase_error(self):
        errors = [urllib.error.URLError("reset"), urllib.error.URLError("reset")]
        with mock.patch.object(qase.urllib.request, "urlopen", side_effect=errors):
            with redirect_stdout(io.StringIO()), self.assertRaises(qase.QaseError):
                qase.http_request("t", sleep=lambda s: None)("GET", "/project", None)


    def test_non_json_answer_becomes_qase_error(self):
        page = mock.MagicMock()
        page.__enter__.return_value.read.return_value = b"<html>gateway</html>"
        with mock.patch.object(qase.urllib.request, "urlopen", side_effect=[page, page]):
            with redirect_stdout(io.StringIO()), self.assertRaises(qase.QaseError):
                qase.http_request("t", sleep=lambda s: None)("GET", "/project", None)


class MainTest(unittest.TestCase):
    """CI without the secret must stay green: no token → notice, exit 0, no request."""

    def setUp(self):
        self.report = tempfile.NamedTemporaryFile("w", suffix=".xml", delete=False, encoding="utf-8")
        self.report.write(MAESTRO.replace("/w/.maestro/flows/ops/b.yaml", "/w/.maestro/flows/auth/sign_in_existing_user.yaml"))
        self.report.close()

    def tearDown(self):
        os.unlink(self.report.name)

    def run_main(self, *args):
        out = io.StringIO()
        with redirect_stdout(out), mock.patch.object(qase.urllib.request, "urlopen") as urlopen:
            code = qase.main([*args, self.report.name])
        return code, out.getvalue(), urlopen

    def test_without_token_prints_notice_and_succeeds(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            code, out, urlopen = self.run_main()
        self.assertEqual(code, 0)
        self.assertIn("QASE_API_TOKEN is not set", out)
        urlopen.assert_not_called()

    def test_dry_run_prints_payload_without_requests(self):
        with mock.patch.dict(os.environ, {"QASE_API_TOKEN": "t"}):
            code, out, urlopen = self.run_main("--dry-run")
        self.assertEqual(code, 0)
        self.assertIn('"case_id": 1', out)
        urlopen.assert_not_called()

    def test_missing_reports_are_announced(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = qase.main(["/nonexistent/report.xml"])
        self.assertEqual(code, 0)
        self.assertIn("no JUnit reports found", out.getvalue())


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

    def test_every_mapped_native_test_exists(self):
        sources = ROOT / "app" / "src"
        stale = []
        for key in self.cases:
            if key.startswith(".maestro/"):
                continue
            cls, method = key.split("#")
            path = next(iter(sources.glob(f"androidTest*/kotlin/{cls.replace('.', '/')}.kt")), None)
            if path is None or f"fun {method}(" not in path.read_text(encoding="utf-8"):
                stale.append(key)
        self.assertEqual(stale, [], "these instrumented tests were renamed or removed: update scripts/qase-cases.json")

    def test_case_ids_are_unique(self):
        ids = list(self.cases.values())
        self.assertEqual(len(ids), len(set(ids)))


if __name__ == "__main__":
    unittest.main()
