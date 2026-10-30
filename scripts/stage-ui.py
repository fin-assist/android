#!/usr/bin/env python3
"""Manual stage UI runner. Public reports contain evidence, never authentication state."""
from __future__ import annotations

import argparse
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

from stage_api import StageApi, StageFailure

ROOT = Path(__file__).resolve().parent.parent
APP = "ru.finassist.pf.stage.e2e"
OUT = ROOT / "build/stage-ui"
CONTROL_STATUS_VALUES = {
    "operation": {"config", "setup_run", "cleanup_run", "sign_in", "expect_process_question"},
    "phase": {"initial", "session", "lock", "document", "phone", "verification", "register", "session_commit",
              "profile", "import", "import_progress", "analytics", "passcode", "cleanup_delete", "cleanup_local", "done"},
    "outcome": {"started", "passed", "failed"},
    "error_class": {"timeout", "session_state", "document_invalid", "verification_not_verified", "candidate_not_new",
                    "registration_receipt_missing", "profile_identity_mismatch", "import_not_complete", "offline",
                    "offline_dns", "offline_timeout", "offline_tls", "offline_connection", "rate_limited", "server",
                    "unauthorized", "update_required", "maintenance", "api_error", "cancelled", "io_error",
                    "invalid_argument", "invalid_state", "other"},
}


def control_status(raw, run_id):
    """Project only matching-run closed diagnostics; arbitrary string fields are never public evidence."""
    try:
        status = json.loads(raw)
        if not isinstance(status, dict) or status.get("run_id") != run_id:
            return None
        if not {"operation", "phase", "outcome"} <= status.keys():
            return None
        safe = {key: status[key] for key in CONTROL_STATUS_VALUES if key in status}
        if all(isinstance(value, str) and value in CONTROL_STATUS_VALUES[key] for key, value in safe.items()):
            return safe
    except (ValueError, TypeError):
        pass
    return None


def sanitize(text, secrets=()):
    for secret in sorted((item for item in secrets if item), key=len, reverse=True):
        text = text.replace(secret, "[redacted]")
    text = re.sub(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "[jwt]", text)
    text = re.sub(r"(?i)(Bearer\s+)[^\s\"<>]+", r"\1[redacted]", text)
    text = re.sub(r'(?i)([\"\']?(?:access_token|refresh_token|verification_token|registration_token)[\"\']?\s*[:=]\s*[\"\']?)[^\s\"\'&,<>]+', r"\1[redacted]", text)
    text = re.sub(r"\+?7000000[0-9]{4}", "[synthetic-phone]", text)
    return text


def command(args, *, timeout, cwd=ROOT, data=None):
    """A process-group timeout also stops Gradle/Maestro children, not just the parent shell."""
    process = subprocess.Popen(args, cwd=cwd, stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        output, _ = process.communicate(data, timeout=timeout)
        return process.returncode, output.decode("utf-8", errors="replace")
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate(timeout=5)
        return 124, "process_timeout"
    except BaseException:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
            process.communicate(timeout=5)
        raise


def inventory(path):
    manifest = json.loads(path.read_text())
    baseline = json.loads((ROOT / "scripts/qase-cases.json").read_text())["cases"]
    rows = manifest["cases"]
    all_rows = rows + manifest.get("additional_cases", [])
    mapped = {row["source"]: row["qase_id"] for row in all_rows if row.get("source") and row.get("qase_id")}
    if mapped != baseline or len(mapped) != len(baseline):
        raise StageFailure("stage_inventory_does_not_match_qase")
    identifiers = [row["id"] for row in rows + manifest.get("additional_cases", [])]
    if len(set(identifiers)) != len(identifiers):
        raise StageFailure("duplicate_stage_case")
    for row in all_rows:
        if row["status"] not in ("ready", "blocked") or row["layer"] not in ("maestro", "native", "checkpoint"):
            raise StageFailure("invalid_stage_case")
        if row["status"] == "blocked" and not row.get("blocked_reason"):
            raise StageFailure("blocked_case_without_reason")
        if row["status"] == "ready" and row["layer"] in ("maestro", "checkpoint"):
            keys = ("stage_flow", "before_flow", "after_flow") if row["layer"] == "checkpoint" else ("stage_flow",)
            for key in keys:
                target = (ROOT / row[key]).resolve()
                if not target.is_relative_to((ROOT / ".maestro-stage").resolve()) or not target.is_file():
                    raise StageFailure("stage_flow_missing_or_unconfined")
    return manifest


def junit(report, path):
    rows = report["results"]
    suite = ET.Element("testsuite", name="Android actual stage", tests=str(len(rows)),
                       failures=str(sum(row["outcome"] != "passed" for row in rows)), skipped="0")
    for row in rows:
        item = ET.SubElement(suite, "testcase", name=row["id"], classname="stage-ui", time=str(row.get("seconds", 0)))
        source = row.get("source") or row["id"]
        item.set("file", str(ROOT / source) if source.startswith((".maestro/", ".maestro-stage/")) else source)
        if "#" in (row.get("source") or ""):
            classname, name = row["source"].split("#", 1)
            item.set("classname", classname)
            item.set("name", name)
        if row["outcome"] != "passed":
            ET.SubElement(item, "failure", type=row["outcome"], message=row.get("reason", row["outcome"]))
        ET.SubElement(item, "system-out").text = "provenance=" + row["provenance"]
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def write_report(report):
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "report.json.tmp").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    (OUT / "report.json.tmp").replace(OUT / "report.json")
    junit(report, OUT / "report.xml.tmp")
    (OUT / "report.xml.tmp").replace(OUT / "report.xml")


def save_materials(source, target, secrets):
    """Only known image/text formats; no raw archives, databases or unknown binary logs."""
    if not source.exists():
        return
    for path in source.rglob("*"):
        if not path.is_file() or path.is_symlink() or path.stat().st_size > 20 * 1024 * 1024:
            continue
        dest = target / path.relative_to(source)
        if path.suffix.lower() == ".png":
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, dest)
        elif path.suffix.lower() in (".json", ".xml", ".txt", ".log", ".yaml"):
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_text(sanitize(path.read_text(errors="replace"), secrets))


def display(phone):
    value = phone[2:]
    return r"\+7 " + value[:3] + " " + value[3:6] + "-" + value[6:8] + "-" + value[8:]


def report_passed(root, expected=None):
    for node in root.iter():
        if node.tag in ("testsuite", "testsuites"):
            for key in ("failures", "errors", "skipped"):
                if node.get(key, "0") != "0":
                    return False
    tests = list(root.iter("testcase"))
    if expected is not None:
        tests = [test for test in tests if (test.get("classname"), test.get("name")) == expected]
    return len(tests) == 1 and all(
        test.get("status", "passed").lower() in ("passed", "success", "successful")
        and not any(child.tag in ("failure", "error", "skipped") for child in test) for test in tests)


def read_client_evidence(serial, run_id):
    _, text = command(["adb", "-s", serial, "exec-out", "run-as", APP, "cat", "files/stage-evidence.json"], timeout=10)
    try:
        evidence = json.loads(text)
    except ValueError:
        raise StageFailure("missing_client_checkpoint_evidence") from None
    if evidence.get("run_id") != run_id:
        raise StageFailure("foreign_checkpoint_evidence")
    return evidence


def process_death_checkpoint(serial, run_id):
    deadline = time.monotonic() + 20
    while True:
        evidence = read_client_evidence(serial, run_id)
        counts = evidence.get("counts", {})
        if counts.get("real_ask_requests") == 1 and counts.get("real_ask_accepted") == 1 and counts.get("ask_response_held") == 1:
            break
        if time.monotonic() >= deadline:
            raise StageFailure("accepted_ask_checkpoint_not_reached")
        time.sleep(0.5)
    if counts.get("ask_response_released", 0) != 0:
        raise StageFailure("ask_response_already_delivered")
    adb = ["adb", "-s", serial]
    code, pid = command([*adb, "shell", "pidof", APP], timeout=10)
    if code != 0 or not re.fullmatch(r"[0-9]+\s*", pid):
        raise StageFailure("client_not_running_at_checkpoint")
    code, _ = command([*adb, "shell", "input", "keyevent", "KEYCODE_HOME"], timeout=10)
    if code:
        raise StageFailure("background_checkpoint_failed")
    deadline = time.monotonic() + 15
    while True:
        command([*adb, "shell", "am", "kill", APP], timeout=10)
        code, current = command([*adb, "shell", "pidof", APP], timeout=10)
        if code in (0, 1) and not current.strip():
            break
        if time.monotonic() >= deadline:
            raise StageFailure("client_process_did_not_die")
        time.sleep(0.5)
    after = read_client_evidence(serial, run_id).get("counts", {})
    if after.get("ask_response_released", 0) != 0:
        raise StageFailure("response_released_before_process_death")
    return {"pid_before": int(pid), "process_disappeared": True, "accepted_before_death": 1,
            "response_delivered_before_death": False, "server_restarted": False}


def native_proof(output, run_id):
    """Recover only the tiny test-storage projection; the orchestrator may have cleared private app data."""
    proofs = []
    for path in (ROOT / "app/build").rglob("fin386-native-proof-" + run_id + ".json"):
        if path.is_file() and not path.is_symlink() and path.stat().st_size <= 8192:
            proofs.append(("test-storage", path.read_text()))
    proofs.extend(("instrumentation-stream", match.group(1)) for match in re.finditer(r"FIN386_NATIVE_PROOF (\{[^\r\n]+\})", output))
    valid = []
    for source, raw in proofs:
        try:
            proof = json.loads(raw)
        except ValueError:
            continue
        if not isinstance(proof, dict):
            continue
        counts = proof.get("counts")
        if (set(proof) == {"schema_version", "run_id", "has_client_injections", "counts"}
                and proof["schema_version"] == 1 and proof["run_id"] == run_id
                and proof["has_client_injections"] is False and isinstance(counts, dict)
                and set(counts) == {"real_requests"} and type(counts["real_requests"]) is int
                and 0 < counts["real_requests"] <= 1_000_000):
            valid.append((source, proof))
    if not valid or any(proof != valid[0][1] for _, proof in valid):
        raise StageFailure("missing_or_conflicting_native_proof")
    return valid[0]


def prepare_case(row, api):
    account = None
    setup = row.get("setup", "existing-seeded")
    if setup not in ("existing-seeded", "new-empty", "existing-and-new", "first-session-seeded", "first-session-fresh"):
        raise StageFailure("unknown_case_setup")
    if setup in ("existing-seeded", "existing-and-new"):
        account = api.create()
        fixture = ROOT / "mock/backend/src/main/assets/statements/fixture.ofx"
        content = fixture.read_bytes()
        api.seed(account, content)
    phone = account.phone if account else api.phone_candidate()
    if account is None:
        api.unused.add(phone)
    new_phone = api.phone_candidate() if setup == "existing-and-new" else phone
    if new_phone != phone:
        api.unused.add(new_phone)
    return {"account": account, "phone": phone, "new_phone": new_phone,
            "ready_at": account.login_ready_at if account else 0, "new_account": account is None}


def run_case(row, api, args, private, prepared=None):
    started = time.monotonic()
    result = {key: row[key] for key in ("id", "source", "qase_id", "provenance") if key in row}
    if row["status"] != "ready":
        return result | {"outcome": "blocked", "reason": row["blocked_reason"], "seconds": 0}
    result["outcome"] = "failed"
    target = OUT / "cases" / row["id"]
    result["artifacts"] = str(target.relative_to(ROOT))
    target.mkdir(parents=True, exist_ok=True)
    configured = False
    flow_private = None
    output = ""
    try:
        prepared = prepared or prepare_case(row, api)
        account, phone, new_phone = prepared["account"], prepared["phone"], prepared["new_phone"]
        remaining = prepared["ready_at"] - time.monotonic()
        if remaining > 0:
            raise StageFailure("existing_account_precondition_not_ready")
        if account:
            api.refresh_owned(account)
        serial_args = ["-s", args.serial]
        for adb_args in (["shell", "am", "force-stop", APP], ["shell", "pm", "clear", APP]):
            code, _ = command(["adb", *serial_args, *adb_args], timeout=30)
            if code:
                raise StageFailure("device_reset_failed")
        receipt = {"run_id": api.run_id, "phone": phone, "new_account": prepared["new_account"]}
        if new_phone != phone:
            receipt["new_phone"] = new_phone
        config = json.dumps(receipt).encode()
        # Static shell code and JSON on stdin: values never become executable shell arguments.
        code, _ = command(["adb", *serial_args, "shell", "run-as", APP, "sh", "-c",
                           "'umask 077; mkdir -p files; cat > files/stage-run.json'"], timeout=30, data=config)
        if code:
            raise StageFailure("private_run_config_failed")
        configured = True
        flow_private = private / row["id"]
        flow_private.mkdir()
        if row["layer"] in ("maestro", "checkpoint"):
            env = {"STAGE_PHONE": phone[2:], "STAGE_PHONE_DISPLAY": display(phone),
                   "STAGE_NEW_PHONE": new_phone[2:], "STAGE_NEW_PHONE_DISPLAY": display(new_phone),
                   "STAGE_UPLOAD_DATE": upload_date(), "STAGE_BASELINE_COUNT": "1",
                   "STAGE_TODAY": upload_date(),
                   "STAGE_SETUP_MODE": "fresh" if row.get("setup") == "first-session-fresh" else "seeded",
                   "STAGE_PROCESS_QUESTION": "#FIN362_TOOLS FIN386 process-death " + api.run_id,
                   "STAGE_FINANCIAL_QUESTION": "#FIN362_TOOLS Покажи доходы и расходы по моим данным."}
            def part(flow, name):
                folder = flow_private / name
                folder.mkdir()
                xml = folder / "report.xml"
                cmd = ["maestro", "--device", args.serial, "test", "--format", "junit",
                       "--output", str(xml), "--test-output-dir", str(folder / "materials")]
                for key, value in env.items():
                    cmd.extend(["-e", key + "=" + value])
                cmd.append(str(ROOT / flow))
                code, output = command(cmd, timeout=args.case_timeout)
                return code, output, xml.is_file() and report_passed(ET.parse(xml).getroot())
            code, output, passed = part(row.get("before_flow", row["stage_flow"]), "before" if row["layer"] == "checkpoint" else "flow")
            if row["layer"] == "checkpoint" and code == 0 and passed:
                result["checkpoint"] = process_death_checkpoint(args.serial, api.run_id)
                code, after_output, passed = part(row["after_flow"], "after")
                output += "\n--- restored client ---\n" + after_output
            result.update(outcome="passed" if code == 0 and passed else "failed",
                          reason="" if code == 0 and passed else ("flow_timeout" if code == 124 else "flow_or_report_failed"))
        else:
            native_reports = ROOT / "app/build/outputs/androidTest-results/connected"
            if native_reports.exists():
                shutil.rmtree(native_reports)
            cmd = ["./gradlew", ":app:connectedStageE2eAndroidTest", "--serial", args.serial, "--console=plain",
                   "-Pandroid.testInstrumentationRunnerArguments.stageRunId=" + api.run_id,
                   "-Pandroid.testInstrumentationRunnerArguments.stagePhone=" + phone]
            code, output = command(cmd, timeout=min(900, args.case_timeout * 2))
            xmls = list((ROOT / "app/build/outputs/androidTest-results/connected").rglob("TEST-*.xml"))
            classname, method = row["native_test"].split("#", 1)
            passed = sum(report_passed(ET.parse(xml).getroot(), (classname, method)) for xml in xmls) == 1
            result.update(outcome="passed" if code == 0 and passed else "failed",
                          reason="" if code == 0 and passed else "native_or_report_failed")
        (target / "execution.log").write_text(sanitize(output, api.secrets))
    except (StageFailure, OSError, KeyError, ValueError, ET.ParseError) as error:
        result["outcome"] = "failed"
        result["reason"] = str(error) if isinstance(error, StageFailure) else "harness_" + type(error).__name__
    if flow_private and flow_private.exists():
        save_materials(flow_private, target, api.secrets)
        (target / "execution.log").write_text(sanitize(output, api.secrets))
    try:
        code, evidence_text = command(["adb", "-s", args.serial, "exec-out", "run-as", APP, "cat", "files/stage-evidence.json"], timeout=20) if configured else (1, "")
        if row["layer"] == "native":
            try:
                evidence = json.loads(evidence_text)
            except ValueError:
                source, evidence = native_proof(output, api.run_id)
                result["native_proof_source"] = source
            code = 0
        if code == 0:
            if row["layer"] != "native":
                evidence = json.loads(evidence_text)
            if not isinstance(evidence, dict):
                raise StageFailure("invalid_client_evidence")
            if evidence.get("run_id") != api.run_id or not isinstance(evidence.get("has_client_injections"), bool):
                raise StageFailure("invalid_client_evidence")
            counts = evidence.get("counts", {})
            if not isinstance(counts, dict) or not all(re.fullmatch(r"[a-z_0-9]+", key) and type(value) is int and value >= 0 for key, value in counts.items()):
                raise StageFailure("invalid_client_evidence_counts")
            result["client_evidence"] = {"has_client_injections": evidence["has_client_injections"], "counts": counts}
            for name, minimum in row.get("required_counts", {}).items():
                if type(minimum) is not int or minimum < 1 or counts.get(name, 0) < minimum:
                    raise StageFailure("required_transport_observation_missing")
            for registration in evidence.get("successful_registrations", []):
                api.adopt_registration(registration["phone"], registration["user_id"])
            if row.get("setup") == "new-empty" and not evidence.get("successful_registrations") and result["outcome"] == "passed":
                raise StageFailure("missing_new_registration_ownership")
            if evidence["has_client_injections"] and row["provenance"] == "real-stage":
                raise StageFailure("undeclared_client_injection")
            # Reuse the first session for cleanup too. A logout case may defer its cleanup to suite end.
            registered = {item["phone"] for item in evidence.get("successful_registrations", [])}
            if registered and not args.retain_owned:
                cleanup_link = "pfe2e://config?cleanup_run=owned"
                command(["adb", "-s", args.serial, "shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", "'" + cleanup_link + "'", "-p", APP], timeout=20)
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    _, deleted_text = command(["adb", "-s", args.serial, "exec-out", "run-as", APP, "cat", "files/stage-evidence.json"], timeout=10)
                    updated = json.loads(deleted_text)
                    if updated.get("run_id") != api.run_id:
                        raise StageFailure("invalid_cleanup_receipt")
                    deleted = set(updated.get("deleted_phones", []))
                    if deleted & registered:
                        for owned in api.owned:
                            if owned.phone in deleted & registered:
                                owned.deleted = True
                        break
                    time.sleep(0.5)
        elif result["outcome"] == "passed":
            raise StageFailure("missing_client_evidence")
        code, output = command(["adb", "-s", args.serial, "logcat", "-b", "crash", "-d"], timeout=20) if configured else (1, "")
        if code == 0:
            (target / "crash.log").write_text(sanitize(output, api.secrets))
    except (StageFailure, OSError, KeyError, ValueError, ET.ParseError) as error:
        evidence_failure = str(error) if isinstance(error, StageFailure) else "harness_" + type(error).__name__
        if result["outcome"] == "passed":
            result["outcome"] = "failed"
            result["reason"] = evidence_failure
        else:
            result["evidence_failure"] = evidence_failure
    if configured:
        _, status_text = command(["adb", "-s", args.serial, "exec-out", "run-as", APP, "cat", "files/stage-control-status.json"], timeout=10)
        status = control_status(status_text, api.run_id)
        if status is not None:
            result["control_status"] = status
    result["seconds"] = round(time.monotonic() - started, 3)
    return result


def upload_date():
    # The backend profile is explicitly Moscow; never inherit the runner host timezone.
    today = dt.datetime.now(dt.timezone(dt.timedelta(hours=3))).date()
    months = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"]
    return str(today.day) + ".*" + months[today.month - 1]


def cleanup_accounts(api, report, accounts, retain, defer_new_sessions=False):
    for account in accounts:
        if defer_new_sessions and not account.access and not account.deleted and not retain:
            continue
        outcome = "retained"
        if not retain:
            try:
                api.delete_owned(account)
                outcome = "deleted"
            except (StageFailure, OSError, KeyError, ValueError):
                outcome = "cleanup_unverified"
        report["cleanup"].append({"account": hashlib.sha256(account.user_id.encode()).hexdigest()[:12], "outcome": outcome})


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", action="append", default=[])
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--case-timeout", type=int, default=300)
    parser.add_argument("--no-build", action="store_true")
    parser.add_argument("--inventory-only", action="store_true")
    parser.add_argument("--prepare-report", action="store_true", help="Initialize the full not-run denominator before CI preparation; no device or network")
    parser.add_argument("--retain-owned", action="store_true", help="Keep only this run's newly registered accounts; report them as retained")
    args = parser.parse_args(argv)
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("use a disposable emulator serial")
    if not 30 <= args.case_timeout <= 900:
        parser.error("case timeout must be 30..900 seconds")
    manifest = inventory(ROOT / "scripts/stage-cases.json")
    rows = manifest["cases"] + manifest.get("additional_cases", [])
    if args.case:
        if set(args.case) - {row["id"] for row in rows}:
            parser.error("unknown case")
        rows = [row for row in rows if row["id"] in args.case]
    if args.inventory_only:
        print(json.dumps({"inventory": len(rows), "ready": sum(row["status"] == "ready" for row in rows), "ui_executed": False}))
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    task_lock = (OUT.parent / ".stage-ui.lock").open("w")
    try:
        fcntl.flock(task_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise StageFailure("another_stage_runner_is_active") from None
    if OUT.exists():
        history = OUT.parent / "stage-ui-history" / str(uuid.uuid4())
        history.parent.mkdir(parents=True, exist_ok=True)
        OUT.rename(history)
    OUT.mkdir(parents=True, exist_ok=True)
    run_id = str(uuid.uuid4())
    version_text = (ROOT / "app/build.gradle.kts").read_text()
    version = re.search(r'val appVersionName = "([0-9.]+)"', version_text).group(1)
    api = StageApi(run_id, "android/" + version + "+1")
    report = {"schema_version": 1, "run_id": run_id, "environment": "https://stage.plainfin.ru/",
              "provider": "fake-phone/fake-LLM", "full_inventory": not bool(args.case), "full_acceptance": False,
              "started_at": dt.datetime.now(dt.timezone.utc).isoformat(), "results": [], "cleanup": []}
    report["provider_scenarios_expected_sha256"] = manifest.get("provider_scenarios", {}).get("sha256")
    report["provider_scenarios_runtime_sha256_verified"] = False
    for row in rows:
        report["results"].append({key: row[key] for key in ("id", "source", "qase_id", "provenance") if key in row} |
                                 {"outcome": "not_run", "reason": "runner_did_not_reach_case"})
    write_report(report)
    if args.prepare_report:
        report["environment_failure"] = "device_runner_not_started"
        write_report(report)
        return 0
    try:
        for binary in ("adb", "maestro"):
            if shutil.which(binary) is None:
                raise StageFailure("missing_" + binary)
        if not args.no_build:
            code, output = command(["./gradlew", ":app:assembleStageE2e", ":app:assembleStageE2eAndroidTest", "--console=plain"], timeout=1200)
            (OUT / "build.log").write_text(sanitize(output, api.secrets))
            if code:
                raise StageFailure("stage_e2e_build_failed")
        apk = ROOT / "app/build/outputs/apk/stage/e2e/app-stage-e2e.apk"
        report["apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
        _, source_commit = command(["git", "rev-parse", "HEAD"], timeout=10)
        report["source_commit"] = source_commit.strip() if re.fullmatch(r"[0-9a-f]{40}\s*", source_commit) else "unknown"
        _, source_status = command(["git", "status", "--porcelain"], timeout=10)
        report["source_dirty"] = bool(source_status.strip())
        _, device_api = command(["adb", "-s", args.serial, "shell", "getprop", "ro.build.version.sdk"], timeout=10)
        report["device_api"] = int(device_api.strip()) if device_api.strip().isdigit() else None
        write_report(report)
        code, output = command(["adb", "-s", args.serial, "install", "-r", "-t", str(apk)], timeout=120)
        (OUT / "install.log").write_text(sanitize(output))
        if code:
            raise StageFailure("stage_e2e_install_failed")
        with tempfile.TemporaryDirectory(prefix="fin386-private-") as folder:
            prepared_cases = {}
            # Existing-account login has an aged-identity precondition. Independent first-session flows run first.
            execution = sorted(enumerate(rows), key=lambda pair: pair[1].get("setup") in ("existing-seeded", "existing-and-new"))
            report["execution_order"] = [row["id"] for _, row in execution]
            for index, row in enumerate(rows):
                if row["status"] != "ready":
                    continue
                try:
                    prepared_cases[row["id"]] = prepare_case(row, api)
                    print("[stage] " + row["id"] + " prepared", flush=True)
                except (StageFailure, OSError, KeyError, ValueError) as error:
                    report["results"][index].update(outcome="failed", reason=str(error) if isinstance(error, StageFailure) else "preparation_" + type(error).__name__)
                write_report(report)
            for index, row in execution:
                if row["status"] == "ready" and row["id"] not in prepared_cases:
                    continue
                before = len(api.owned)
                prepared = prepared_cases.get(row["id"])
                report["results"][index].update(outcome="running", reason="")
                write_report(report)
                print("[stage] " + row["id"] + " executing", flush=True)
                report["results"][index] = run_case(row, api, args, Path(folder), prepared)
                # Keep deletion within the access token lifetime, rather than waiting for the whole suite.
                accounts = ([prepared["account"]] if prepared and prepared["account"] else []) + api.owned[before:]
                cleanup_accounts(api, report, accounts, args.retain_owned, defer_new_sessions=True)
                print("[stage] " + row["id"] + " " + report["results"][index]["outcome"], flush=True)
                write_report(report)
    except StageFailure as error:
        report["environment_failure"] = str(error)
    finally:
        recorded = {row["account"] for row in report["cleanup"]}
        unrecorded = [account for account in api.owned if hashlib.sha256(account.user_id.encode()).hexdigest()[:12] not in recorded]
        cleanup_accounts(api, report, unrecorded, args.retain_owned)
        report["public_api_requests"] = api.requests
        report["finished_at"] = dt.datetime.now(dt.timezone.utc).isoformat()
        report["full_acceptance"] = (report["full_inventory"] and len(report["results"]) == len(rows)
                                     and all(row["outcome"] == "passed" for row in report["results"])
                                     and all(row["outcome"] == "deleted" for row in report["cleanup"]))
        write_report(report)
    return 0 if (all(row["outcome"] == "passed" for row in report["results"])
                 and not report.get("environment_failure")
                 and all(row["outcome"] != "cleanup_unverified" for row in report["cleanup"])) else 1


if __name__ == "__main__":
    def interrupted(signum, frame):
        raise StageFailure("runner_interrupted")
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        sys.exit(main())
    except StageFailure as error:
        print("stage-ui: " + str(error), file=sys.stderr)
        sys.exit(1)
