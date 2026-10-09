#!/usr/bin/env python3
"""Sends UI test results to Qase as one test run (docs/e2e.md).

Reads JUnit XML reports — Maestro (`build/e2e/maestro-report.xml`) and the instrumented tests
(`app/build/outputs/androidTest-results/**/TEST-*.xml`) — maps each test to its Qase case through
`scripts/qase-cases.json`, then creates a run, records the results in bulk and completes the run.

Test keys in the map:
- a Maestro flow: its path from the repository root, `.maestro/flows/<feature>/<flow>.yaml`;
- an instrumented test: `<class>#<method>`.
A test without a case is reported as a warning and left out of the run.

Usage:
  QASE_API_TOKEN=… scripts/qase-report.py --title "UI tests · main · abc1234" REPORT [REPORT…]
  scripts/qase-report.py --dry-run REPORT…   # print what would be sent, no token needed

Without QASE_API_TOKEN the script prints a notice and exits 0, so CI without the secret stays green.
Standard library only.
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parent.parent
CASES_FILE = ROOT / "scripts" / "qase-cases.json"
API = "https://api.qase.io/v1"
# Qase keeps stack traces short in the UI anyway; huge Maestro traces only slow the request down.
MAX_TRACE = 8000
# One retry after this pause for rate limits, 5xx and network errors.
RETRY_DELAY_S = 5
# The same test in several reports (e.g. several devices) keeps its worst result.
SEVERITY = {"passed": 0, "skipped": 1, "failed": 2}


@dataclass
class TestResult:
    key: str
    name: str
    status: str  # passed | failed | skipped
    time_ms: int
    message: str = ""
    trace: str = ""


def test_key(case: ET.Element) -> str:
    """`.maestro/…` path for a Maestro flow (its `file` attribute is absolute), `<class>#<method>` otherwise."""
    file = case.get("file") or ""
    marker = "/.maestro/"
    if marker in file:
        return ".maestro/" + file.split(marker, 1)[1]
    return f"{case.get('classname', '')}#{case.get('name', '')}"


def parse_report(xml_text: str) -> list[TestResult]:
    results = []
    root = ET.fromstring(xml_text)
    for case in root.iter("testcase"):
        problem = case.find("failure")
        if problem is None:
            problem = case.find("error")
        if problem is not None:
            status = "failed"
        elif case.find("skipped") is not None:
            status = "skipped"
        else:
            # Maestro also writes status="ERROR"/"FAILURE" on the testcase itself.
            status = "failed" if (case.get("status") or "SUCCESS").upper() not in ("SUCCESS", "PASSED") else "passed"
        try:
            time_ms = int(float(case.get("time") or 0) * 1000)
        except ValueError:
            time_ms = 0
        results.append(TestResult(
            key=test_key(case),
            name=case.get("name", ""),
            status=status,
            time_ms=time_ms,
            message=(problem.get("message") or "") if problem is not None else "",
            trace=((problem.text or "").strip()[:MAX_TRACE]) if problem is not None else "",
        ))
    return results


def map_results(results: list[TestResult], cases: dict[str, int]) -> tuple[list[dict], list[TestResult]]:
    """Qase result payloads for mapped tests (one per case, the worst one), and the tests that have no case."""
    by_case: dict[int, dict] = {}
    unmapped = []
    for r in results:
        case_id = cases.get(r.key)
        if case_id is None:
            unmapped.append(r)
            continue
        item = {"case_id": case_id, "status": r.status, "time_ms": r.time_ms}
        if r.message:
            item["comment"] = r.message
        if r.trace:
            item["stacktrace"] = r.trace
        kept = by_case.get(case_id)
        if kept is None or SEVERITY[item["status"]] > SEVERITY[kept["status"]]:
            by_case[case_id] = item
    return list(by_case.values()), unmapped


Request = Callable[[str, str, dict | None], dict]


class QaseError(RuntimeError):
    def __init__(self, message: str, retryable: bool = False):
        super().__init__(message)
        self.retryable = retryable


def http_request(token: str, sleep: Callable[[float], None] = time.sleep) -> Request:
    def once(method: str, path: str, body: dict | None) -> dict:
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(API + path, data=data, method=method, headers={
            "Token": token, "Content-Type": "application/json", "Accept": "application/json",
        })
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                answer = json.loads(resp.read() or b"{}")
        except urllib.error.HTTPError as e:
            text = e.read().decode(errors="replace")[:500]
            raise QaseError(f"{method} {path}: HTTP {e.code} {text}", retryable=e.code == 429 or e.code >= 500) from e
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            raise QaseError(f"{method} {path}: {e}", retryable=True) from e
        if not answer.get("status", False):
            raise QaseError(f"{method} {path}: {answer}")
        return answer.get("result") or {}

    def call(method: str, path: str, body: dict | None) -> dict:
        try:
            return once(method, path, body)
        except QaseError as e:
            if not e.retryable:
                raise
            print(f"qase: {e}; retrying in {RETRY_DELAY_S} s")
            sleep(RETRY_DELAY_S)
            return once(method, path, body)
    return call


def send(project: str, title: str, description: str, payload: list[dict], request: Request) -> int:
    """Creates the run with exactly the reported cases, records the results, completes it; returns the run id.

    A run whose results could not be recorded is deleted (best effort): an open empty run would read as «not run»,
    and the next attempt creates a new one anyway.
    """
    case_ids = sorted({p["case_id"] for p in payload})
    run = request("POST", f"/run/{project}", {
        "title": title, "description": description, "cases": case_ids, "is_autotest": True,
    })
    run_id = run["id"]
    try:
        request("POST", f"/result/{project}/{run_id}/bulk", {"results": payload})
    except Exception:
        try:
            request("DELETE", f"/run/{project}/{run_id}", None)
        except Exception as cleanup:  # the original error matters more
            print(f"::warning::could not delete the empty Qase run {run_id}: {cleanup}")
        raise
    request("POST", f"/run/{project}/{run_id}/complete", {})
    return run_id


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("reports", nargs="+", help="JUnit XML files or glob patterns (** allowed)")
    parser.add_argument("--title", default="UI tests")
    parser.add_argument("--description", default="")
    parser.add_argument("--dry-run", action="store_true", help="print the payload, do not call Qase")
    args = parser.parse_args(argv)

    config = json.loads(CASES_FILE.read_text(encoding="utf-8"))
    files = sorted({f for pattern in args.reports for f in glob.glob(pattern, recursive=True)})
    results = [r for f in files for r in parse_report(Path(f).read_text(encoding="utf-8"))]
    payload, unmapped = map_results(results, config["cases"])
    for r in unmapped:
        # GitHub turns this line into a warning annotation on the run.
        print(f"::warning::no Qase case for {r.key} ({r.name}); add it to scripts/qase-cases.json")
    print(f"qase: {len(files)} report(s), {len(results)} test(s), {len(payload)} mapped")
    if not files:
        print("::notice::no JUnit reports found (the tests did not run or stopped before writing them)")

    if not payload:
        print("qase: nothing to report")
        return 0
    if args.dry_run:
        print(json.dumps({"title": args.title, "results": payload}, ensure_ascii=False, indent=2))
        return 0
    token = os.environ.get("QASE_API_TOKEN", "")
    if not token:
        print("::notice::QASE_API_TOKEN is not set; results are not sent to Qase")
        return 0
    project = config["project"]
    try:
        run_id = send(project, args.title, args.description, payload, http_request(token))
    except QaseError as e:
        print(f"::error::Qase: {e}")
        return 1
    print(f"qase: run https://app.qase.io/run/{project}/dashboard/{run_id}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
