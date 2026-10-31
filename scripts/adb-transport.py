#!/usr/bin/env python3
"""Compare bounded Maestro launch probes on an explicitly owned local emulator."""
from __future__ import annotations

import argparse
from contextlib import contextmanager, suppress
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import threading
import time

APP_IDS = {"ru.finassist.pf.mock.e2e", "ru.finassist.pf.stage", "ru.finassist.pf.stage.e2e"}


class ProbeAbort(RuntimeError):
    """A known diagnostic boundary with a safe, machine-readable reason."""

    def __init__(self, reason, message):
        super().__init__(message)
        self.reason = reason


class ProbeInterrupted(KeyboardInterrupt):
    def __init__(self, signum):
        super().__init__(signal.Signals(signum).name)
        self.signum = signum


@contextmanager
def interruption_handlers():
    """Translate terminal termination into cleanup; handlers only live on the CLI main thread."""
    previous = {}

    def defer_signals():
        for signum in previous:
            signal.signal(signum, signal.SIG_IGN)

    def interrupt(signum, _frame):
        # A second termination request must not interrupt group cleanup or summary persistence.
        defer_signals()
        raise ProbeInterrupted(signum)

    try:
        for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
            previous[signum] = signal.getsignal(signum)
            signal.signal(signum, interrupt)
        yield defer_signals
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)


def timestamp():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def emulator_port(serial):
    match = re.fullmatch(r"emulator-(\d+)", serial)
    port = int(match[1]) if match else 0
    if not 5554 <= port <= 5682 or port % 2:
        raise ValueError("serial must name one local emulator on an even port 5554..5682")
    return port


def maestro_connection(serial, transport):
    port = emulator_port(serial)
    if transport == "direct":
        return ["--platform", "android", "--host", "127.0.0.1", "--port", str(port + 1)]
    if transport == "server":
        return ["--device", serial]
    raise ValueError("unsupported transport")


def run_bounded(command, timeout, *, env=None, output=None):
    """Kill the whole owned command group on timeout or interruption, including Java."""
    process = subprocess.Popen(
        command, stdout=output if output is not None else subprocess.PIPE,
        stderr=subprocess.STDOUT, text=True, env=env, start_new_session=True,
    )
    try:
        stdout, _ = process.communicate(timeout=timeout)
        return process.returncode, stdout or ""
    except subprocess.TimeoutExpired:
        with suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGKILL)
        stdout, _ = process.communicate()
        return 124, stdout or ""
    except BaseException:
        with suppress(ProcessLookupError):
            os.killpg(process.pid, signal.SIGKILL)
        process.communicate()
        raise


def adb_command(adb, serial, *command):
    # Maestro's selected-device route uses the local default server, irrespective of adb env overrides.
    return [adb, "-H", "127.0.0.1", "-P", "5037", "-s", serial, *command]


def probe(adb, serial, command):
    code, output = run_bounded(adb_command(adb, serial, *command), 3)
    # Never copy arbitrary adb output, logcat, keys, host identity or app data into the public timeline.
    state = output.strip() if output.strip() in {"device", "offline", "unknown"} else None
    return {"returncode": code, "state": state, "offline": state == "offline" or "device offline" in output}


def wait_for_boot(adb, serial, avd_name, timeout, event):
    deadline = time.monotonic() + timeout
    while True:
        code, output = run_bounded(adb_command(adb, serial, "shell", "getprop", "sys.boot_completed"), 3)
        event("boot", {"returncode": code, "completed": code == 0 and output.strip() == "1"})
        if code == 0 and output.strip() == "1":
            break
        if time.monotonic() >= deadline:
            raise ProbeAbort("boot_timeout", "owned emulator did not finish booting before the deadline")
        time.sleep(min(1, max(0, deadline - time.monotonic())))
    code, output = run_bounded(adb_command(adb, serial, "emu", "avd", "name"), 3)
    if code or output.splitlines()[:1] != [avd_name]:
        raise ProbeAbort("ownership", "serial does not belong to the explicitly named AVD")
    event("ownership", {"verified": True})


def verify_server_isolation(adb, serial, event):
    code, output = run_bounded([adb, "-H", "127.0.0.1", "-P", "5037", "devices"], 3)
    service_lines = {"List of devices attached", "* daemon not running; starting now at tcp:5037",
                     "* daemon started successfully", "* daemon not running; starting now at tcp:5037 *",
                     "* daemon started successfully *"}
    devices = [line.strip().split() for line in output.splitlines()
               if line.strip() and line.strip() not in service_lines]
    if code or devices != [[serial, "device"]]:
        raise ProbeAbort("server_isolation", "server comparison requires the owned AVD to be the only listed device")
    # Maestro 2.11 createAndroid has a byId -> discover fallback if a selected device disappears.
    # Refuse a comparison with other devices attached instead of disconnecting or touching them.
    event("server_isolation", {"verified": True, "listed_devices": 1})


def flow_text(app_id, root_id):
    if app_id not in APP_IDS or not re.fullmatch(r"[a-zA-Z0-9_.]+", root_id):
        raise ValueError("use a FinAssist test/stage app and a literal resource-id")
    selector = json.dumps(re.escape(root_id))
    return (
        f"appId: {app_id}\n---\n- launchApp\n- assertVisible:\n    id: {selector}\n"
        f"- launchApp:\n    stopApp: false\n- assertVisible:\n    id: {selector}\n"
    )


def run_series(args, env, event, run=run_bounded, verify_isolation=verify_server_isolation, results=None):
    if results is None:
        results = []
    for number in range(1, args.repetitions + 1):
        if args.transport == "server":
            verify_isolation(args.adb, args.serial, event)
        destination = args.out / str(number)
        destination.mkdir()
        command = [args.maestro, *maestro_connection(args.serial, args.transport), "test",
                   str(args.out / "probe.yaml"), "--format", "junit", "--output", str(destination / "report.xml"),
                   "--test-output-dir", str(destination / "materials"), "--debug-output", str(destination / "debug")]
        event("maestro_start", {"iteration": number})
        started = time.monotonic()
        with (destination / "console.private.log").open("w") as output:
            code, _ = run(command, args.timeout, env=env, output=output)
        result = {"iteration": number, "returncode": code, "seconds": round(time.monotonic() - started, 3)}
        results.append(result)
        event("maestro_end", result)
        # A failed repetition stays failed. Later diagnostics never replace it with a green result.
        (args.out / "results.json").write_text(json.dumps(results, indent=2) + "\n")
        if code == 124:
            event("series_aborted", {"reason": "process_timeout", "completed_repetitions": len(results) - 1})
            break
    return results


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--avd-name", required=True, help="own disposable AVD; checked before any flow")
    parser.add_argument("--transport", choices=("server", "direct"), required=True)
    parser.add_argument("--app-id", choices=sorted(APP_IDS), default="ru.finassist.pf.mock.e2e")
    parser.add_argument("--root-id", default="auth.phone")
    parser.add_argument("--repetitions", type=int, default=10)
    parser.add_argument("--timeout", type=int, default=150, help="seconds per Maestro process group")
    parser.add_argument("--boot-timeout", type=int, default=300)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--maestro", default="maestro")
    parser.add_argument("--out", type=Path, required=True, help="new private directory; never reused")
    args = parser.parse_args(argv)
    try:
        emulator_port(args.serial)
        content = flow_text(args.app_id, args.root_id)
        if not 1 <= args.repetitions <= 100 or not 10 <= args.timeout <= 600 or not 1 <= args.boot_timeout <= 600:
            raise ValueError("repetitions must be 1..100; process timeout 10..600s; boot timeout 1..600s")
    except ValueError as error:
        parser.error(str(error))
    args.out = args.out.resolve()
    args.out.mkdir(mode=0o700, parents=True, exist_ok=False)
    env = os.environ.copy()
    env.update(ANDROID_SERIAL=args.serial, MAESTRO_CLI_NO_ANALYTICS="true",
               MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED="true")
    lock = threading.Lock()
    started = time.monotonic()
    timeline = args.out / "timeline.jsonl"

    def event(phase, values):
        record = {"at": timestamp(), "elapsed": round(time.monotonic() - started, 3), "phase": phase, **values}
        with lock, timeline.open("a") as output:
            output.write(json.dumps(record) + "\n")

    summary = {"serial": args.serial, "avd_name": args.avd_name, "transport": args.transport,
               "app_id": args.app_id, "repetitions": args.repetitions,
               "flow_sha256": hashlib.sha256(content.encode()).hexdigest(), "started": timestamp(),
               "complete": False, "passed": False}
    stop = threading.Event()
    monitor_errors = []
    probe_failures = []
    results = []
    setup_complete = False

    def monitor():
        try:
            while not stop.is_set():
                for name, command in (("adb_state", ["get-state"]), ("adb_shell", ["shell", "true"])):
                    sample = probe(args.adb, args.serial, command)
                    if sample["returncode"] or sample["offline"] or (name == "adb_state" and sample["state"] != "device"):
                        probe_failures.append({"probe": name, **sample})
                    event(name, sample)
                stop.wait(.5)
        except OSError as error:
            monitor_errors.append(type(error).__name__)
            event("monitor_failed", {"error": type(error).__name__})

    thread = None
    interrupted_exit_code = 0
    with interruption_handlers() as defer_signals:
        try:
            wait_for_boot(args.adb, args.serial, args.avd_name, args.boot_timeout, event)
            setup_complete = True
            (args.out / "probe.yaml").write_text(content)
            thread = threading.Thread(target=monitor, name="owned-adb-probe", daemon=True)
            thread.start()
            results = run_series(args, env, event, results=results)
        except (RuntimeError, OSError) as error:
            reason = error.reason if isinstance(error, ProbeAbort) else "command_error"
            summary.update(error=type(error).__name__, abort_reason=reason)
            event("series_aborted" if setup_complete else "setup_failed", {"reason": reason})
        except KeyboardInterrupt as error:
            signum = error.signum if isinstance(error, ProbeInterrupted) else signal.SIGINT
            interrupted_exit_code = 128 + signum
            summary.update(abort_reason="interrupted", interrupted_signal=signal.Signals(signum).name)
            event("series_aborted" if setup_complete else "setup_failed",
                  {"reason": "interrupted", "signal": summary["interrupted_signal"]})
        finally:
            defer_signals()
            stop.set()
            if thread is not None:
                thread.join(timeout=8)
            if any(result["returncode"] == 124 for result in results):
                summary["abort_reason"] = "process_timeout"
            summary.update(results=results, recorded_repetitions=len(results),
                           completed_repetitions=sum(result["returncode"] != 124 for result in results),
                           complete=len(results) == args.repetitions and "abort_reason" not in summary,
                           flows_passed=bool(results) and all(result["returncode"] == 0 for result in results))
            summary.update(transport_clean=not probe_failures and not monitor_errors,
                           probe_failure_count=len(probe_failures), monitor_errors=monitor_errors)
            summary["passed"] = summary["complete"] and summary.get("flows_passed", False) and summary["transport_clean"]
            summary["finished"] = timestamp()
            (args.out / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    return interrupted_exit_code or (0 if summary["passed"] else 1)


if __name__ == "__main__":
    raise SystemExit(main())
