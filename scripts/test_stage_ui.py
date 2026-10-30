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
from types import SimpleNamespace
from unittest.mock import patch
import xml.etree.ElementTree as ET

from stage_api import BASE, NoRedirect, OwnedAccount, StageApi, StageFailure

SPEC = importlib.util.spec_from_file_location("stage_ui", Path(__file__).with_name("stage-ui.py"))
ui = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ui)
RUN = "11111111-2222-4333-8444-555555555555"


class StageControlStatusTest(unittest.TestCase):
    def test_closed_transport_diagnostics_retain_classification_and_drop_unrelated_data(self):
        for code in ("offline", "offline_dns", "offline_timeout", "offline_tls", "offline_connection"):
            raw = {"run_id": RUN, "operation": "setup_run", "phase": "document", "outcome": "failed", "error_class": code,
                   "message": "Bearer private-token +70000001234", "url": "https://private.invalid"}
            safe = ui.control_status(json.dumps(raw), RUN)
            self.assertEqual(safe, {"operation": "setup_run", "phase": "document", "outcome": "failed", "error_class": code})
            self.assertNotIn("private-token", json.dumps(safe))
            self.assertNotIn("+7000000", json.dumps(safe))

    def test_unknown_diagnostic_codes_invalid_shapes_and_foreign_runs_are_rejected(self):
        baseline = {"run_id": RUN, "operation": "setup_run", "phase": "document", "outcome": "failed", "error_class": "offline_dns"}
        for key in ("operation", "phase", "outcome", "error_class"):
            self.assertIsNone(ui.control_status(json.dumps(baseline | {key: "private_lowercase_payload"}), RUN))
        self.assertIsNone(ui.control_status(json.dumps(baseline | {"run_id": str(uuid.uuid4())}), RUN))
        self.assertIsNone(ui.control_status("[]", RUN))
        self.assertIsNone(ui.control_status("{}", RUN))
        self.assertIsNone(ui.control_status("not-json", RUN))


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
    def test_retained_first_session_registration_never_sends_device_or_host_cleanup(self):
        phone = "+70000001234"
        user_id = "22222222-2222-4222-8222-222222222222"
        api = FakeApi([])
        api.unused.add(phone)
        evidence = {"run_id": RUN, "has_client_injections": False, "counts": {"real_requests": 7},
                    "successful_registrations": [{"phone": phone, "user_id": user_id}], "deleted_phones": []}
        row = {"id": "qase-8", "source": ".maestro/flows/operations/feed_and_detail.yaml", "qase_id": 8,
               "status": "ready", "layer": "maestro", "setup": "first-session-seeded", "provenance": "real-stage",
               "stage_flow": ".maestro-stage/flows/operations/feed_and_detail.yaml"}
        args = SimpleNamespace(serial="emulator-5554", case_timeout=30, retain_owned=True)
        prepared = {"account": None, "phone": phone, "new_phone": phone, "ready_at": 0, "new_account": True}
        commands = []

        def fake_command(command, **kwargs):
            commands.append(command)
            if command[0] == "maestro":
                Path(command[command.index("--output") + 1]).write_text('<testsuite><testcase name="stage"/></testsuite>')
            elif command[-1] == "files/stage-evidence.json":
                return 0, json.dumps(evidence)
            elif command[-1] == "files/stage-control-status.json":
                return 0, json.dumps({"run_id": RUN, "operation": "setup_run", "phase": "done", "outcome": "passed"})
            return 0, ""

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private = root / "private"
            private.mkdir()
            with patch.object(ui, "ROOT", root), patch.object(ui, "OUT", root / "build/stage-ui"), patch.object(ui, "command", side_effect=fake_command):
                result = ui.run_case(row, api, args, private, prepared)
            self.assertEqual(result["outcome"], "passed", result)
            self.assertEqual(len(api.owned), 1)
            self.assertEqual((api.owned[0].phone, api.owned[0].user_id), (phone, user_id))
            self.assertFalse(api.owned[0].deleted)
            self.assertFalse(any("cleanup_run=owned" in str(argument) for command in commands for argument in command))
            report = {"cleanup": []}
            with patch.object(api, "delete_owned") as delete:
                ui.cleanup_accounts(api, report, api.owned, retain=True)
                delete.assert_not_called()
            self.assertEqual(report["cleanup"][0]["outcome"], "retained")
            self.assertFalse(api.owned[0].deleted)
            self.assertEqual(api.calls, [])

    def test_only_explicit_nonregistration_new_empty_case_can_pass_without_registration_receipt(self):
        for expected in (False, True, None):
            with self.subTest(registration_expected=expected), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                private = root / "private"
                private.mkdir()
                phone = "+70000001234"
                api = FakeApi([])
                api.unused.add(phone)
                evidence = {"run_id": RUN, "has_client_injections": False, "counts": {"real_requests": 1}, "successful_registrations": []}
                row = {"id": "qase-3", "status": "ready", "layer": "maestro", "setup": "new-empty",
                       "provenance": "real-stage", "stage_flow": ".maestro-stage/flows/auth/phone_validation.yaml"}
                if expected is not None:
                    row["registration_expected"] = expected
                args = SimpleNamespace(serial="emulator-5554", case_timeout=30, retain_owned=True)
                prepared = {"account": None, "phone": phone, "new_phone": phone, "ready_at": 0, "new_account": True}

                def fake_command(command, **kwargs):
                    if command[0] == "maestro":
                        Path(command[command.index("--output") + 1]).write_text('<testsuite><testcase name="stage"/></testsuite>')
                    elif command[-1] == "files/stage-evidence.json":
                        return 0, json.dumps(evidence)
                    return 0, ""

                with patch.object(ui, "ROOT", root), patch.object(ui, "OUT", root / "build/stage-ui"), patch.object(ui, "command", side_effect=fake_command):
                    result = ui.run_case(row, api, args, private, prepared)
                self.assertEqual(result["outcome"], "passed" if expected is False else "failed", result)
                if expected is not False:
                    self.assertEqual(result["reason"], "missing_new_registration_ownership")
                self.assertEqual(api.owned, [])
                self.assertEqual(api.calls, [])

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


class StageEvidenceFailureCleanupTest(unittest.TestCase):
    PHONE = "+70000001234"
    USER = "22222222-2222-4222-8222-222222222222"
    FOREIGN_PHONE = "+70000009999"

    def run_evidence_case(self, *, missing_count=False, injected=False, retain=False, flow_passed=True, extra_registration=None, invalid_counts=False, interrupt_flow=False):
        api = FakeApi([])
        api.unused.update((self.PHONE, self.FOREIGN_PHONE))
        registrations = [{"phone": self.PHONE, "user_id": self.USER}]
        if extra_registration is not None:
            registrations.append(extra_registration)
        evidence = {"run_id": RUN, "has_client_injections": injected,
                    "counts": {"real_requests": -1 if invalid_counts else 7},
                    "successful_registrations": registrations, "deleted_phones": []}
        row = {"id": "qase-8", "status": "ready", "layer": "maestro", "setup": "first-session-seeded",
               "provenance": "real-stage", "stage_flow": ".maestro-stage/flows/operations/feed_and_detail.yaml"}
        if missing_count:
            row["required_counts"] = {"stream_429": 1}
        args = SimpleNamespace(serial="emulator-5554", case_timeout=30, retain_owned=retain)
        prepared = {"account": None, "phone": self.PHONE, "new_phone": self.PHONE, "ready_at": 0, "new_account": True}
        commands = []
        cleanup_sent = False

        def fake_command(command, **kwargs):
            nonlocal cleanup_sent
            commands.append(command)
            if command[0] == "maestro":
                if interrupt_flow:
                    raise StageFailure("runner_interrupted")
                failure = "" if flow_passed else '<failure message="synthetic UI failure"/>'
                Path(command[command.index("--output") + 1]).write_text('<testsuite><testcase name="stage">' + failure + '</testcase></testsuite>')
                return (0 if flow_passed else 1), ""
            if any("cleanup_run=owned" in str(item) for item in command):
                cleanup_sent = True
            if command[-1] == "files/stage-evidence.json":
                return 0, json.dumps(evidence | {"deleted_phones": [self.PHONE] if cleanup_sent else []})
            return 0, ""

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            private = root / "private"
            private.mkdir()
            with patch.object(ui, "ROOT", root), patch.object(ui, "OUT", root / "build/stage-ui"), patch.object(ui, "command", side_effect=fake_command):
                result = ui.run_case(row, api, args, private, prepared)
        report = {"cleanup": []}
        with patch.object(api, "delete_owned", wraps=api.delete_owned) as delete:
            ui.cleanup_accounts(api, report, api.owned, retain=retain)
            host_deletes = delete.call_count
        return api, result, commands, report, host_deletes

    def test_missing_counter_and_undeclared_injection_keep_ownership_and_cleanup_on_failure(self):
        for kind, failure in (("missing_count", "required_transport_observation_missing"), ("injected", "undeclared_client_injection")):
            for retain in (False, True):
                with self.subTest(kind=kind, retain=retain):
                    api, result, commands, report, host_deletes = self.run_evidence_case(**{kind: True}, retain=retain)
                    self.assertEqual(result["outcome"], "failed")
                    self.assertEqual(result["reason"], failure)
                    self.assertEqual([(account.phone, account.user_id) for account in api.owned], [(self.PHONE, self.USER)])
                    self.assertEqual(any("cleanup_run=owned" in str(item) for command in commands for item in command), not retain)
                    self.assertEqual(api.owned[0].deleted, not retain)
                    self.assertEqual(report["cleanup"][0]["outcome"], "retained" if retain else "deleted")
                    self.assertEqual(host_deletes, 0 if retain else 1)
                    self.assertEqual(api.calls, [])

    def test_failed_ui_still_preserves_registration_and_cleanup_without_declaring_a_pass(self):
        api, result, commands, report, _ = self.run_evidence_case(missing_count=True, flow_passed=False)
        self.assertEqual(result["outcome"], "failed")
        self.assertEqual(result["reason"], "flow_or_report_failed")
        self.assertEqual(result["evidence_failure"], "required_transport_observation_missing")
        self.assertEqual(len(api.owned), 1)
        self.assertTrue(api.owned[0].deleted)
        self.assertEqual(report["cleanup"][0]["outcome"], "deleted")

    def test_partially_invalid_registration_never_adopts_or_deletes_foreign_candidate(self):
        for invalid in ({"phone": self.FOREIGN_PHONE, "user_id": "33333333-3333-4333-8333-333333333333"},
                        {"phone": self.PHONE}, {"phone": self.PHONE, "user_id": "not-an-id"}):
            with self.subTest(invalid=invalid):
                api, result, commands, report, _ = self.run_evidence_case(extra_registration=invalid)
                self.assertEqual(result["outcome"], "failed")
                self.assertEqual(result["reason"], "invalid_client_registration")
                self.assertEqual([(account.phone, account.user_id) for account in api.owned], [(self.PHONE, self.USER)])
                self.assertIn(self.FOREIGN_PHONE, api.unused)
                self.assertTrue(api.owned[0].deleted)
                self.assertEqual(len(report["cleanup"]), 1)
                self.assertEqual(api.calls, [])

    def test_interrupted_flow_finishes_ownership_capture_and_cleanup_before_main_stops(self):
        api, result, commands, report, _ = self.run_evidence_case(interrupt_flow=True)
        self.assertEqual(result["outcome"], "failed")
        self.assertEqual(result["reason"], "runner_interrupted")
        self.assertEqual(len(api.owned), 1)
        self.assertTrue(api.owned[0].deleted)
        self.assertEqual(report["cleanup"][0]["outcome"], "deleted")

    def test_invalid_safe_envelope_counts_never_grant_registration_ownership(self):
        api, result, commands, report, host_deletes = self.run_evidence_case(invalid_counts=True)
        self.assertEqual(result["outcome"], "failed")
        self.assertEqual(result["reason"], "invalid_client_evidence_counts")
        self.assertEqual(api.owned, [])
        self.assertFalse(any("cleanup_run=owned" in str(item) for command in commands for item in command))
        self.assertEqual(report["cleanup"], [])
        self.assertEqual(host_deletes, 0)


class StageInterruptTest(unittest.TestCase):
    def main_interrupted(self, *, during_preparation=False, evidence_failure=False):
        rows = [{"id": "qase-8", "qase_id": 8, "status": "ready", "layer": "maestro", "setup": "first-session-seeded", "provenance": "real-stage"},
                {"id": "qase-9", "qase_id": 9, "status": "ready", "layer": "maestro", "setup": "first-session-seeded", "provenance": "real-stage"}]
        prepared = {"account": None, "phone": "+70000001234", "new_phone": "+70000001234", "ready_at": 0, "new_account": True}
        first = rows[0] | {"outcome": "failed", "reason": "flow_or_report_failed" if evidence_failure else "runner_interrupted"}
        if evidence_failure:
            first["evidence_failure"] = "runner_interrupted"
        api = FakeApi([])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = root / "app"
            app.mkdir()
            (app / "build.gradle.kts").write_text('val appVersionName = "0.2.16"\n')
            apk = app / "build/outputs/apk/stage/e2e/app-stage-e2e.apk"
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"synthetic-apk-placeholder")
            with patch.object(ui, "ROOT", root), patch.object(ui, "OUT", root / "build/stage-ui"), \
                    patch.object(ui, "inventory", return_value={"cases": rows}), patch.object(ui, "StageApi", return_value=api), \
                    patch.object(ui.shutil, "which", return_value="/synthetic/available"), \
                    patch.object(ui, "command", return_value=(0, "")), \
                    patch.object(ui, "prepare_case", side_effect=StageFailure("runner_interrupted") if during_preparation else None, return_value=prepared) as prepare, \
                    patch.object(ui, "run_case", return_value=first) as run:
                self.assertEqual(ui.main(["--no-build", "--retain-owned"]), 1)
            report = json.loads((root / "build/stage-ui/report.json").read_text())
        return report, prepare.call_count, run.call_count

    def test_interrupt_in_flow_or_evidence_prevents_the_next_case(self):
        for evidence in (False, True):
            with self.subTest(evidence=evidence):
                report, _, runs = self.main_interrupted(evidence_failure=evidence)
                self.assertEqual(runs, 1)
                self.assertEqual(report["environment_failure"], "runner_interrupted")
                self.assertEqual(report["results"][0]["outcome"], "failed")
                self.assertEqual(report["results"][1]["outcome"], "not_run")
                self.assertFalse(report["full_acceptance"])

    def test_interrupt_during_preparation_never_prepares_or_runs_the_next_case(self):
        report, preparations, runs = self.main_interrupted(during_preparation=True)
        self.assertEqual(preparations, 1)
        self.assertEqual(runs, 0)
        self.assertEqual(report["environment_failure"], "runner_interrupted")

    def owned_pair(self, api):
        phones = ("+70000001234", "+70000005678")
        users = ("22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333")
        api.unused.update(phones)
        return [api.adopt_registration(phone, user) for phone, user in zip(phones, users)]

    def test_cleanup_interrupt_records_current_and_remaining_owned_accounts_as_unverified(self):
        api = FakeApi([])
        accounts = self.owned_pair(api)
        report = {"cleanup": []}
        with patch.object(api, "delete_owned", side_effect=StageFailure("runner_interrupted")) as delete:
            with self.assertRaisesRegex(StageFailure, "runner_interrupted"):
                ui.cleanup_accounts(api, report, accounts, retain=False)
        self.assertEqual(delete.call_count, 1)
        self.assertEqual(len(report["cleanup"]), 2)
        self.assertTrue(all(row["outcome"] == "cleanup_unverified" for row in report["cleanup"]))
        self.assertTrue(all(not account.deleted for account in accounts))
        self.assertEqual(api.calls, [])

    def test_second_interrupt_in_final_cleanup_still_persists_the_final_failure_report(self):
        api = FakeApi([])
        accounts = self.owned_pair(api)
        rows = [{"id": "qase-8", "qase_id": 8, "status": "ready", "layer": "maestro", "setup": "first-session-seeded", "provenance": "real-stage"},
                {"id": "qase-9", "qase_id": 9, "status": "ready", "layer": "maestro", "setup": "first-session-seeded", "provenance": "real-stage"}]
        prepared = {"account": None, "phone": accounts[0].phone, "new_phone": accounts[0].phone, "ready_at": 0, "new_account": True}
        previous = {number: ui.signal.getsignal(number) for number in (ui.signal.SIGINT, ui.signal.SIGTERM)}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = root / "app"
            app.mkdir()
            (app / "build.gradle.kts").write_text('val appVersionName = "0.2.16"\n')
            apk = app / "build/outputs/apk/stage/e2e/app-stage-e2e.apk"
            apk.parent.mkdir(parents=True)
            apk.write_bytes(b"synthetic-apk-placeholder")
            with patch.object(ui, "ROOT", root), patch.object(ui, "OUT", root / "build/stage-ui"), \
                    patch.object(ui, "inventory", return_value={"cases": rows}), patch.object(ui, "StageApi", return_value=api), \
                    patch.object(ui.shutil, "which", return_value="/synthetic/available"), \
                    patch.object(ui, "command", return_value=(0, "")), patch.object(ui, "prepare_case", return_value=prepared), \
                    patch.object(ui, "run_case", side_effect=StageFailure("runner_interrupted")) as run, \
                    patch.object(api, "delete_owned", side_effect=StageFailure("runner_interrupted")) as delete:
                with self.assertRaisesRegex(StageFailure, "runner_interrupted"):
                    ui.main(["--no-build"])
            report = json.loads((root / "build/stage-ui/report.json").read_text())
        self.assertEqual(run.call_count, 1)
        self.assertEqual(delete.call_count, 1)
        self.assertEqual(report["environment_failure"], "runner_interrupted")
        self.assertEqual(report["results"][0]["outcome"], "failed")
        self.assertEqual(report["results"][0]["reason"], "runner_interrupted")
        self.assertEqual(report["results"][1]["outcome"], "not_run")
        self.assertEqual(len(report["cleanup"]), 2)
        self.assertTrue(all(row["outcome"] == "cleanup_unverified" for row in report["cleanup"]))
        self.assertTrue(all(not account.deleted for account in accounts))
        self.assertIn("finished_at", report)
        self.assertFalse(report["full_acceptance"])
        self.assertEqual(previous, {number: ui.signal.getsignal(number) for number in previous})
        self.assertEqual(api.calls, [])

    def test_interrupt_during_cleanup_is_not_reclassified_as_a_recoverable_failure(self):
        api = FakeApi(registration_replies())
        account = api.create()
        with patch.object(api, "delete_owned", side_effect=StageFailure("runner_interrupted")) as delete:
            with self.assertRaisesRegex(StageFailure, "runner_interrupted"):
                ui.cleanup_accounts(api, {"cleanup": []}, [account, account], retain=False)
        self.assertEqual(delete.call_count, 1)


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
    def test_prepare_report_and_preparation_failures_preserve_all_original_case_identities(self):
        manifest = json.loads((ui.ROOT / "scripts/stage-cases.json").read_text())
        rows = manifest["cases"] + manifest.get("additional_cases", [])
        identities = {row["id"]: row.get("qase_id") for row in rows}
        for argv, expected_exit in ((["--prepare-report"], 0), (["--no-build"], 1)):
            with self.subTest(argv=argv), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                app = root / "app"
                app.mkdir()
                (app / "build.gradle.kts").write_text('val appVersionName = "0.2.16"\n')
                apk = app / "build/outputs/apk/stage/e2e/app-stage-e2e.apk"
                apk.parent.mkdir(parents=True)
                apk.write_bytes(b"synthetic-apk-placeholder")
                api = FakeApi([])
                with patch.object(ui, "ROOT", root), patch.object(ui, "OUT", root / "build/stage-ui"), \
                        patch.object(ui, "inventory", return_value=manifest), patch.object(ui, "StageApi", return_value=api), \
                        patch.object(ui.shutil, "which", return_value="/synthetic/available"), \
                        patch.object(ui, "command", return_value=(0, "")), \
                        patch.object(ui, "prepare_case", side_effect=StageFailure("synthetic_preparation_failure")):
                    self.assertEqual(ui.main(argv), expected_exit)
                report = json.loads((root / "build/stage-ui/report.json").read_text())
                self.assertEqual({item["id"]: item.get("qase_id") for item in report["results"]}, identities)
                if "--prepare-report" in argv:
                    self.assertTrue(all(item["outcome"] == "not_run" for item in report["results"]))
                else:
                    for item, row in zip(report["results"], rows):
                        if row["status"] == "ready":
                            self.assertEqual(item["outcome"], "failed")
                            self.assertEqual(item["reason"], "synthetic_preparation_failure")
                self.assertFalse(report["full_acceptance"])
                self.assertEqual(api.calls, [])

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
        evidence = {"run_id": RUN, "counts": {"real_ask_requests": 2, "real_ask_consent_required": 1, "real_ask_accepted": 1, "ask_response_held": 1}}
        replies = [(0, json.dumps(evidence)), (0, "4242\n"), (0, ""), (0, ""), (1, ""), (0, json.dumps(evidence))]
        with patch.object(ui, "command", side_effect=replies) as command:
            result = ui.process_death_checkpoint("emulator-5554", RUN)
        self.assertTrue(result["process_disappeared"])
        self.assertEqual(result["pid_before"], 4242)
        self.assertEqual(result["requests_before_death"], 2)
        self.assertEqual(result["consent_refusals_before_death"], 1)
        calls = [call.args[0] for call in command.call_args_list]
        self.assertTrue(any(call[-3:] == ["am", "kill", ui.APP] for call in calls))
        self.assertFalse(any("force-stop" in call for call in calls))

    def test_checkpoint_refuses_a_response_already_delivered_to_the_client(self):
        evidence = {"run_id": RUN, "counts": {"real_ask_requests": 2, "real_ask_consent_required": 1, "real_ask_accepted": 1, "ask_response_held": 1, "ask_response_released": 1}}
        with patch.object(ui, "command", return_value=(0, json.dumps(evidence))) as command:
            with self.assertRaisesRegex(StageFailure, "already_delivered"):
                ui.process_death_checkpoint("emulator-5554", RUN)
        self.assertEqual(command.call_count, 1)

    def test_checkpoint_rejects_an_extra_third_post_even_when_it_was_refused(self):
        evidence = {"run_id": RUN, "counts": {"real_ask_requests": 3, "real_ask_consent_required": 1,
                                             "real_ask_accepted": 1, "ask_response_held": 1}}
        with patch.object(ui, "command", return_value=(0, json.dumps(evidence))) as command:
            with self.assertRaisesRegex(StageFailure, "unexpected_ask_requests"):
                ui.process_death_checkpoint("emulator-5554", RUN)
        self.assertEqual(command.call_count, 1)

    def test_checkpoint_never_treats_two_posts_without_the_exact_consent_refusal_as_valid(self):
        evidence = {"run_id": RUN, "counts": {"real_ask_requests": 2, "real_ask_accepted": 1, "ask_response_held": 1}}
        with patch.object(ui, "command", return_value=(0, json.dumps(evidence))) as command, \
                patch.object(ui.time, "monotonic", side_effect=[0, 21]):
            with self.assertRaisesRegex(StageFailure, "checkpoint_not_reached"):
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
