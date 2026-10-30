"""Stage isolation, ownership, report and subprocess failure regressions; no device/network."""
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import urllib.error
import urllib.request
import uuid
from unittest.mock import patch
import xml.etree.ElementTree as ET

from stage_api import BASE, NoRedirect, OwnedAccount, StageApi, StageFailure

SPEC = importlib.util.spec_from_file_location("stage_ui", Path(__file__).with_name("stage-ui.py"))
ui = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ui)
RUN = "11111111-2222-4333-8444-555555555555"


class FakeApi(StageApi):
    def __init__(self, replies):
        super().__init__(RUN, "android/0.2.16+1")
        self.replies, self.calls = list(replies), []

    def request(self, method, path, payload=None, **kwargs):
        self.calls.append((method, path, payload, kwargs))
        value = self.replies.pop(0)
        if isinstance(value, Exception):
            raise value
        return value


def registration_replies():
    return [{"version": "current-version"}, {"verification_token": "verification-secret", "expires_in": 300},
            [{"status": "verified", "is_new_user": True, "registration_token": "registration-secret"}],
            {"user_id": "owned-user", "access_token": "access-secret", "refresh_token": "refresh-secret"}]


class StageOwnershipTest(unittest.TestCase):
    def test_registration_is_the_ownership_boundary(self):
        api = FakeApi(registration_replies())
        account = api.create()
        self.assertIs(api.owned[0], account)
        self.assertEqual(account.user_id, "owned-user")
        self.assertTrue(account.phone.startswith("+7000000"))
        self.assertEqual(api.calls[1][2]["pd_consent_version"], "current-version")
        self.assertTrue(api.calls[1][2]["pd_consent_accepted"])
        self.assertTrue(api.calls[1][2]["age_confirmed"])
        self.assertNotIn("access-secret", repr(account))

    def test_collision_never_reads_or_mutates_existing_account(self):
        api = FakeApi([{"version": "pd"}, {"verification_token": "v", "expires_in": 300},
                       [{"status": "verified", "is_new_user": False, "access_token": "foreign-access"}],
                       *registration_replies()[1:]])
        account = api.create()
        self.assertEqual(account.user_id, "owned-user")
        self.assertEqual([call[1] for call in api.calls], ["v1/consents/personal_data", "v1/auth/phone",
                         "v1/auth/phone/events", "v1/auth/phone", "v1/auth/phone/events", "v1/auth/register"])
        self.assertNotEqual(api.calls[1][2]["phone"], api.calls[3][2]["phone"])

    def test_failed_registration_has_no_owned_account(self):
        api = FakeApi(registration_replies()[:-1] + [StageFailure("stage_http_409")])
        with self.assertRaisesRegex(StageFailure, "409"):
            api.create()
        self.assertEqual(api.owned, [])

    def test_clone_of_receipt_and_foreign_run_cannot_delete(self):
        api = FakeApi(registration_replies())
        account = api.create()
        clone = OwnedAccount(account.run_id, account.phone, account.user_id, account.access, account.refresh)
        with self.assertRaisesRegex(StageFailure, "not_owned"):
            api.delete_owned(clone)
        self.assertEqual(len(api.calls), 4)

    def test_cleanup_requires_live_identity_to_match(self):
        api = FakeApi(registration_replies() + [{"user_id": "another-user"}])
        account = api.create()
        with self.assertRaisesRegex(StageFailure, "identity_mismatch"):
            api.delete_owned(account)
        self.assertFalse(any(call[0] == "DELETE" for call in api.calls))

    def test_cleanup_deletes_only_matched_new_registration(self):
        api = FakeApi(registration_replies() + [{"id": "owned-user"}, None])
        account = api.create()
        api.delete_owned(account)
        api.delete_owned(account)
        self.assertTrue(account.deleted)
        self.assertEqual(sum(call[0] == "DELETE" for call in api.calls), 1)

    def test_revoked_session_is_not_evidence_of_successful_deletion(self):
        api = FakeApi(registration_replies() + [StageFailure("stage_http_401"), StageFailure("stage_http_429")])
        account = api.create()
        with self.assertRaisesRegex(StageFailure, "429"):
            api.delete_owned(account)
        self.assertFalse(account.deleted)

    def test_seed_is_limited_to_owned_synthetic_fixture(self):
        api = FakeApi(registration_replies())
        account = api.create()
        for name in ('../../private.ofx', 'secret\r\nHeader.ofx'):
            with self.assertRaisesRegex(StageFailure, "invalid_synthetic_fixture"):
                api.seed(account, b"fixture", name)
        self.assertEqual(len(api.calls), 4)

    def test_seed_waits_for_its_own_import_id(self):
        api = FakeApi(registration_replies() + [{"upload_id": "ours"},
                     {"uploads": [{"upload_id": "other", "status": "done"}, {"upload_id": "ours", "status": "processing"}]},
                     {"uploads": [{"upload_id": "ours", "status": "done", "operation_count": 7}]},
                     {"has_data": True}, {"periods": [{"date": "2026-09"}]}])
        account = api.create()
        with patch("stage_api.time.sleep"):
            self.assertEqual(api.seed(account, b"synthetic")["operation_count"], 7)
        self.assertEqual(api.calls[4][3]["headers"]["Content-Type"].split(";")[0], "multipart/form-data")

    def test_first_session_preparation_does_not_start_an_extra_phone_verification(self):
        api = FakeApi([])
        prepared = ui.prepare_case({"setup": "first-session-seeded"}, api)
        self.assertIn(prepared["phone"], api.unused)
        self.assertTrue(prepared["new_account"])
        self.assertEqual(prepared["ready_at"], 0)
        self.assertEqual(api.owned, [])
        self.assertEqual(api.calls, [])

    def test_adopting_device_registration_requires_preflight_new_candidate(self):
        api = FakeApi([])
        user_id = "22222222-2222-4222-8222-222222222222"
        with self.assertRaisesRegex(StageFailure, "unowned_device_registration"):
            api.adopt_registration("+70000009998", user_id)
        api.unused.add("+70000009998")
        account = api.adopt_registration("+70000009998", user_id)
        self.assertEqual(account.user_id, user_id)
        self.assertIs(api.owned[0], account)
        with self.assertRaises(StageFailure):
            api.adopt_registration("+70000009998", user_id)


class StageTransportTest(unittest.TestCase):
    def test_absolute_url_traversal_and_header_injection_are_rejected(self):
        api = StageApi(RUN, "android/0.2.16+1")
        for path in ("https://evil.invalid/v1/profile", "v1/../profile", "v1/profile\r\nX-Foo:x"):
            with self.assertRaisesRegex(StageFailure, "unsafe_stage_path"):
                api.request("GET", path, token="must-not-leak")

    def test_redirect_never_carries_credentials_to_another_host(self):
        with self.assertRaisesRegex(StageFailure, "redirect_refused"):
            NoRedirect().redirect_request(urllib.request.Request(BASE), None, 307, "redirect", {}, "https://evil.invalid")

    def test_http_failure_never_contains_remote_body_or_secret(self):
        class Opener:
            def open(self, req, timeout):
                self.req, self.timeout = req, timeout
                raise urllib.error.HTTPError(req.full_url, 403, "private-phone-and-token", {}, io.BytesIO(b"secret body"))
        opener = Opener()
        api = StageApi(RUN, "android/0.2.16+1", opener)
        with self.assertRaisesRegex(StageFailure, "^stage_http_403$"):
            api.request("GET", "v1/profile", token="access-secret")
        self.assertEqual(opener.req.full_url, BASE + "v1/profile")
        self.assertEqual(opener.timeout, 30)


class StageReportingTest(unittest.TestCase):
    def test_native_stream_proof_is_run_scoped_and_does_not_accept_private_fields(self):
        proof = {"schema_version": 1, "run_id": RUN, "has_client_injections": False, "counts": {"real_requests": 7}}
        with tempfile.TemporaryDirectory() as directory, patch.object(ui, "ROOT", Path(directory)):
            source, value = ui.native_proof("FIN386_NATIVE_PROOF " + json.dumps(proof), RUN)
            self.assertEqual(source, "instrumentation-stream")
            self.assertEqual(value["counts"]["real_requests"], 7)
            for invalid in (proof | {"run_id": str(uuid.uuid4())}, proof | {"refresh_token": "never-export"},
                            proof | {"counts": {"real_requests": 0}}, proof | {"has_client_injections": True}):
                with self.assertRaises(StageFailure):
                    ui.native_proof("FIN386_NATIVE_PROOF " + json.dumps(invalid), RUN)

    def test_conflicting_native_proofs_do_not_pass(self):
        proof = {"schema_version": 1, "run_id": RUN, "has_client_injections": False, "counts": {"real_requests": 7}}
        text = "FIN386_NATIVE_PROOF " + json.dumps(proof) + "\nFIN386_NATIVE_PROOF " + json.dumps(proof | {"counts": {"real_requests": 8}})
        with tempfile.TemporaryDirectory() as directory, patch.object(ui, "ROOT", Path(directory)):
            with self.assertRaisesRegex(StageFailure, "conflicting_native_proof"):
                ui.native_proof(text, RUN)
    def test_checkpoint_kills_only_the_background_client_before_response_delivery(self):
        evidence = {"run_id": RUN, "counts": {"real_ask_requests": 1, "real_ask_accepted": 1, "ask_response_held": 1}}
        replies = [(0, json.dumps(evidence)), (0, "4242\n"), (0, ""), (0, ""), (1, ""), (0, json.dumps(evidence))]
        with patch.object(ui, "command", side_effect=replies) as command:
            result = ui.process_death_checkpoint("emulator-5554", RUN)
        self.assertTrue(result["process_disappeared"])
        self.assertEqual(result["pid_before"], 4242)
        calls = [call.args[0] for call in command.call_args_list]
        self.assertTrue(any(call[-3:] == ["am", "kill", ui.APP] for call in calls))
        self.assertFalse(any("force-stop" in call for call in calls))

    def test_checkpoint_refuses_a_response_already_delivered_to_the_client(self):
        evidence = {"run_id": RUN, "counts": {"real_ask_requests": 1, "real_ask_accepted": 1, "ask_response_held": 1, "ask_response_released": 1}}
        with patch.object(ui, "command", return_value=(0, json.dumps(evidence))) as command:
            with self.assertRaisesRegex(StageFailure, "already_delivered"):
                ui.process_death_checkpoint("emulator-5554", RUN)
        self.assertEqual(command.call_count, 1)

    def test_checkpoint_refuses_foreign_run_evidence(self):
        with patch.object(ui, "command", return_value=(0, json.dumps({"run_id": str(uuid.uuid4())}))):
            with self.assertRaisesRegex(StageFailure, "foreign_checkpoint"):
                ui.read_client_evidence("emulator-5554", RUN)
    def test_xml_non_success_attributes_and_suite_errors_cannot_pass(self):
        for xml in ('<testsuite><testcase status="ERROR"/></testsuite>',
                    '<testsuite failures="1"><testcase status="PASSED"/></testsuite>',
                    '<testsuite><testcase status="SKIPPED"/></testsuite>',
                    '<testsuite><testcase><skipped/></testcase></testsuite>',
                    '<testsuite/>'):
            self.assertFalse(ui.report_passed(ET.fromstring(xml)))
        self.assertTrue(ui.report_passed(ET.fromstring('<testsuite failures="0"><testcase status="PASSED"/></testsuite>')))

    def test_all_original_cases_roundtrip_to_qase(self):
        spec = importlib.util.spec_from_file_location("qase_report_stage_test", Path(__file__).with_name("qase-report.py"))
        qase = importlib.util.module_from_spec(spec)
        sys.modules[spec.name] = qase
        spec.loader.exec_module(qase)
        mapping = json.loads((ui.ROOT / "scripts/qase-cases.json").read_text())["cases"]
        rows = [{"id": "qase-" + str(number), "source": source, "outcome": "passed", "provenance": "real-stage"}
                for source, number in mapping.items()]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.xml"
            ui.junit({"results": rows}, path)
            results = qase.parse_report(path.read_text())
        payload, unmapped = qase.map_results(results, mapping)
        self.assertEqual(unmapped, [])
        self.assertEqual({row["case_id"] for row in payload}, set(mapping.values()))
    def test_redaction_covers_root_and_new_app_session_tokens(self):
        text = 'Bearer abc\n{"access_token":"xyz","verification_token":"token"} +70000001234 eyJabc.def.ghi owned-secret'
        safe = ui.sanitize(text, {"owned-secret"})
        for value in ("abc", "xyz", '"token"', "+70000001234", "eyJabc.def.ghi", "owned-secret"):
            self.assertNotIn(value, safe)

    def test_blocked_missing_and_skipped_are_never_passed(self):
        report = {"results": [{"id": "qase-1", "source": ".maestro/flows/a.yaml", "outcome": "passed", "provenance": "real-stage"},
                              {"id": "qase-2", "outcome": "blocked", "provenance": "client-injected", "reason": "provider_required"},
                              {"id": "qase-3", "outcome": "not_run", "provenance": "real-stage"}]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.xml"
            ui.junit(report, path)
            tree = ET.parse(path).getroot()
        self.assertEqual(tree.get("tests"), "3")
        self.assertEqual(tree.get("failures"), "2")
        self.assertEqual(tree.get("skipped"), "0")
        self.assertEqual(len(tree.findall(".//failure")), 2)

    def test_native_retains_original_qase_identity(self):
        row = {"id": "qase-16", "source": "ru.finassist.pf.StatementPickerTest#uploadsTheDocumentReturnedBySystemPicker",
               "outcome": "passed", "provenance": "real-stage"}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.xml"
            ui.junit({"results": [row]}, path)
            test = ET.parse(path).getroot().find("testcase")
        self.assertEqual(test.get("classname"), "ru.finassist.pf.StatementPickerTest")
        self.assertEqual(test.get("name"), "uploadsTheDocumentReturnedBySystemPicker")

    def test_child_timeout_is_enforced(self):
        code, output = ui.command([sys.executable, "-c", "import time; time.sleep(10)"], timeout=0.05)
        self.assertEqual(code, 124)
        self.assertEqual(output, "process_timeout")

    def test_workflow_has_only_manual_trigger(self):
        source = (ui.ROOT / ".github/workflows/stage-ui.yml").read_text()
        events = source.split("\non:\n", 1)[1].split("\npermissions:", 1)[0]
        self.assertEqual(events.strip(), "workflow_dispatch:")
        self.assertNotIn("workflow_call:", source)
        self.assertIn("if: always()", source)


if __name__ == "__main__":
    unittest.main()
