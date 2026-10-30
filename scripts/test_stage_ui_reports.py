"""Aggregation guards use synthetic public reports only; no network, device or Gradle."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import uuid
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location("stage_reports", Path(__file__).with_name("stage-ui-reports.py"))
reports = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reports)
MANIFEST = {"provider_scenarios": {"sha256": "e" * 64}}


def expected_case(identifier="qase-8", layer="maestro", status="ready"):
    source = ".maestro/flows/operations/feed_and_detail.yaml"
    qase = 8
    if layer == "native":
        source, qase = "ru.finassist.pf.StatementPickerTest#uploadsTheDocumentReturnedBySystemPicker", 16
    if layer == "checkpoint":
        source, qase = ".maestro-stage/flows/assistant/process_death.yaml", 60
    return {"id": identifier, "source": source, "qase_id": qase, "layer": layer, "status": status,
            "provenance": "client-injected" if layer == "checkpoint" else "real-stage", "blocked_reason": "server_capability_missing",
            "required_counts": {"real_ask_requests": 2, "real_ask_consent_required": 1, "real_ask_accepted": 1, "ask_response_held": 1} if layer == "checkpoint" else {}}


def result(case):
    row = {key: case[key] for key in ("id", "source", "qase_id", "provenance")}
    row.update(outcome="passed", seconds=1, client_evidence={"has_client_injections": case["provenance"] == "client-injected",
                                                           "counts": {"real_requests": 7}})
    row["client_evidence"]["counts"].update(case.get("required_counts", {}))
    if case["layer"] == "checkpoint":
        row["client_evidence"]["counts"].update(real_ask_requests=2, real_ask_consent_required=1, real_ask_accepted=1, ask_response_held=1, ask_response_released=0)
        row["checkpoint"] = {"process_disappeared": True, "accepted_before_death": 1,
                             "requests_before_death": 2, "consent_refusals_before_death": 1,
                             "response_delivered_before_death": False, "server_restarted": False}
    return row


def worker(rows):
    return {"schema_version": 1, "run_id": str(uuid.uuid4()), "environment": reports.ENVIRONMENT,
            "source_commit": "a" * 40, "source_dirty": False, "apk_sha256": "b" * 64,
            "provider": "fake-phone/fake-LLM", "provider_scenarios_expected_sha256": "e" * 64,
            "provider_scenarios_runtime_sha256_verified": False,
            "full_inventory": False, "full_acceptance": False, "results": rows,
            "finished_at": "2026-10-30T15:00:00+00:00",
            "cleanup": [{"account": uuid.uuid4().hex[:12], "outcome": "deleted"}]}


class AggregateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.case = expected_case()
        self.native = expected_case("qase-16", "native")
        self.checkpoint = expected_case("process-death-assistant", "checkpoint")
        self.expected = [self.case, self.native, self.checkpoint]

    def tearDown(self):
        self.temp.cleanup()

    def merge(self, data, expected=None):
        directories = []
        for index, report in enumerate(data):
            directory = self.root / f"worker{index}"
            directory.mkdir(exist_ok=True)
            (directory / "report.json").write_text(json.dumps(report))
            directories.append(directory)
        return reports.aggregate(directories, self.root / "out", MANIFEST, expected or self.expected)

    def valid(self):
        return [worker([result(self.case), result(self.native)]), worker([result(self.checkpoint)])]

    def test_complete_partial_workers_can_form_one_complete_acceptance(self):
        data = self.valid()
        data[0]["results"][0]["artifacts"] = "build/stage-ui/cases/qase-8"
        combined = self.merge(data)
        self.assertTrue(combined["full_inventory"])
        self.assertTrue(combined["full_acceptance"])
        self.assertFalse(combined["provider_scenarios_runtime_sha256_verified"])
        self.assertEqual(combined["workers"][0]["run_id"], data[0]["run_id"])
        self.assertEqual(combined["results"][0]["client_evidence"], data[0]["results"][0]["client_evidence"])
        self.assertEqual(combined["results"][0]["artifacts"], "workers/1/cases/qase-8")

    def test_missing_duplicate_and_unknown_cases_fail_without_disappearing(self):
        for kind in ("missing", "duplicate", "unknown"):
            with self.subTest(kind=kind):
                data = self.valid()
                if kind == "missing":data[1]["results"] = []
                if kind == "duplicate":data[1]["results"].append(copy.deepcopy(data[0]["results"][0]))
                if kind == "unknown":data[1]["results"].append({"id": "foreign-case", "outcome": "passed"})
                combined = self.merge(data)
                self.assertEqual(len(combined["results"]), 3)
                self.assertFalse(combined["full_inventory"])
                self.assertFalse(combined["full_acceptance"])
                self.assertTrue(combined["guard_errors"])

    def test_worker_metadata_cannot_mix_stale_dirty_apk_or_foreign_environment(self):
        for key, value in (("source_commit", "c" * 40), ("source_commit", "invalid"), ("source_dirty", True),
                           ("apk_sha256", "d" * 64), ("environment", "https://prod.example/"),
                           ("provider_scenarios_expected_sha256", "f" * 64), ("environment_failure", "boot_failed")):
            with self.subTest(key=key, value=value):
                data = self.valid();data[1][key] = value
                combined = self.merge(data)
                self.assertFalse(combined["full_acceptance"])
                self.assertEqual(combined["results"][2]["outcome"], "failed")

    def test_native_source_qase_and_provenance_are_bound_to_manifest(self):
        for key, value in (("source", "ru.foreign.OtherTest#uploadsTheDocumentReturnedBySystemPicker"),
                           ("qase_id", 8), ("provenance", "client-injected")):
            with self.subTest(key=key):
                data=self.valid();data[0]["results"][1][key] = value
                combined=self.merge(data)
                self.assertFalse(combined["full_acceptance"])
                self.assertEqual(combined["results"][1]["source"], self.native["source"])
                self.assertEqual(combined["results"][1]["outcome"], "failed")

    def test_pass_requires_real_counter_and_declared_injection(self):
        for evidence in (None, {"has_client_injections": False, "counts": {}},
                         {"has_client_injections": False, "counts": {"real_requests": True}},
                         {"has_client_injections": True, "counts": {"real_requests": 1}}):
            with self.subTest(evidence=evidence):
                data=self.valid();data[0]["results"][0]["client_evidence"] = evidence
                combined=self.merge(data)
                self.assertFalse(combined["full_acceptance"])
                self.assertEqual(combined["results"][0]["outcome"], "failed")

    def test_manifest_required_observation_cannot_be_replaced_by_generic_injection(self):
        for name in ("unknown_kind_requests", "import_response_transform"):
            for observed in (None, 0, True, 1, 2):
                with self.subTest(name=name, observed=observed):
                    case=expected_case()
                    case.update(provenance="client-injected", required_counts={name: 2})
                    row=result(case)
                    row["client_evidence"]={"has_client_injections": True,
                                            "counts": {"real_requests": 10, "unrelated_transform": 1}}
                    if observed is not None:row["client_evidence"]["counts"][name]=observed
                    combined=self.merge([worker([row])], [case])
                    self.assertEqual(combined["full_acceptance"], type(observed) is int and observed >= 2)
                    if observed is None or type(observed) is int and observed < 2:
                        self.assertIn("required_count_not_observed:"+name, combined["results"][0]["validation_errors"])

    def test_invalid_required_observation_template_fails_closed(self):
        for required in (None, [], {"unknown_kind_requests": True}, {"import_response_transform": -1}):
            case=expected_case();case["required_counts"]=required
            row=result(expected_case())
            combined=self.merge([worker([row])], [case])
            self.assertFalse(combined["full_acceptance"])
            self.assertIn("invalid_required_counts", combined["results"][0]["validation_errors"])

    def test_every_ready_injected_case_has_complete_observation_contract(self):
        _, cases=reports.load_manifest()
        for case in cases:
            if case["status"] != "ready" or case["provenance"] != "client-injected":continue
            with self.subTest(case=case["id"]):
                self.assertEqual(reports.observation_contract_problems(case), [])
                without=copy.deepcopy(case);without.pop("required_counts")
                self.assertTrue(reports.observation_contract_problems(without))
                unobserved=result(case)
                unobserved["client_evidence"]={"has_client_injections": True, "counts": {"real_requests": 7}}
                self.assertTrue(reports.case_problems(unobserved, case))
                for name in case["required_counts"]:
                    weak=copy.deepcopy(case);weak["required_counts"][name]=0
                    self.assertTrue(reports.observation_contract_problems(weak), (case["id"],name))

    def test_three_stream_drop_phases_require_six_actual_drops(self):
        _, cases=reports.load_manifest()
        case=next(case for case in cases if case["qase_id"]==43)
        self.assertEqual(case["required_counts"]["stream_drop"], 6)
        row=result(case)
        row["client_evidence"]["counts"]["stream_drop"]=5
        self.assertIn("required_count_not_observed:stream_drop", reports.case_problems(row, case))
        weakened=copy.deepcopy(case);weakened["required_counts"]["stream_drop"]=1
        self.assertIn("observation_contract_too_weak:stream_drop", reports.case_problems(result(weakened), weakened))

    def test_offline_retry_requires_two_real_accepted_questions(self):
        _, cases=reports.load_manifest()
        case=next(case for case in cases if case["qase_id"]==20)
        self.assertEqual(case["required_counts"]["real_ask_accepted"], 2)
        self.assertEqual(reports.observation_contract_problems(case), [])
        for count in (0, 1):
            with self.subTest(count=count):
                weakened=copy.deepcopy(case);weakened["required_counts"]["real_ask_accepted"]=count
                self.assertIn("observation_contract_too_weak:real_ask_accepted", reports.observation_contract_problems(weakened))
                row=result(case);row["client_evidence"]["counts"]["real_ask_accepted"]=count
                combined=self.merge([worker([row])], [case])
                self.assertFalse(combined["full_acceptance"])
                self.assertIn("required_count_not_observed:real_ask_accepted", combined["results"][0]["validation_errors"])
        self.assertTrue(self.merge([worker([result(case)])], [case])["full_acceptance"])

    def test_timezone_patch_is_real_stage_and_flag_controls_are_not(self):
        _, cases=reports.load_manifest()
        timezone=next(case for case in cases if case["qase_id"]==27)
        self.assertEqual(timezone["provenance"], "real-stage")
        self.assertEqual(reports.observation_contract_problems(timezone), [])
        flags=copy.deepcopy(next(case for case in cases if case["qase_id"]==11))
        self.assertEqual(flags["required_counts"]["flag_override"], 4)
        flags["provenance"]="real-stage"
        self.assertIn("injection_controls_declared_real_stage", reports.observation_contract_problems(flags))
        self.assertEqual(next(case for case in cases if case["qase_id"]==51)["required_counts"]["pending_upload_seed"], 1)

    def test_process_death_requires_real_death_and_no_second_post(self):
        for mutation in ("extra_post", "missing_sent_counter", "missing_consent_refusal", "extra_consent_refusal", "invalid_counts", "no_checkpoint", "released", "not_dead", "wrong_pre_death_requests", "wrong_pre_death_refusals"):
            with self.subTest(mutation=mutation):
                data=self.valid();row=data[1]["results"][0]
                if mutation == "extra_post":row["client_evidence"]["counts"]["real_ask_requests"] = 3
                if mutation == "missing_sent_counter":row["client_evidence"]["counts"].pop("real_ask_requests")
                if mutation == "missing_consent_refusal":row["client_evidence"]["counts"].pop("real_ask_consent_required")
                if mutation == "extra_consent_refusal":row["client_evidence"]["counts"]["real_ask_consent_required"]=2
                if mutation == "wrong_pre_death_requests":row["checkpoint"]["requests_before_death"]=1
                if mutation == "wrong_pre_death_refusals":row["checkpoint"]["consent_refusals_before_death"]=0
                if mutation == "invalid_counts":row["client_evidence"]["counts"] = None
                if mutation == "no_checkpoint":row.pop("checkpoint")
                if mutation == "released":row["client_evidence"]["counts"]["ask_response_released"] = 1
                if mutation == "not_dead":row["checkpoint"]["process_disappeared"] = False
                self.assertFalse(self.merge(data)["full_acceptance"])

    def test_blocked_and_not_run_are_failures_never_skipped(self):
        blocked=expected_case(status="blocked")
        data=[worker([result(blocked)])]
        combined=self.merge(data, [blocked])
        self.assertFalse(combined["full_acceptance"])
        data[0]["results"][0].update(outcome="blocked", reason="backend_missing")
        combined=self.merge(data, [blocked])
        self.assertEqual(combined["results"][0]["outcome"], "blocked")
        xml=ET.parse(self.root/"out/report.xml").getroot()
        self.assertEqual(xml.get("skipped"), "0")
        self.assertTrue(list(xml.iter("failure")))
        self.assertFalse(list(xml.iter("skipped")))

    def test_cleanup_and_reused_run_ids_block_acceptance(self):
        for mutation in ("retained", "empty_cleanup", "duplicate_run", "reused_account", "unfinished"):
            data=self.valid()
            if mutation == "retained":data[1]["cleanup"][0]["outcome"] = "retained"
            if mutation == "empty_cleanup":data[1]["cleanup"] = []
            if mutation == "duplicate_run":data[1]["run_id"] = data[0]["run_id"]
            if mutation == "reused_account":data[1]["cleanup"] = copy.deepcopy(data[0]["cleanup"])
            if mutation == "unfinished":data[1].pop("finished_at")
            combined=self.merge(data)
            self.assertFalse(combined["full_acceptance"])

    def test_canonical_junit_roundtrips_both_maestro_trees_and_native(self):
        self.merge(self.valid())
        spec=importlib.util.spec_from_file_location("qase_report", reports.ROOT/"scripts/qase-report.py")
        qase=importlib.util.module_from_spec(spec)
        import sys
        sys.modules[spec.name]=qase
        spec.loader.exec_module(qase)
        parsed=qase.parse_report((self.root/"out/report.xml").read_text())
        payload, unmapped=qase.map_results(parsed, {case["source"]:case["qase_id"] for case in self.expected})
        self.assertEqual(unmapped, [])
        self.assertEqual({row["case_id"] for row in payload}, {8,16,60})

    def test_copy_only_public_artifacts_and_reject_symlinks(self):
        directory=self.root/"worker0";directory.mkdir()
        (directory/"cases").mkdir();(directory/"cases/failure.png").write_bytes(b"png")
        (directory/"secret.db").write_bytes(b"private")
        (directory/"private").mkdir();(directory/"private/session.json").write_text('secret')
        data=self.valid();combined=self.merge(data)
        self.assertTrue((self.root/"out/workers/1/cases/failure.png").is_file())
        self.assertFalse((self.root/"out/workers/1/secret.db").exists())
        self.assertFalse((self.root/"out/workers/1/private/session.json").exists())
        (directory/"linked.json").symlink_to(directory/"report.json")
        self.assertFalse(self.merge(data)["full_acceptance"])
        self.assertFalse((self.root/"out/workers/1/linked.json").exists())

    def test_no_output_inside_input_or_symlink_output(self):
        directory=self.root/"worker";directory.mkdir()
        with self.assertRaisesRegex(ValueError, "overlaps"):
            reports.aggregate([directory], directory/"aggregate", MANIFEST, self.expected)
        link=self.root/"output-link";link.symlink_to(directory, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlink"):
            reports.aggregate([directory], link, MANIFEST, self.expected)

    def test_actual_complete_manifest_keeps_all_57_including_blocked_cases(self):
        manifest, expected = reports.load_manifest()
        self.assertEqual(len(expected), 57)
        self.assertEqual(sum(row["layer"] == "native" for row in expected), 1)
        rows = [result(case) for case in expected]
        for row, case in zip(rows, expected):
            if case["status"] == "blocked":row.update(outcome="blocked", reason=case["blocked_reason"])
        data=worker(rows)
        data["provider_scenarios_expected_sha256"] = manifest["provider_scenarios"]["sha256"]
        directory=self.root/"actual";directory.mkdir();(directory/"report.json").write_text(json.dumps(data))
        combined=reports.aggregate([directory], self.root/"out", manifest, expected)
        self.assertTrue(combined["full_inventory"])
        self.assertEqual(len(combined["results"]), 57)
        self.assertEqual(sum(row["outcome"] == "blocked" for row in combined["results"]),
                         sum(row["status"] == "blocked" for row in expected))
        self.assertFalse(combined["full_acceptance"])
        self.assertEqual(combined["guard_errors"], [])

    def test_unreadable_worker_produces_explicit_missing_denominator(self):
        directory=self.root/"unreadable";directory.mkdir();(directory/"report.json").write_text('invalid')
        combined=reports.aggregate([directory], self.root/"out", MANIFEST, self.expected)
        self.assertEqual([row["outcome"] for row in combined["results"]], ["missing"]*3)
        self.assertFalse(combined["full_acceptance"])


if __name__ == "__main__":
    unittest.main()
