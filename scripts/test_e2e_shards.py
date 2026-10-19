"""Tests of scripts/e2e-shards.py: the split of the flows, merging the shards' reports, coverage data and durations."""

from __future__ import annotations

import importlib.util
import io
import json
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from contextlib import redirect_stdout
from pathlib import Path

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

    def test_real_flows_split_into_four_shards(self):
        flows = shards.discover_flows(ROOT)
        self.assertTrue(flows)
        self.assertTrue(all(f.startswith(".maestro/flows/") for f in flows))
        self.assertFalse(any("/subflows/" in f for f in flows))
        planned = shards.plan(flows, shards.load_durations(shards.DURATIONS_FILE), 4)
        self.assertEqual(sorted(f for s in planned for f in s.flows), flows)

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
        self.durations = {self.flows[0]: 60, self.flows[1]: 50, self.flows[2]: 40}
        # 2 shards, no native weight: [one] and [two, three].
        self.plan = shards.plan(self.flows, self.durations, 2, native_seconds=0)
        self.assertEqual([s.flows for s in self.plan], [[self.flows[0]], [self.flows[1], self.flows[2]]])
        self.dir = Path(self.tmp.name) / "shards"

    def tearDown(self):
        self.tmp.cleanup()

    def shard_dir(self, index: int) -> Path:
        return self.dir / f"e2e-shard-{index}"

    def gather(self):
        out = self.root / "build" / "e2e"
        outcomes = shards.gather(self.root, self.plan, self.dir, out)
        merged = ET.parse(out / "maestro-report.xml").getroot()
        return outcomes, merged

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

    def test_shard_without_report_turns_its_flows_into_failures(self):
        write(self.shard_dir(1) / "maestro-report.xml", report(self.root, [(self.flows[0], "Flow one", 61, False)]))
        # Shard 2 uploaded only a logcat: the emulator never came up.
        write(self.shard_dir(2) / "logcat.txt", "boot failed")
        outcomes, merged = self.gather()
        self.assertFalse(outcomes[1].reported)
        self.assertEqual(outcomes[1].missing, 2)
        failed = [c for c in merged.iter("testcase") if c.find("failure") is not None]
        self.assertEqual(sorted(shards.flow_of(c) for c in failed), sorted([self.flows[1], self.flows[2]]))
        self.assertIn("shard 2/2 left no Maestro report", failed[0].find("failure").get("message"))
        # Named as Maestro names them, so the summary and Qase show the flow, not a file name.
        self.assertEqual({c.get("name") for c in failed}, {"Flow two", "Flow three"})

    def test_missing_shard_folder_and_broken_report_count_as_no_report(self):
        write(self.shard_dir(1) / "maestro-report.xml", "<testsuites><testsuite>")  # cut off mid-write
        outcomes, merged = self.gather()
        self.assertEqual([o.reported for o in outcomes], [False, False])
        self.assertEqual(len([c for c in merged.iter("testcase") if c.find("failure") is not None]), 3)

    def test_flow_missing_from_a_report_is_a_failure(self):
        write(self.shard_dir(1) / "maestro-report.xml", report(self.root, [(self.flows[0], "Flow one", 61, False)]))
        write(self.shard_dir(2) / "maestro-report.xml", report(self.root, [(self.flows[2], "Flow three", 40, False)]))
        outcomes, merged = self.gather()
        self.assertEqual((outcomes[1].passed, outcomes[1].missing), (1, 1))
        (lost,) = [c for c in merged.iter("testcase") if c.find("failure") is not None]
        self.assertEqual(shards.flow_of(lost), self.flows[1])
        self.assertIn("not in the report of shard 2/2", lost.find("failure").get("message"))

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
            [(self.flows[0], "passed"), (self.flows[1], "failed"), (self.flows[2], "failed")],
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
        self.assertIn("| 2/2 | 2 | 2 | 0 | 0 | 1.5 min |", summary)
        self.assertIn("- shard 1/2: no Maestro report received (1 flow(s) not run)", summary)

    def test_shard_whose_report_never_arrived_makes_the_outcome_incomplete(self):
        # Both jobs green, but the artifact of shard 1 was not downloaded: the workflow must still go red.
        write(self.shard_dir(2) / "maestro-report.xml", report(self.root, [
            (self.flows[2], "Flow three", 40, False), (self.flows[1], "Flow two", 50, False),
        ]))
        _, _, output = self.run_gather()
        self.assertEqual(output.splitlines(), [
            "incomplete=true", "problems=shard 1/2: no Maestro report received (1 flow(s) not run)",
        ])

    def test_missing_and_failed_flows_are_listed_per_shard(self):
        write(self.shard_dir(1) / "maestro-report.xml", report(self.root, [(self.flows[0], "Flow one", 61, True)]))
        write(self.shard_dir(2) / "maestro-report.xml", report(self.root, [(self.flows[2], "Flow three", 40, False)]))
        _, _, output = self.run_gather()
        self.assertEqual(output.splitlines(), [
            "incomplete=true",
            "problems=shard 1/2: 1 flow(s) failed; shard 2/2: 1 flow(s) missing from its report",
        ])

    def test_complete_run_is_not_incomplete(self):
        write(self.shard_dir(1) / "maestro-report.xml", report(self.root, [(self.flows[0], "Flow one", 61, False)]))
        write(self.shard_dir(2) / "maestro-report.xml", report(self.root, [
            (self.flows[2], "Flow three", 40, False), (self.flows[1], "Flow two", 50, False),
        ]))
        _, _, output = self.run_gather()
        self.assertEqual(output.splitlines(), ["incomplete=false", "problems="])

    def test_empty_shard_without_report_is_no_problem(self):
        # More shards than flows: a shard with nothing to run leaves no report, and that is fine.
        self.assertEqual(shards.problems([shards.ShardOutcome(index=1, planned=0, reported=False)]), [])


class DurationsTest(unittest.TestCase):
    def test_passed_flows_update_failed_keep_their_time(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path = write(root / "r.xml", report(root, [
                (".maestro/flows/a.yaml", "A", 41.6, False),
                (".maestro/flows/b.yaml", "B", 3, True),
                (".maestro/flows/new.yaml", "N", 0.2, False),
            ]))
            result = shards.updated_durations({".maestro/flows/a.yaml": 60, ".maestro/flows/b.yaml": 70}, [path])
        self.assertEqual(result, {".maestro/flows/a.yaml": 42, ".maestro/flows/b.yaml": 70, ".maestro/flows/new.yaml": 1})

    def test_list_command_prints_the_shard(self):
        with redirect_stdout(io.StringIO()) as out:
            self.assertEqual(shards.main(["list", "--shard", "1/1"]), 0)
        self.assertEqual(out.getvalue().split(), shards.discover_flows(ROOT))


if __name__ == "__main__":
    unittest.main()
