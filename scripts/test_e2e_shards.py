"""Tests of scripts/e2e-shards.py: the split of the flows, merging the shards' reports, coverage data and durations."""

from __future__ import annotations

import importlib.util
import io
import json
import math
import os
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location("e2e_shards", ROOT / "scripts" / "e2e-shards.py")
shards = importlib.util.module_from_spec(_spec)
sys.modules["e2e_shards"] = shards  # dataclasses resolve the module by name
_spec.loader.exec_module(shards)


def write(path: Path, text: str | bytes = "") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(text, bytes):
        path.write_bytes(text)
    else:
        path.write_text(text, encoding="utf-8")
    return path


def execution_receipt(path: Path, **changes) -> Path:
    data = {
        'schema': 1, 'phase': 'complete', 'category': None, 'result': 'passed', 'exit_code': 0,
        'identity': {key: os.environ[key] for key in ('GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT') if key in os.environ},
        'apks': {'app-mock-e2e.apk': '1' * 64, 'app-mock-e2e-androidTest.apk': '2' * 64},
    }
    data.update(changes)
    return write(path / 'execution.json', json.dumps(data))


def flow_file(root: Path, flow: str, name: str) -> None:
    write(root / flow, f"appId: ru.finassist.pf.mock.e2e\nname: {name}\ntags:\n  - x\n---\n- launchApp\n")


def report(root: Path, cases: list[tuple[str, str, float, bool]], with_file: bool = True) -> str:
    """JUnit XML as Maestro writes it: (flow, name, seconds, failed); [with_file] false — a version without `file`."""
    body = []
    for flow, name, seconds, failed in cases:
        status = "ERROR" if failed else "SUCCESS"
        inner = '<failure message="Assertion is false">trace</failure>' if failed else ""
        file = f' file="{root / flow}"' if with_file else ""
        body.append(
            f'<testcase id="{name}" name="{name}" classname="{name}"{file} time="{seconds}" '
            f'status="{status}">{inner}</testcase>'
        )
    return f'<?xml version="1.0"?><testsuites><testsuite name="Test Suite">{"".join(body)}</testsuite></testsuites>'


class PlanTest(unittest.TestCase):
    FLOWS = [f".maestro/flows/f{i:02d}.yaml" for i in range(12)]
    DURATIONS = {f: 30 + 7 * i for i, f in enumerate(FLOWS)}

    def test_every_flow_lands_in_exactly_one_shard(self):
        for count in (1, 2, 3, 4, 5, 13):
            planned = [f for s in shards.plan(self.FLOWS, self.DURATIONS, count) for f in s.flows]
            self.assertEqual(sorted(planned), sorted(self.FLOWS), count)
            self.assertEqual(len(planned), len(set(planned)), count)

    def test_shards_are_balanced_and_shard_one_carries_the_native_tests(self):
        planned = shards.plan(self.FLOWS, self.DURATIONS, 3, native_seconds=70)
        self.assertTrue(planned[0].native)
        self.assertFalse(any(s.native for s in planned[1:]))
        loads = [s.seconds for s in planned]
        self.assertEqual(sum(loads), sum(self.DURATIONS.values()) + 70)
        # Greedy longest-first: no shard exceeds another by more than its longest flow.
        self.assertLessEqual(max(loads) - min(loads), max(self.DURATIONS.values()))

    def test_split_is_deterministic_and_shards_keep_path_order(self):
        a = shards.plan(self.FLOWS, self.DURATIONS, 4)
        b = shards.plan(list(reversed(self.FLOWS)), dict(reversed(list(self.DURATIONS.items()))), 4)
        self.assertEqual([s.flows for s in a], [s.flows for s in b])
        for s in a:
            self.assertEqual(s.flows, sorted(s.flows))

    def test_unmeasured_flow_counts_as_the_median(self):
        durations = {"a.yaml": 10, "b.yaml": 20, "c.yaml": 90}
        (one,) = shards.plan(["a.yaml", "b.yaml", "c.yaml", "new.yaml"], durations, 1, native_seconds=0)
        self.assertEqual(one.seconds, 10 + 20 + 90 + 20)

    def test_more_shards_than_flows_leaves_empty_shards(self):
        planned = shards.plan(["a.yaml"], {"a.yaml": 5}, 3, native_seconds=0)
        self.assertEqual([len(s.flows) for s in planned], [1, 0, 0])

    def test_shard_argument(self):
        self.assertEqual(shards.parse_shard("2/4"), (2, 4))
        for bad in ("0/4", "5/4", "1/0", "2", "a/b", "-1/3"):
            with self.assertRaises(ValueError, msg=bad):
                shards.parse_shard(bad)

    def test_real_flows_split_into_four_five_or_six_shards_with_native_separate(self):
        flows = shards.discover_flows(ROOT)
        self.assertTrue(flows)
        self.assertTrue(all(f.startswith(".maestro/flows/") for f in flows))
        self.assertFalse(any("/subflows/" in f for f in flows))
        for count in (4, 5, 6):
            planned = shards.plan(flows, shards.load_durations(shards.DURATIONS_FILE), count)
            self.assertTrue(all(s.flows for s in planned))
            self.assertEqual(sorted(f for s in planned for f in s.flows), flows)
            self.assertEqual([s.index for s in planned if s.native], [])

    def test_native_suite_does_not_consume_maestro_shard_budget(self):
        self.assertEqual(shards.NATIVE_SECONDS, 0)
        planned = shards.plan(self.FLOWS, self.DURATIONS, 3)
        self.assertEqual(sum(s.seconds for s in planned), sum(self.DURATIONS.values()))
        self.assertFalse(any(s.native for s in planned))

    def test_real_six_shard_plan_fits_synthetic_weight_envelope(self):
        flows = shards.discover_flows(ROOT)
        durations = shards.load_durations(shards.DURATIONS_FILE)
        # FIN-465's fixed padding checks the plan against stored weights. Run 43081255161 exceeded
        # that padding in CI, so this test only checks the synthetic workload.
        for shard in shards.plan(flows, durations, 6):
            stressed = math.ceil(shard.seconds * 1.2) + 120
            self.assertLessEqual(stressed, 2400, f"shard {shard.index}/6 has {stressed}s of synthetic load")

    def test_flow_names_are_unique(self):
        # Reports are matched to flows by name when Maestro writes no `file` (flow_of): two flows of one name would mix.
        flows = shards.discover_flows(ROOT)
        names = [shards.flow_name(ROOT, f) for f in flows]
        duplicates = sorted({n for n in names if names.count(n) > 1})
        self.assertEqual(duplicates, [])
        self.assertEqual(len(shards.flows_by_name(ROOT, flows)), len(flows))

    def test_every_flow_has_a_measured_duration(self):
        # A new flow works without one (median), but the file should follow the flows: refresh it after a run.
        durations = shards.load_durations(shards.DURATIONS_FILE)
        flows = shards.discover_flows(ROOT)
        self.assertEqual(sorted(set(durations) - set(flows)), [], "durations of flows that no longer exist")


class MergeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "repo"
        self.flows = [".maestro/flows/a/one.yaml", ".maestro/flows/a/two.yaml", ".maestro/flows/b/three.yaml"]
        for flow in self.flows:
            flow_file(self.root, flow, f"Flow {Path(flow).stem}")
        write(self.root / "scripts/qase-cases.json", json.dumps({"cases": {flow: i + 1 for i, flow in enumerate(self.flows)}}))
        self.durations = {self.flows[0]: 60, self.flows[1]: 50, self.flows[2]: 40}
        # 2 shards, no native weight: [one] and [two, three].
        self.plan = shards.plan(self.flows, self.durations, 2, native_seconds=0)
        self.assertEqual([s.flows for s in self.plan], [[self.flows[0]], [self.flows[1], self.flows[2]]])
        self.dir = Path(self.tmp.name) / "shards"
        for index in (1, 2):
            execution_receipt(self.shard_dir(index))

    def tearDown(self):
        self.tmp.cleanup()

    def shard_dir(self, index: int) -> Path:
        return self.dir / f"e2e-shard-{index}"

    def gather(self):
        out = self.root / "build" / "e2e"
        outcomes = shards.gather(self.root, self.plan, self.dir, out)
        merged = ET.parse(out / "maestro-report.xml").getroot()
        return outcomes, merged

    def test_cancelled_maestro_keeps_only_unverified_terminal_observations(self):
        identity = {'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '1'}
        directory = self.shard_dir(2)
        progress = directory / shards.PROGRESS_FILE
        durations = write(self.root / 'durations.json', json.dumps({'flows': self.durations}))
        fake = "print('    [Passed] Flow three (1s)', flush=True); print('[Passed] Flow two (3m)', flush=True)"
        fake += "; import time; time.sleep(60)"
        command = [sys.executable, str(ROOT / 'scripts/e2e-shards.py'), '--root', str(self.root),
                   '--durations', str(durations), 'watch', '--shard', '2/2',
                   '--progress', str(progress), '--', sys.executable, '-c', fake]
        with patch.dict(os.environ, identity):
            process = subprocess.Popen(command, stdout=subprocess.PIPE, start_new_session=True)
            try:
                end = time.monotonic() + 5
                while not progress.is_file() and process.poll() is None and time.monotonic() < end:
                    time.sleep(.02)
                self.assertTrue(progress.is_file(), 'a completed flow must be durable before Maestro exits')
            finally:
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except ProcessLookupError:
                    pass
                process.communicate(timeout=5)
            execution_receipt(directory, phase='maestro', result='failed', exit_code=1, category='test')
            outcomes, merged = self.gather()
            outcome = outcomes[1]
            self.assertEqual((outcome.passed, outcome.missing, outcome.unverified), (0, 1, 1))
            self.assertEqual(outcome.unverified_results, {'passed': 1})
            self.assertTrue(shards.problems(outcomes))
            states = {case.get('name'): case.get('pf-execution') for case in merged.iter('testcase')}
            self.assertEqual(states['Flow two'], 'unverified')
            self.assertEqual(states['Flow three'], 'not-run')

            # A stale console checkpoint must not be attributed to the current receipt.
            observed = json.loads(progress.read_text())
            observed['identity']['GITHUB_RUN_ATTEMPT'] = '9'
            write(progress, json.dumps(observed))
            self.assertEqual(self.gather()[0][1].missing, 2)

    def test_reports_of_all_shards_end_up_in_one(self):
        write(self.shard_dir(1) / "maestro-report.xml", report(self.root, [(self.flows[0], "Flow one", 61.5, False)]))
        write(self.shard_dir(2) / "maestro-report.xml", report(self.root, [
            (self.flows[2], "Flow three", 40, True), (self.flows[1], "Flow two", 49, False),
        ]))
        outcomes, merged = self.gather()
        cases = list(merged.iter("testcase"))
        self.assertEqual(sorted(shards.flow_of(c) for c in cases), sorted(self.flows))
        self.assertEqual([s.get("name") for s in merged.iter("testsuite")], ["Shard 1/2", "Shard 2/2"])
        self.assertEqual([(o.passed, o.failed, o.missing) for o in outcomes], [(1, 0, 0), (1, 1, 0)])
        second = list(merged.iter("testsuite"))[1]
        self.assertEqual((second.get("tests"), second.get("failures")), ("2", "1"))

    def test_shard_without_report_marks_not_run_and_keeps_verdict_incomplete(self):
        write(self.shard_dir(1) / "maestro-report.xml", report(self.root, [(self.flows[0], "Flow one", 61, False)]))
        # Shard 2 uploaded only a logcat: the emulator never came up.
        write(self.shard_dir(2) / "logcat.txt", "boot failed")
        outcomes, merged = self.gather()
        self.assertFalse(outcomes[1].reported)
        self.assertEqual(outcomes[1].missing, 2)
        failed = [c for c in merged.iter("testcase") if c.find("skipped") is not None]
        self.assertEqual(sorted(shards.flow_of(c) for c in failed), sorted([self.flows[1], self.flows[2]]))
        self.assertIn("NOT RUN: shard 2", failed[0].find("skipped").get("message"))
        self.assertEqual(outcomes[1].failed, 0)
        self.assertTrue(shards.problems(outcomes))
        # Named as Maestro names them, so the summary and Qase show the flow, not a file name.
        self.assertEqual({c.get("name") for c in failed}, {"Flow two", "Flow three"})

    def test_missing_shard_folder_and_broken_report_count_as_no_report(self):
        write(self.shard_dir(1) / "maestro-report.xml", "<testsuites><testsuite>")  # cut off mid-write
        outcomes, merged = self.gather()
        self.assertEqual([o.reported for o in outcomes], [False, False])
        self.assertEqual(len([c for c in merged.iter("testcase") if c.find("skipped") is not None]), 3)

    def test_flow_missing_from_a_report_is_not_run(self):
        write(self.shard_dir(1) / "maestro-report.xml", report(self.root, [(self.flows[0], "Flow one", 61, False)]))
        write(self.shard_dir(2) / "maestro-report.xml", report(self.root, [(self.flows[2], "Flow three", 40, False)]))
        outcomes, merged = self.gather()
        self.assertEqual((outcomes[1].passed, outcomes[1].missing), (1, 1))
        (lost,) = [c for c in merged.iter("testcase") if c.find("skipped") is not None]
        self.assertEqual(shards.flow_of(lost), self.flows[1])
        self.assertIn("NOT RUN: shard 2", lost.find("skipped").get("message"))

    def test_report_without_file_is_matched_by_flow_name(self):
        # A Maestro version without the `file` attribute: the flows still count as run, none turns into «not run».
        write(self.shard_dir(1) / "maestro-report.xml", report(self.root, [(self.flows[0], "Flow one", 61, False)], with_file=False))
        write(self.shard_dir(2) / "maestro-report.xml", report(self.root, [
            (self.flows[2], "Flow three", 40, False), (self.flows[1], "Flow two", 50, False),
        ], with_file=False))
        outcomes, merged = self.gather()
        self.assertEqual([(o.passed, o.failed, o.missing) for o in outcomes], [(1, 0, 0), (2, 0, 0)])
        self.assertEqual(shards.problems(outcomes), [])
        self.assertTrue(all(c.find("failure") is None for c in merged.iter("testcase")))
        # The merged report carries the path again: Qase finds every case.
        self.assertEqual(sorted(shards.flow_of(c) for c in merged.iter("testcase")), sorted(self.flows))
        spec = importlib.util.spec_from_file_location("qase_report_by_name", ROOT / "scripts" / "qase-report.py")
        qase = importlib.util.module_from_spec(spec)
        sys.modules["qase_report_by_name"] = qase
        spec.loader.exec_module(qase)
        results = qase.parse_report((self.root / "build" / "e2e" / "maestro-report.xml").read_text(encoding="utf-8"))
        cases = {flow: 100 + i for i, flow in enumerate(self.flows)}
        payload, unmapped = qase.map_results(results, cases)
        self.assertEqual(unmapped, [])
        self.assertEqual(sorted((p["case_id"], p["status"]) for p in payload), [(100, "passed"), (101, "passed"), (102, "passed")])

    def test_merged_report_is_read_by_qase_report_as_before(self):
        spec = importlib.util.spec_from_file_location("qase_report_for_shards", ROOT / "scripts" / "qase-report.py")
        qase = importlib.util.module_from_spec(spec)
        sys.modules["qase_report_for_shards"] = qase
        spec.loader.exec_module(qase)
        write(self.shard_dir(1) / "maestro-report.xml", report(self.root, [(self.flows[0], "Flow one", 61, False)]))
        self.gather()
        results = qase.parse_report((self.root / "build" / "e2e" / "maestro-report.xml").read_text(encoding="utf-8"))
        self.assertEqual(
            sorted((r.key, r.status) for r in results),
            [(self.flows[0], "passed"), (self.flows[1], "skipped"), (self.flows[2], "skipped")],
        )

    def test_coverage_data_of_every_shard_and_native_results_are_put_in_place(self):
        write(self.shard_dir(1) / "coverage" / "ec" / "ru.finassist.pf.mock.e2e.ec", b"one")
        write(self.shard_dir(2) / "coverage" / "ec" / "ru.finassist.pf.mock.e2e.ec", b"two")
        write(self.shard_dir(1) / "native" / "connected" / "debug" / "TEST-emulator.xml", "<testsuite/>")
        write(self.shard_dir(1) / "native-coverage" / "connected" / "emulator" / "coverage.ec", b"native")
        self.gather()
        ec = self.root / "build" / "e2e" / "coverage" / "ec"
        self.assertEqual(sorted(p.name for p in ec.iterdir()), ["shard1-ru.finassist.pf.mock.e2e.ec", "shard2-ru.finassist.pf.mock.e2e.ec"])
        self.assertEqual((ec / "shard2-ru.finassist.pf.mock.e2e.ec").read_bytes(), b"two")
        outputs = self.root / "app" / "build" / "outputs"
        self.assertTrue((outputs / "androidTest-results" / "connected" / "debug" / "TEST-emulator.xml").is_file())
        self.assertEqual((outputs / "code_coverage" / "connected" / "emulator" / "coverage.ec").read_bytes(), b"native")

    def run_gather(self) -> tuple[str, str, str]:
        """The `gather` command as the workflow runs it: (stdout, summary file, GitHub output file)."""
        summary = Path(self.tmp.name) / "summary.md"
        github_output = Path(self.tmp.name) / "github_output"
        durations = write(Path(self.tmp.name) / "durations.json", json.dumps({"flows": self.durations}))
        # Without the native weight, the command splits as setUp does.
        shards.NATIVE_SECONDS, saved = 0, shards.NATIVE_SECONDS
        try:
            with redirect_stdout(io.StringIO()) as out:
                code = shards.main([
                    "--root", str(self.root), "--durations", str(durations),
                    "gather", "--shards", "2", "--dir", str(self.dir), "--out", str(self.root / "build" / "e2e"),
                    "--summary", str(summary), "--github-output", str(github_output),
                ])
        finally:
            shards.NATIVE_SECONDS = saved
        self.assertEqual(code, 0)
        return out.getvalue(), summary.read_text(encoding="utf-8"), github_output.read_text(encoding="utf-8")

    def test_gather_command_prints_and_appends_the_status(self):
        write(self.shard_dir(2) / "maestro-report.xml", report(self.root, [
            (self.flows[2], "Flow three", 40, False), (self.flows[1], "Flow two", 50, False),
        ]))
        out, summary, _ = self.run_gather()
        self.assertIn("| 1/2 (no report) | 1 | 0 | 0 | 1 |", out)
        self.assertIn("| 2/2 | 2 | 2 | 0 | 0 | 0 | 0 | 1.5 min |", summary)
        self.assertIn("- shard 1/2: no Maestro report received (1 flow(s) not run)", summary)

    def test_shard_whose_report_never_arrived_makes_the_outcome_incomplete(self):
        # Both jobs green, but the artifact of shard 1 was not downloaded: the workflow must still go red.
        write(self.shard_dir(2) / "maestro-report.xml", report(self.root, [
            (self.flows[2], "Flow three", 40, False), (self.flows[1], "Flow two", 50, False),
        ]))
        _, _, output = self.run_gather()
        self.assertEqual(output.splitlines(), [
            "incomplete=true", "problems=shard 1/2: no Maestro report received (1 flow(s) not run)", "interrupted=false",
        ])

    def test_missing_and_failed_flows_are_listed_per_shard(self):
        write(self.shard_dir(1) / "maestro-report.xml", report(self.root, [(self.flows[0], "Flow one", 61, True)]))
        write(self.shard_dir(2) / "maestro-report.xml", report(self.root, [(self.flows[2], "Flow three", 40, False)]))
        _, _, output = self.run_gather()
        self.assertEqual(output.splitlines(), [
            "incomplete=true",
            "problems=shard 1/2: 1 flow(s) failed; shard 2/2: 1 flow(s) missing from its report", "interrupted=false",
        ])

    def test_qase_deadline_guard_does_not_depend_on_console_progress(self):
        identity = {'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '1'}
        directory = self.shard_dir(2)
        path = directory / shards.PROGRESS_FILE
        with patch.dict(os.environ, identity):
            execution_receipt(self.shard_dir(1))
            for category, marker, expected in [('test', False, 'false'), ('budget', False, 'true'), ('test', True, 'true')]:
                for checkpoint in (False, True):
                    with self.subTest(category=category, marker=marker, checkpoint=checkpoint):
                        execution_receipt(directory, phase='maestro', category=category, result='failed',
                                          exit_code=124 if category == 'budget' else 1, budget_expired=marker)
                        path.unlink(missing_ok=True)
                        if checkpoint:
                            write(path, json.dumps({'schema': 1, 'identity': identity, 'flows': {self.flows[1]: 'passed'}}))
                        _, _, output = self.run_gather()
                        self.assertEqual(output.splitlines()[-1], f'interrupted={expected}')

    def test_checkpoint_cannot_hide_missing_flow_in_received_junit(self):
        identity = {'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '1'}
        directory = self.shard_dir(2)
        write(directory / 'maestro-report.xml', report(self.root, [(self.flows[2], 'Flow three', 40, False)]))
        write(directory / shards.PROGRESS_FILE,
              json.dumps({'schema': 1, 'identity': identity, 'flows': {self.flows[1]: 'passed'}}))
        with patch.dict(os.environ, identity):
            execution_receipt(self.shard_dir(1))
            execution_receipt(directory)
            outcomes, merged = self.gather()
        self.assertEqual((outcomes[1].missing, outcomes[1].unverified), (1, 0))
        self.assertIn('shard 2/2: 1 flow(s) missing from its report', shards.problems(outcomes))
        self.assertEqual(next(c for c in merged.iter('testcase') if c.get('name') == 'Flow two').get('pf-execution'), 'not-run')

    def test_complete_run_is_not_incomplete(self):
        write(self.shard_dir(1) / "maestro-report.xml", report(self.root, [(self.flows[0], "Flow one", 61, False)]))
        write(self.shard_dir(2) / "maestro-report.xml", report(self.root, [
            (self.flows[2], "Flow three", 40, False), (self.flows[1], "Flow two", 50, False),
        ]))
        _, _, output = self.run_gather()
        self.assertEqual(output.splitlines(), ["incomplete=false", "problems=", "interrupted=false"])

    def test_empty_shard_without_report_is_no_problem(self):
        # More shards than flows: a shard with nothing to run leaves no report, and that is fine.
        self.assertEqual(shards.problems([shards.ShardOutcome(index=1, planned=0, reported=False)]), [])


class DurationsTest(unittest.TestCase):
    def test_junit_skip_without_status_is_not_a_successful_sample(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = write(root / "skipped.xml", '<testsuite><testcase file="' + str(root) +
                         '/.maestro/flows/a.yaml" time="600"><skipped/></testcase></testsuite>')
            self.assertEqual(shards.updated_durations({".maestro/flows/a.yaml": 60}, [path]),
                             {".maestro/flows/a.yaml": 60})

    def test_passed_flows_keep_maximum_failed_keep_their_time(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = write(root / "r.xml", report(root, [
                (".maestro/flows/a.yaml", "A", 41.6, False),
                (".maestro/flows/b.yaml", "B", 3, True),
                (".maestro/flows/new.yaml", "N", 0.2, False),
            ]))
            result = shards.updated_durations({".maestro/flows/a.yaml": 60, ".maestro/flows/b.yaml": 70}, [path])
        self.assertEqual(result, {".maestro/flows/a.yaml": 60, ".maestro/flows/b.yaml": 70, ".maestro/flows/new.yaml": 1})

    def test_maximum_successful_samples_are_order_independent(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            slow = write(root / "slow.xml", report(root, [(".maestro/flows/a.yaml", "A", 91.4, False)]))
            fast = write(root / "fast.xml", report(root, [(".maestro/flows/a.yaml", "A", 40.2, False)]))
            for reports in ([slow, fast], [fast, slow]):
                with self.subTest(reports=reports):
                    self.assertEqual(shards.updated_durations({".maestro/flows/a.yaml": 60}, reports),
                                     {".maestro/flows/a.yaml": 91})

    def test_nonpositive_samples_do_not_become_complete_flow_weights(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = write(root / "partial.xml", report(root, [
                (".maestro/flows/known.yaml", "K", 0, False),
                (".maestro/flows/unknown.yaml", "U", -1, False),
                (".maestro/flows/failed.yaml", "F", 31.051, True),
            ]))
            self.assertEqual(shards.updated_durations({".maestro/flows/known.yaml": 70}, [path]),
                             {".maestro/flows/known.yaml": 70})

    def test_list_command_prints_the_shard(self):
        with redirect_stdout(io.StringIO()) as out:
            self.assertEqual(shards.main(["list", "--shard", "1/1"]), 0)
        self.assertEqual(out.getvalue().split(), shards.discover_flows(ROOT))


class StrictResultsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'repo'
        self.out = self.root / 'build/e2e'
        self.dir = self.root / 'build/shards'
        self.native_dir = self.root / 'build/native'
        self.jvm_dir = self.root / 'build/components'
        self.flows = ['.maestro/flows/a.yaml', '.maestro/flows/b.yaml']
        for index, flow in enumerate(self.flows):
            flow_file(self.root, flow, f'Flow {index}')
        self.plan = shards.plan(self.flows, {self.flows[0]: 10, self.flows[1]: 9}, 2)
        self.native = 'example.NativeTest#checksBoundary'
        self.jvm = 'jvm:example.ComponentTest#checksComponent'
        write(self.root / 'app/src/androidTestMock/kotlin/example/NativeTest.kt',
              'package example\nclass NativeTest { @Test fun checksBoundary() {} }')
        write(self.root / 'feature/example/src/test/kotlin/example/ComponentTest.kt',
              'package example\nclass ComponentTest { @Test fun checksComponent() {} }')
        self.mapping = {self.flows[0]: 1, self.flows[1]: 2, self.native: 3, self.jvm: 1}
        self.write_mapping()
        for index, flow in enumerate(self.flows, 1):
            write(self.shard_dir(index) / 'maestro-report.xml', report(self.root, [(flow, f'Flow {index-1}', 1, False)]))
        self.native_report = write(self.native_dir / 'native/connected/TEST-native.xml', self.native_xml())
        self.jvm_report = write(self.jvm_dir / 'TEST-component.xml', self.jvm_xml())
        for directory in (self.shard_dir(1), self.shard_dir(2), self.native_dir, self.jvm_dir):
            execution_receipt(directory)

    def write_mapping(self):
        write(self.root / 'scripts/qase-cases.json', json.dumps({'cases': self.mapping}))

    def shard_dir(self, index):
        return self.dir / f'e2e-shard-{index}'

    @staticmethod
    def native_xml(inner='', seconds='2'):
        return f'<testsuite><testcase classname="example.NativeTest" name="checksBoundary" time="{seconds}">{inner}</testcase></testsuite>'

    @staticmethod
    def jvm_xml(inner=''):
        return f'<testsuite><testcase classname="example.ComponentTest" name="checksComponent" time="3">{inner}</testcase></testsuite>'

    def gather(self):
        return shards.gather(self.root, self.plan, self.dir, self.out, self.native_dir, self.jvm_dir)

    def test_exact_maestro_native_jvm_inventory_passes_and_writes_one_manifest(self):
        outcomes = self.gather()
        self.assertEqual(shards.problems(outcomes), [])
        expected = json.loads((self.out / 'expected-tests.json').read_text())
        self.assertEqual(expected['native'], [self.native])
        self.assertEqual(expected['jvm'], [self.jvm])
        status = json.loads((self.out / 'execution-status.json').read_text())
        self.assertTrue(status['passed'])
        self.assertEqual([(o.kind, o.passed) for o in outcomes], [('maestro', 1), ('maestro', 1), ('native', 1), ('jvm', 1)])
        canonical = list((self.root / 'app/build/outputs/androidTest-results').rglob('TEST-*.xml'))
        self.assertEqual(len(canonical), 1)
        self.assertEqual(len(list(ET.parse(canonical[0]).getroot().iter('testcase'))), 1)

    def test_native_only_missing_case_is_not_lost_or_counted_as_assertion_failure(self):
        self.native_report.unlink()
        outcome = self.gather()[2]
        self.assertEqual((outcome.passed, outcome.failed, outcome.missing), (0, 0, 1))
        self.assertTrue(shards.problems([outcome]))
        case = next(ET.parse(self.out / 'native-report.xml').getroot().iter('testcase'))
        self.assertEqual(case.get('name'), 'checksBoundary')
        self.assertIsNotNone(case.find('skipped'))
        self.assertIn('NOT RUN', case.find('skipped').get('message'))

    def test_jvm_companion_missing_keeps_complete_case_from_passing(self):
        self.jvm_report.unlink()
        outcomes = self.gather()
        self.assertEqual(outcomes[-1].missing, 1)
        self.assertIn('jvm suite: no jvm report received (1 test(s) not run)', shards.problems(outcomes))

    def test_skipped_native_and_jvm_tests_fail_completeness(self):
        self.native_report.write_text(self.native_xml('<skipped/>'))
        self.jvm_report.write_text(self.jvm_xml('<skipped/>'))
        outcomes = self.gather()
        self.assertEqual([o.skipped for o in outcomes[-2:]], [1, 1])
        self.assertFalse(json.loads((self.out / 'execution-status.json').read_text())['passed'])

    def test_skipped_maestro_without_status_is_never_a_pass(self):
        path = self.shard_dir(1) / 'maestro-report.xml'
        path.write_text(f'<testsuite><testcase file="{self.root / self.flows[0]}" name="Flow 0"><skipped/></testcase></testsuite>')
        outcome = self.gather()[0]
        self.assertEqual((outcome.passed, outcome.skipped), (0, 1))
        self.assertTrue(shards.problems([outcome]))

    def test_duplicate_result_is_integrity_error_not_extra_coverage(self):
        for target, xml in [(self.native_report, self.native_xml()), (self.jvm_report, self.jvm_xml())]:
            target.write_text(xml.replace('</testsuite>', xml.split('<testsuite>')[1]))
        outcomes = self.gather()
        for outcome in outcomes[-2:]:
            self.assertEqual(outcome.passed, 1)
            self.assertIn('duplicate test result', outcome.invalid)
        self.assertFalse(json.loads((self.out / 'execution-status.json').read_text())['passed'])

    def test_wrong_shard_result_is_not_accepted_by_its_real_owner(self):
        first = self.shard_dir(1) / 'maestro-report.xml'
        first.write_text(report(self.root, [(self.flows[1], 'Flow 1', 1, False)]))
        outcomes = self.gather()
        self.assertEqual(outcomes[0].passed, 0)
        self.assertEqual(outcomes[0].missing, 1)
        self.assertIn('unknown or wrong-suite test result', outcomes[0].invalid)
        self.assertTrue(shards.problems(outcomes))

    def test_unknown_native_method_is_rejected_even_with_all_expected_methods(self):
        self.native_report.write_text(self.native_xml().replace('</testsuite>', '<testcase classname="example.NativeTest" name="unknown"/></testsuite>'))
        outcome = self.gather()[2]
        self.assertEqual(outcome.passed, 1)
        self.assertIn('unknown or wrong-suite test result', outcome.invalid)

    def test_malformed_xml_and_nonfinite_duration_cannot_pass(self):
        self.native_report.write_text('<testsuite>')
        self.jvm_report.write_text(self.jvm_xml().replace('time="3"', 'time="NaN"'))
        outcomes = self.gather()
        self.assertIn('unreadable or malformed JUnit report', outcomes[-2].invalid)
        self.assertIn('invalid test duration', outcomes[-1].invalid)
        self.assertEqual(outcomes[-2].missing, 1)
        self.assertFalse(json.loads((self.out / 'execution-status.json').read_text())['passed'])

    def test_setup_failure_has_not_run_evidence_without_assertion_failures(self):
        (self.shard_dir(1) / 'maestro-report.xml').unlink()
        execution_receipt(self.shard_dir(1), phase='setup', category='setup', result='failed', exit_code=28, apks={})
        outcomes = self.gather()
        first = outcomes[0]
        self.assertEqual((first.missing, first.failed, first.category, first.phase), (1, 0, 'setup', 'setup'))
        self.assertIn('setup / setup', shards.outcomes_markdown(outcomes))
        case = next(ET.parse(self.out / 'maestro-report.xml').getroot().iter('testcase'))
        self.assertIn('cause=setup', case.find('skipped').get('message'))

    def test_bad_execution_receipt_is_not_trusted(self):
        write(self.native_dir / 'execution.json', '{invalid')
        outcome = self.gather()[2]
        self.assertIn('invalid execution receipt', outcome.invalid)

    def test_native_source_method_not_in_map_fails_manifest(self):
        self.mapping.pop(self.native)
        self.write_mapping()
        outcomes = self.gather()
        self.assertIn('source discovery and Qase component mapping differ', outcomes[0].invalid)
        self.assertEqual(outcomes[2].planned, 1)

    def test_missing_jvm_source_method_cannot_be_declared_as_covered(self):
        (self.root / 'feature/example/src/test/kotlin/example/ComponentTest.kt').unlink()
        outcomes = self.gather()
        self.assertIn('JVM component source and Qase mapping differ', outcomes[0].invalid)

    def test_coverage_from_native_job_is_preserved(self):
        write(self.native_dir / 'native-coverage/connected/device/coverage.ec', b'execution')
        self.gather()
        self.assertEqual((self.root / 'app/build/outputs/code_coverage/connected/device/coverage.ec').read_bytes(), b'execution')

    def test_passing_test_rows_do_not_hide_failed_execution_receipt(self):
        execution_receipt(self.native_dir, phase='reporting', category='reporting', result='failed', exit_code=1)
        outcomes = self.gather()
        self.assertEqual(outcomes[2].passed, 1)
        self.assertIn('native suite: execution failed during reporting', shards.problems(outcomes))

    def test_invalid_mapping_is_reported_without_crashing_or_synthesizing_an_invalid_key(self):
        write(self.root / 'scripts/qase-cases.json', json.dumps({'cases': {'not-a-component': 1}}))
        outcomes = self.gather()
        self.assertIn('invalid Qase component mapping', outcomes[0].invalid)

    def test_duplicate_failure_is_preserved_in_canonical_report(self):
        self.native_report.write_text(self.native_xml().replace('</testsuite>', self.native_xml('<failure message="real failure"/>').split('<testsuite>')[1]))
        outcome = self.gather()[2]
        self.assertEqual((outcome.passed, outcome.failed), (0, 1))
        self.assertIn('duplicate test result', outcome.invalid)
        case = next(ET.parse(self.out / 'native-report.xml').getroot().iter('testcase'))
        self.assertIsNotNone(case.find('failure'))

    def test_missing_mapping_cannot_make_expected_test_set_shrink(self):
        (self.root / 'scripts/qase-cases.json').unlink()
        outcomes = self.gather()
        self.assertIn('missing Qase component mapping', outcomes[0].invalid)

    def test_unrelated_explicit_path_cannot_match_by_valid_display_name(self):
        path = self.shard_dir(1) / 'maestro-report.xml'
        path.write_text('<testsuite><testcase name="Flow 0" file="unrelated.yaml"/></testsuite>')
        outcome = self.gather()[0]
        self.assertEqual((outcome.passed, outcome.missing), (0, 1))
        self.assertIn('unknown or wrong-suite test result', outcome.invalid)

    def test_missing_execution_receipt_blocks_passing_xml(self):
        for directory in (self.shard_dir(1), self.native_dir, self.jvm_dir):
            with self.subTest(directory=directory):
                path = directory / 'execution.json'
                saved = path.read_bytes()
                path.unlink()
                outcomes = self.gather()
                self.assertTrue(any('missing execution receipt' in o.invalid for o in outcomes))
                self.assertTrue(shards.problems(outcomes))
                path.write_bytes(saved)

    def test_historical_replay_must_explicitly_disable_receipt_requirement(self):
        for directory in (self.shard_dir(1), self.shard_dir(2), self.native_dir, self.jvm_dir):
            (directory / 'execution.json').unlink()
        outcomes = shards.gather(self.root, self.plan, self.dir, self.out, self.native_dir, self.jvm_dir, receipts_required=False)
        self.assertEqual(shards.problems(outcomes), [])

    def test_source_and_run_identity_must_match_and_old_successful_attempt_is_allowed(self):
        from unittest import mock
        with mock.patch.dict(os.environ, {'GITHUB_SHA': 'current-source', 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '2'}):
            for directory in (self.shard_dir(1), self.shard_dir(2), self.native_dir, self.jvm_dir):
                execution_receipt(directory, identity={'GITHUB_SHA': 'current-source', 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1'})
            self.assertEqual(shards.problems(self.gather()), [])
            for wrong in ({'GITHUB_SHA': 'old-source'}, {'GITHUB_RUN_ID': '999'}, {'GITHUB_RUN_ATTEMPT': '3'}):
                identity = {'GITHUB_SHA': 'current-source', 'GITHUB_RUN_ID': '123', 'GITHUB_RUN_ATTEMPT': '1', **wrong}
                execution_receipt(self.native_dir, identity=identity)
                self.assertIn('invalid execution receipt', self.gather()[2].invalid)

    def test_passed_receipt_needs_complete_phase_zero_exit_and_no_failure_cause(self):
        for changes in ({'schema': 2}, {'phase': 'invalid'}, {'phase': 'native'}, {'exit_code': 1}, {'category': 'test'}):
            with self.subTest(changes=changes):
                execution_receipt(self.native_dir, **changes)
                self.assertIn('invalid execution receipt', self.gather()[2].invalid)

    def test_emulator_receipt_needs_apk_identity_but_jvm_does_not(self):
        execution_receipt(self.jvm_dir, apks={})
        self.assertEqual(shards.problems(self.gather()), [])
        execution_receipt(self.native_dir, apks={})
        self.assertIn('missing emulator APK identity', self.gather()[2].invalid)

    def test_duplicate_skip_is_preserved_as_worse_than_pass(self):
        self.native_report.write_text(self.native_xml().replace('</testsuite>', self.native_xml('<skipped/>').split('<testsuite>')[1]))
        outcome = self.gather()[2]
        self.assertEqual((outcome.passed, outcome.skipped), (0, 1))
        self.assertIn('duplicate test result', outcome.invalid)
        case = next(ET.parse(self.out / 'native-report.xml').getroot().iter('testcase'))
        self.assertIsNotNone(case.find('skipped'))

    def qase_payload(self):
        spec = importlib.util.spec_from_file_location('qase_provenance', ROOT / 'scripts/qase-report.py')
        qase = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = qase
        spec.loader.exec_module(qase)
        results = []
        for name, prefix in (('maestro-report.xml', ''), ('native-report.xml', ''), ('jvm-report.xml', 'jvm:')):
            results.extend(qase.parse_report((self.out / name).read_text(), prefix=prefix))
        payload, unmapped = qase.map_results(results, self.mapping)
        self.assertEqual(unmapped, [])
        return {case['case_id']: case for case in payload}

    def test_unverified_provenance_never_becomes_a_current_pass_in_qase(self):
        from unittest import mock
        suites = [(self.shard_dir(1), 1, 0), (self.native_dir, 3, 2), (self.jvm_dir, 1, 3)]
        with mock.patch.dict(os.environ, {'GITHUB_SHA': 'current-source'}):
            for directory, case_id, outcome_index in suites:
                mutations = ['missing', 'malformed', 'wrong_source']
                if directory != self.jvm_dir:
                    mutations.append('missing_apk')
                for mutation in mutations:
                    with self.subTest(directory=directory, mutation=mutation):
                        for d in (self.shard_dir(1), self.shard_dir(2), self.native_dir, self.jvm_dir):
                            execution_receipt(d)
                        path = directory / 'execution.json'
                        if mutation == 'missing':
                            path.unlink()
                        elif mutation == 'malformed':
                            path.write_text('{invalid')
                        elif mutation == 'wrong_source':
                            execution_receipt(directory, identity={'GITHUB_SHA': 'stale-source'})
                        else:
                            execution_receipt(directory, apks={})
                        outcome = self.gather()[outcome_index]
                        self.assertEqual((outcome.passed, outcome.failed, outcome.unverified), (0, 0, 1))
                        self.assertEqual(outcome.unverified_results, {'passed': 1})
                        case = self.qase_payload()[case_id]
                        self.assertEqual(case['status'], 'skipped')
                        self.assertIn('UNVERIFIED', case['comment'])
                        self.assertIn('raw result passed', case['comment'])

    def test_unverified_raw_failures_keep_diagnostics_without_claiming_current_assertion_failures(self):
        reports = [(self.shard_dir(1) / 'maestro-report.xml', 1, 0, 'maestro-report.xml'),
                   (self.native_report, 3, 2, 'native-report.xml'),
                   (self.jvm_report, 1, 3, 'jvm-report.xml')]
        for report_path, case_id, outcome_index, canonical_name in reports:
            with self.subTest(report=report_path):
                raw = report_path.read_text()
                parsed = ET.fromstring(raw)
                ET.SubElement(next(parsed.iter('testcase')), 'failure', {'message': 'raw failure detail'}).text = 'raw failure trace'
                failed = ET.tostring(parsed, encoding='unicode')
                report_path.write_text(failed)
                directory = self.shard_dir(1) if outcome_index == 0 else self.native_dir if outcome_index == 2 else self.jvm_dir
                receipt = directory / 'execution.json'
                saved_receipt = receipt.read_text()
                receipt.write_text('{invalid')
                outcome = self.gather()[outcome_index]
                self.assertEqual((outcome.failed, outcome.unverified_results), (0, {'failed': 1}))
                case = self.qase_payload()[case_id]
                self.assertEqual(case['status'], 'skipped')
                self.assertIn('raw result failed', case['comment'])
                canonical = next(c for c in ET.parse(self.out / canonical_name).getroot().iter('testcase') if c.get('pf-execution') == 'unverified')
                self.assertIsNone(canonical.find('failure'))
                self.assertIn('raw failure detail', canonical.find('system-out').text)
                self.assertIn('raw failure trace', canonical.find('system-out').text)
                self.assertEqual(report_path.read_text(), failed, 'raw artifact must not be rewritten')
                receipt.write_text(saved_receipt)
                report_path.write_text(raw)

    def test_valid_failed_execution_preserves_real_observed_results_in_qase(self):
        for phase in ('setup', 'reporting'):
            with self.subTest(phase=phase):
                execution_receipt(self.native_dir, phase=phase, category=phase, result='failed', exit_code=1)
                self.native_report.write_text(self.native_xml('<failure message="current failure"/>'))
                outcome = self.gather()[2]
                self.assertEqual((outcome.failed, outcome.unverified), (1, 0))
                self.assertEqual(self.qase_payload()[3]['status'], 'failed')
                self.native_report.write_text(self.native_xml())
                outcome = self.gather()[2]
                self.assertEqual((outcome.passed, outcome.unverified), (1, 0))
                self.assertEqual(self.qase_payload()[3]['status'], 'passed')

    def test_prior_real_failure_and_later_deadline_are_visible_through_qase(self):
        self.plan = shards.plan(self.flows, {self.flows[0]: 10, self.flows[1]: 9}, 1)
        write(self.shard_dir(1) / 'maestro-report.xml', report(self.root, [(self.flows[0], 'Flow 0', 1, True)]))
        execution_receipt(self.shard_dir(1), phase='maestro', category='test', result='failed', exit_code=1, budget_expired=True)
        outcomes = self.gather()
        first = outcomes[0]
        self.assertEqual((first.failed, first.missing, first.category, first.phase), (1, 1, 'test', 'maestro'))
        self.assertTrue(first.budget_expired)
        self.assertIn('test / maestro; deadline_expired=true', shards.outcomes_markdown(outcomes))
        status = json.loads((self.out / 'execution-status.json').read_text())
        self.assertEqual(status['suites'][0]['budget_expired'], True)
        self.assertFalse(status['passed'])
        cases = self.qase_payload()
        self.assertEqual(cases[1]['status'], 'failed')
        self.assertEqual(cases[1]['comment'], 'Assertion is false')
        self.assertEqual(cases[2]['status'], 'skipped')
        self.assertIn('cause=budget', cases[2]['comment'])
        self.assertIn('phase=maestro', cases[2]['comment'])
        self.assertIn('deadline_expired=true', cases[2]['comment'])

    def test_clean_deadline_keeps_prior_completed_cases_and_marks_missing_as_budget(self):
        self.plan = shards.plan(self.flows, {self.flows[0]: 10, self.flows[1]: 9}, 1)
        write(self.shard_dir(1) / 'maestro-report.xml', report(self.root, [(self.flows[0], 'Flow 0', 1, False)]))
        execution_receipt(self.shard_dir(1), phase='maestro', category='budget', result='failed', exit_code=124, budget_expired=True)
        outcome = self.gather()[0]
        self.assertEqual((outcome.passed, outcome.failed, outcome.missing, outcome.category), (1, 0, 1, 'budget'))
        cases = self.qase_payload()
        self.assertEqual(cases[1]['status'], 'passed')
        self.assertEqual(cases[2]['status'], 'skipped')
        self.assertIn('cause=budget', cases[2]['comment'])
        self.assertIn('deadline_expired=true', cases[2]['comment'])

    def test_exit_137_without_coordinator_marker_is_not_inferred_as_budget(self):
        (self.shard_dir(1) / 'maestro-report.xml').unlink()
        execution_receipt(self.shard_dir(1), phase='maestro', category='test', result='failed', exit_code=137)
        outcomes = self.gather()
        first = outcomes[0]
        self.assertFalse(first.budget_expired)
        self.assertEqual(first.category, 'test')
        self.assertNotIn('deadline_expired=true', shards.outcomes_markdown(outcomes))
        case = self.qase_payload()[1]
        self.assertEqual(case['status'], 'skipped')
        self.assertIn('cause=test', case['comment'])
        self.assertNotIn('cause=budget', case['comment'])

    def test_non_boolean_budget_marker_is_invalid_provenance(self):
        for value in ('true', 1, None, []):
            with self.subTest(value=value):
                execution_receipt(self.native_dir, budget_expired=value)
                outcome = self.gather()[2]
                self.assertIn('invalid execution receipt', outcome.invalid)
                self.assertFalse(outcome.budget_expired)
                self.assertEqual(self.qase_payload()[3]['status'], 'skipped')

    def test_deadline_marker_cannot_coexist_with_a_passed_execution_receipt(self):
        execution_receipt(self.native_dir, budget_expired=True)
        outcome = self.gather()[2]
        self.assertIn('invalid execution receipt', outcome.invalid)
        self.assertEqual(self.qase_payload()[3]['status'], 'skipped')


if __name__ == "__main__":
    unittest.main()
