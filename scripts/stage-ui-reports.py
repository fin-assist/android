#!/usr/bin/env python3
"""Merge isolated stage UI workers without losing failed, blocked or missing cases."""
from __future__ import annotations

import argparse
from collections import Counter
import copy
import datetime as dt
import json
import math
from pathlib import Path
import re
import shutil
import sys
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
ENVIRONMENT = "https://stage.plainfin.ru/"
PUBLIC_EXTENSIONS = {".png", ".json", ".xml", ".txt", ".log", ".yaml", ".yml"}
OUTCOMES = {"passed", "failed", "blocked", "not_run", "running", "missing", "cancelled", "timed_out"}


def canonical_uuid(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value
    except ValueError:
        return False


def load_manifest():
    manifest = json.loads((ROOT / "scripts/stage-cases.json").read_text())
    rows = manifest["cases"] + manifest.get("additional_cases", [])
    mapping = json.loads((ROOT / "scripts/qase-cases.json").read_text())["cases"]
    if (len({row["id"] for row in rows}) != len(rows)
            or {row["source"]: row["qase_id"] for row in rows} != mapping):
        raise ValueError("invalid_stage_inventory")
    return manifest, rows


def public_artifacts(source, destination):
    """Copy only public runner formats; never follow a link or copy private stores."""
    problems = []
    for path in sorted(source.rglob("*")):
        relative = path.relative_to(source)
        if path.is_symlink():
            problems.append("artifact_symlink")
            continue
        if any(part.startswith(".") or part in {"private", "files", "databases", "shared_prefs"} for part in relative.parts):
            continue
        if not path.is_file() or path.suffix.lower() not in PUBLIC_EXTENSIONS:
            continue
        if path.stat().st_size > 20 * 1024 * 1024:
            problems.append("artifact_size_limit")
            continue
        target = destination / relative
        if any(parent.is_symlink() for parent in (target, *target.parents)):
            problems.append("artifact_destination_symlink")
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)
    return sorted(set(problems))


def valid_counts(evidence):
    if not isinstance(evidence, dict) or type(evidence.get("has_client_injections")) is not bool:
        return False
    counts = evidence.get("counts")
    return isinstance(counts, dict) and all(
        isinstance(key, str) and re.fullmatch(r"[a-z_0-9]+", key)
        and type(value) is int and value >= 0 for key, value in counts.items()) and counts.get("real_requests", 0) > 0


def case_problems(row, expected):
    problems = []
    for key in ("source", "qase_id", "provenance"):
        if row.get(key) != expected.get(key) or (key == "qase_id" and type(row.get(key)) is not int):
            problems.append("case_" + key + "_mismatch")
    if not isinstance(row.get("outcome"), str) or row["outcome"] not in OUTCOMES:
        problems.append("invalid_case_outcome")
    seconds = row.get("seconds", 0)
    if type(seconds) not in (int, float) or seconds < 0 or seconds > 86_400 or not math.isfinite(seconds):
        problems.append("invalid_case_duration")
    if row.get("outcome") == "passed":
        if expected["status"] != "ready":
            problems.append("blocked_case_claimed_passed")
        evidence = row.get("client_evidence")
        if not valid_counts(evidence):
            problems.append("missing_or_invalid_client_evidence")
        elif expected["provenance"] == "real-stage" and evidence["has_client_injections"]:
            problems.append("undeclared_client_injection")
        counts = evidence["counts"] if valid_counts(evidence) else {}
        required = expected.get("required_counts", {})
        if not isinstance(required, dict) or any(
                not isinstance(key, str) or not re.fullmatch(r"[a-z_0-9]+", key)
                or type(minimum) is not int or minimum < 0 for key, minimum in required.items()):
            problems.append("invalid_required_counts")
        else:
            for key, minimum in required.items():
                if counts.get(key, 0) < minimum:
                    problems.append("required_count_not_observed:" + key)
        if expected["layer"] == "checkpoint":
            checkpoint = row.get("checkpoint", {})
            if (not isinstance(checkpoint, dict) or checkpoint.get("process_disappeared") is not True
                    or type(checkpoint.get("accepted_before_death")) is not int or checkpoint["accepted_before_death"] != 1
                    or checkpoint.get("response_delivered_before_death") is not False
                    or checkpoint.get("server_restarted") is not False
                    or any(counts.get(key, 0) != value for key, value in {
                        "real_ask_requests": 1, "real_ask_accepted": 1,
                        "ask_response_held": 1, "ask_response_released": 0}.items())):
                problems.append("invalid_process_death_evidence")
    return problems


def write_junit(report, path):
    rows, errors = report["results"], report["guard_errors"]
    suite = ET.Element("testsuite", name="Android stage aggregate", tests=str(len(rows) + len(errors)),
                       failures=str(sum(row["outcome"] != "passed" for row in rows) + len(errors)), skipped="0")
    for row in rows:
        item = ET.SubElement(suite, "testcase", name=row["id"], classname="stage-ui", time=str(row.get("seconds", 0)))
        source = row["source"]
        item.set("file", str(ROOT / source) if source.startswith((".maestro/", ".maestro-stage/")) else source)
        if "#" in source:
            classname, name = source.split("#", 1)
            item.set("classname", classname)
            item.set("name", name)
        if row["outcome"] != "passed":
            ET.SubElement(item, "failure", type=row["outcome"], message=row.get("reason", row["outcome"]))
        ET.SubElement(item, "system-out").text = "provenance=" + row["provenance"]
    for index, error in enumerate(errors, 1):
        item = ET.SubElement(suite, "testcase", name=f"stage-report-guard-{index}", classname="stage-report-guard")
        ET.SubElement(item, "failure", type="aggregation_guard", message=error["reason"])
    ET.ElementTree(suite).write(path, encoding="utf-8", xml_declaration=True)


def aggregate(directories, output, manifest, expected):
    output = Path(output).absolute()
    sources = [Path(directory).absolute() for directory in directories]
    if any(path.is_symlink() for path in [output, *sources]):
        raise ValueError("symlink_report_directory")
    output = output.resolve()
    sources = [path.resolve() for path in sources]
    resolved_output = output
    if any(resolved_output.is_relative_to(path.resolve()) or path.resolve().is_relative_to(resolved_output) for path in sources):
        raise ValueError("report_output_overlaps_worker")
    output.mkdir(parents=True, exist_ok=True)
    report = {"schema_version": 1, "aggregate": True, "environment": ENVIRONMENT,
              "provider": "fake-phone/fake-LLM", "full_inventory": False, "full_acceptance": False,
              "finished_at": dt.datetime.now(dt.timezone.utc).isoformat(), "workers": [],
              "results": [], "cleanup": [], "guard_errors": []}
    by_id = {row["id"]: row for row in expected}
    occurrences = {identifier: [] for identifier in by_id}
    worker_reports = []
    def guard(worker, reason):
        report["guard_errors"].append({"worker": worker, "reason": reason})
    for index, source in enumerate(sources, 1):
        worker = {"worker": index, "artifacts": f"workers/{index}", "errors": []}
        report["workers"].append(worker)
        try:
            document = source / "report.json"
            if not document.is_file() or document.is_symlink() or document.stat().st_size > 8 * 1024 * 1024:
                raise ValueError("missing_or_oversized_worker_report")
            def reject_constant(value):
                raise ValueError("non_finite_worker_json")
            data = json.loads(document.read_text(), parse_constant=reject_constant)
            if not isinstance(data, dict) or data.get("schema_version") != 1 or not isinstance(data.get("results"), list):
                raise ValueError("invalid_worker_report")
            worker_reports.append((worker, data))
            for key in ("run_id", "source_commit", "source_dirty", "apk_sha256", "environment", "provider",
                        "provider_scenarios_expected_sha256", "provider_scenarios_runtime_sha256_verified", "provider_scenarios_runtime_sha256",
                        "device_api", "public_api_requests", "started_at", "finished_at", "environment_failure"):
                if key in data:
                    worker[key] = copy.deepcopy(data[key])
            worker["results"] = copy.deepcopy(data["results"])
            worker["cleanup"] = copy.deepcopy(data.get("cleanup", []))
            worker["errors"].extend(public_artifacts(source, output / "workers" / str(index)))
        except (OSError, ValueError, TypeError):
            worker["errors"].append("unreadable_worker_report")
    revisions = {data.get("source_commit") for _, data in worker_reports if isinstance(data.get("source_commit"), str)}
    apks = {data.get("apk_sha256") for _, data in worker_reports if isinstance(data.get("apk_sha256"), str)}
    run_ids = Counter(data.get("run_id") for _, data in worker_reports if isinstance(data.get("run_id"), str))
    for worker, data in worker_reports:
        problems = worker["errors"]
        if not canonical_uuid(data.get("run_id")) or run_ids.get(data.get("run_id"), 0) != 1:
            problems.append("invalid_or_reused_worker_run_id")
        if not isinstance(data.get("source_commit"), str) or not re.fullmatch(r"[0-9a-f]{40}", data["source_commit"]) or len(revisions) != 1:
            problems.append("worker_source_revision_mismatch")
        if data.get("source_dirty") is not False:
            problems.append("worker_source_not_clean")
        if not isinstance(data.get("apk_sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", data["apk_sha256"]) or len(apks) != 1:
            problems.append("worker_apk_mismatch")
        if data.get("environment") != ENVIRONMENT or data.get("provider") != "fake-phone/fake-LLM":
            problems.append("worker_environment_mismatch")
        if data.get("provider_scenarios_expected_sha256") != manifest.get("provider_scenarios", {}).get("sha256"):
            problems.append("worker_provider_fixture_mismatch")
        if type(data.get("provider_scenarios_runtime_sha256_verified")) is not bool:
            problems.append("worker_provider_provenance_missing")
        elif data["provider_scenarios_runtime_sha256_verified"] and data.get("provider_scenarios_runtime_sha256") != manifest.get("provider_scenarios", {}).get("sha256"):
            problems.append("worker_provider_revision_proof_missing")
        if data.get("environment_failure"):
            problems.append("worker_environment_failure")
        try:
            finished = dt.datetime.fromisoformat(data["finished_at"])
            if finished.tzinfo is None:
                raise ValueError("missing timezone")
        except (KeyError, ValueError, TypeError):
            problems.append("worker_not_finished")
        cleanup = data.get("cleanup")
        if not isinstance(cleanup, list) or any(not isinstance(item, dict) or not isinstance(item.get("account"), str)
                                               or not re.fullmatch(r"[0-9a-f]{12}", item["account"])
                                               or item.get("outcome") != "deleted" for item in cleanup):
            problems.append("worker_cleanup_not_verified_deleted")
        if not cleanup and any(isinstance(row, dict) and row.get("outcome") == "passed" for row in data["results"]):
            problems.append("worker_passed_without_cleanup_receipt")
        if isinstance(cleanup, list):
            report["cleanup"].extend({**copy.deepcopy(item), "worker": worker["worker"]} for item in cleanup if isinstance(item, dict))
        for row in data["results"]:
            if not isinstance(row, dict) or not isinstance(row.get("id"), str) or row["id"] not in by_id:
                problems.append("unknown_or_invalid_worker_case")
                continue
            occurrences[row["id"]].append((worker, row))
        worker["errors"] = sorted(set(problems))
    account_workers = {}
    for worker in report["workers"]:
        for item in worker.get("cleanup", []) if isinstance(worker.get("cleanup", []), list) else []:
            if isinstance(item, dict) and isinstance(item.get("account"), str):
                account_workers.setdefault(item["account"], set()).add(worker["worker"])
    for worker in report["workers"]:
        if any(worker["worker"] in owners and len(owners) > 1 for owners in account_workers.values()):
            worker["errors"].append("cleanup_identity_reused_across_workers")
    identity_errors = False
    for expected_row in expected:
        identifier = expected_row["id"]
        found = occurrences[identifier]
        base = {key: expected_row[key] for key in ("id", "source", "qase_id", "provenance", "layer")}
        if len(found) != 1:
            identity_errors = True
            reason = "missing_worker_case" if not found else "duplicate_worker_case"
            result = base | {"outcome": "missing" if not found else "failed", "reason": reason,
                             "workers": [worker["worker"] for worker, _ in found]}
            guard(None, reason + ":" + identifier)
        else:
            worker, row = found[0]
            errors = case_problems(row, expected_row)
            identity_errors |= any(error.startswith("case_") for error in errors)
            result = copy.deepcopy(row) | base | {"worker": worker["worker"], "worker_run_id": worker.get("run_id")}
            if isinstance(row.get("artifacts"), str):
                result["artifacts"] = f"workers/{worker['worker']}/cases/{identifier}"
            if errors or worker["errors"]:
                result["worker_outcome"] = row.get("outcome")
                result.update(outcome="failed", reason="aggregation_validation_failed", validation_errors=errors + worker["errors"])
            for error in errors:
                guard(worker["worker"], error + ":" + identifier)
            if expected_row["status"] == "blocked" and result["outcome"] == "passed":
                result.update(outcome="blocked", reason=expected_row["blocked_reason"])
        report["results"].append(result)
    for worker in report["workers"]:
        for error in worker["errors"]:
            guard(worker["worker"], error)
    report["full_inventory"] = not identity_errors and not any(
        "unknown_or_invalid_worker_case" in worker["errors"] for worker in report["workers"])
    if len(revisions) == 1:
        report["source_commit"] = next(iter(revisions))
    if len(apks) == 1:
        report["apk_sha256"] = next(iter(apks))
    report["source_dirty"] = any(worker.get("source_dirty") is not False for worker in report["workers"])
    report["provider_scenarios_expected_sha256"] = manifest.get("provider_scenarios", {}).get("sha256")
    report["provider_scenarios_runtime_sha256_verified"] = bool(worker_reports) and all(
        data.get("provider_scenarios_runtime_sha256_verified") is True for _, data in worker_reports)
    report["full_acceptance"] = (report["full_inventory"] and not report["guard_errors"]
                                  and all(row["outcome"] == "passed" for row in report["results"])
                                  and all(item["outcome"] == "deleted" for item in report["cleanup"]))
    for filename, writer in (("report.json", lambda path: path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")),
                             ("report.xml", lambda path: write_junit(report, path))):
        temporary = output / (filename + ".tmp")
        if temporary.is_symlink() or (output / filename).is_symlink():
            raise ValueError("symlink_output_report")
        writer(temporary)
        temporary.replace(output / filename)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workers", nargs="+", type=Path, help="Worker build/stage-ui directories")
    parser.add_argument("--output", type=Path, default=ROOT / "build/stage-ui")
    args = parser.parse_args(argv)
    manifest, rows = load_manifest()
    report = aggregate(args.workers, args.output, manifest, rows)
    print(json.dumps({"cases": len(report["results"]), "full_inventory": report["full_inventory"],
                      "full_acceptance": report["full_acceptance"], "guard_errors": len(report["guard_errors"])}))
    return 0 if report["full_acceptance"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, TypeError):
        print("stage-ui-reports: invalid input or output", file=sys.stderr)
        sys.exit(1)
