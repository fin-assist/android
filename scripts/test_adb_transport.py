import argparse
import contextlib
import io
import importlib.util
import json
import os
import signal
import subprocess
from pathlib import Path
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("adb_transport", Path(__file__).with_name("adb-transport.py"))
transport = importlib.util.module_from_spec(spec)
spec.loader.exec_module(transport)


class TransportTests(unittest.TestCase):
    def test_connection_stays_on_owned_emulator(self):
        self.assertEqual(transport.maestro_connection("emulator-5582", "server"), ["--device", "emulator-5582"])
        self.assertEqual(transport.maestro_connection("emulator-5582", "direct"),
                         ["--platform", "android", "--host", "127.0.0.1", "--port", "5583"])
        for serial in ("usb-serial", "emulator-5583", "emulator-5037", "emulator-5582;anything", "localhost:5583"):
            with self.subTest(serial=serial), self.assertRaises(ValueError):
                transport.maestro_connection(serial, "direct")
        with self.assertRaises(ValueError):
            transport.maestro_connection("emulator-5582", "unknown")

    def test_probe_is_same_for_both_environments_and_keeps_assertions(self):
        mock = transport.flow_text("ru.finassist.pf.mock.e2e", "auth.phone")
        stage = transport.flow_text("ru.finassist.pf.stage", "auth.phone")
        self.assertEqual(mock.split("\n", 1)[1], stage.split("\n", 1)[1])
        self.assertEqual(mock.count("- assertVisible:"), 2)
        self.assertIn('id: "auth\\\\.phone"', mock)
        self.assertIn("stopApp: false", mock)
        self.assertNotIn("clearState", mock)
        self.assertNotIn("retry", mock)
        for app_id, root_id in (("ru.finassist.pf", "auth.phone"), ("com.android.settings", "auth.phone"),
                                ("ru.finassist.pf.stage", "auth.phone\n- clearState")):
            with self.subTest(app_id=app_id), self.assertRaises(ValueError):
                transport.flow_text(app_id, root_id)

    def test_boot_gate_and_avd_ownership_fail_closed(self):
        events = []
        with patch.object(transport, "run_bounded", side_effect=[(0, "1\n"), (0, "someone_else\nOK\n")]):
            with self.assertRaisesRegex(RuntimeError, "explicitly named"):
                transport.wait_for_boot("adb", "emulator-5582", "fin390_transport", 1,
                                        lambda *event: events.append(event))
        self.assertNotIn("ownership", [event[0] for event in events])
        with patch.object(transport, "run_bounded", return_value=(1, "offline")), \
                patch.object(transport.time, "monotonic", side_effect=[0, 2]):
            with self.assertRaisesRegex(RuntimeError, "finish booting"):
                transport.wait_for_boot("adb", "emulator-5582", "fin390_transport", 1, lambda *event: None)
        with patch.object(transport, "run_bounded", side_effect=[(0, "1\n"), (0, "fin390_transport\nOK\n")]):
            transport.wait_for_boot("adb", "emulator-5582", "fin390_transport", 1, lambda *event: None)

    def test_server_refuses_any_other_listed_device_without_touching_it(self):
        events = []
        outputs = ["List of devices attached\nemulator-5582\tdevice\nemulator-5580\tdevice\n",
                   "List of devices attached\nemulator-5582\tdevice\nusb-serial\tunauthorized\n",
                   "List of devices attached\nemulator-5582\toffline\n"]
        for output in outputs:
            with self.subTest(output=output), patch.object(transport, "run_bounded", return_value=(0, output)) as run:
                with self.assertRaisesRegex(RuntimeError, "only listed device"):
                    transport.verify_server_isolation("adb", "emulator-5582", lambda *event: events.append(event))
                self.assertEqual(run.call_count, 1)
                self.assertEqual(run.call_args.args[0], ["adb", "-H", "127.0.0.1", "-P", "5037", "devices"])
        self.assertEqual(events, [])
        for state in ("connecting", "host", "rescue", "detached", "no permissions (missing udev rules)", "future_state"):
            output = "List of devices attached\nemulator-5582\tdevice\nother\t" + state + "\n"
            with self.subTest(state=state), patch.object(transport, "run_bounded", return_value=(0, output)):
                with self.assertRaises(transport.ProbeAbort):
                    transport.verify_server_isolation("adb", "emulator-5582", lambda *event: None)
        with patch.object(transport, "run_bounded", return_value=(0, "* daemon not running; starting now at tcp:5037\n* daemon started successfully\nList of devices attached\nemulator-5582\tdevice\n")):
            transport.verify_server_isolation("adb", "emulator-5582", lambda *event: events.append(event))
        self.assertEqual(events, [("server_isolation", {"verified": True, "listed_devices": 1})])

    def test_timeline_never_copies_arbitrary_device_output(self):
        for raw in ("secret token and device identity", "error: device offline", "device\n"):
            with patch.object(transport, "run_bounded", return_value=(1, raw)) as command:
                event = transport.probe("adb", "emulator-5582", ["get-state"])
            self.assertEqual(command.call_args.args[0], ["adb", "-H", "127.0.0.1", "-P", "5037", "-s", "emulator-5582", "get-state"])
            self.assertNotIn("secret", json.dumps(event))
            self.assertEqual(event["offline"], "device offline" in raw)

    def test_failed_iteration_is_kept_after_later_pass_and_all_fixed_runs_execute(self):
        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(out=Path(directory), serial="emulator-5582", transport="server",
                                      maestro="maestro", adb="adb", repetitions=3, timeout=10)
            codes = iter([1, 1, 0])
            calls = []

            def run(command, timeout, **kwargs):
                calls.append(command)
                return next(codes), ""

            result = transport.run_series(args, {}, lambda *event: None, run=run,
                                          verify_isolation=lambda *args: None)
            self.assertEqual([item["returncode"] for item in result], [1, 1, 0])
            self.assertEqual(len(calls), 3)
            self.assertEqual(json.loads((args.out / "results.json").read_text()), result)
            self.assertEqual(len({tuple(command[:3]) for command in calls}), 1)


    def test_new_device_before_second_server_iteration_prevents_second_maestro_launch(self):
        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(out=Path(directory), serial="emulator-5582", transport="server",
                                      maestro="maestro", adb="adb", repetitions=3, timeout=10)
            outputs = [(0, "List of devices attached\nemulator-5582\tdevice\n"),
                       (0, "List of devices attached\nemulator-5582\tdevice\nemulator-5580\tdevice\n")]
            calls = []
            events = []

            def run(command, timeout, **kwargs):
                calls.append(command)
                return 0, ""

            with patch.object(transport, "run_bounded", side_effect=outputs):
                with self.assertRaisesRegex(RuntimeError, "only listed device"):
                    transport.run_series(args, {}, lambda *event: events.append(event), run=run)
            self.assertEqual(len(calls), 1)
            self.assertEqual(sum(event[0] == "maestro_start" for event in events), 1)
            self.assertFalse((args.out / "2").exists())
            self.assertEqual(json.loads((args.out / "results.json").read_text())[0]["returncode"], 0)

    def test_timeout_stops_series_before_stale_driver_can_pollute_more_iterations(self):
        with tempfile.TemporaryDirectory() as directory:
            args = argparse.Namespace(out=Path(directory), serial="emulator-5582", transport="server",
                                      maestro="maestro", adb="adb", repetitions=3, timeout=10)
            events = []
            result = transport.run_series(args, {}, lambda *event: events.append(event),
                                          run=lambda *args, **kwargs: (124, ""), verify_isolation=lambda *args: None)
            self.assertEqual(len(result), 1)
            self.assertEqual(result[0]["returncode"], 124)
            self.assertEqual(events[-1], ("series_aborted", {"reason": "process_timeout", "completed_repetitions": 0}))

    def _assert_real_process_group_terminated(self, interrupt):
        processes = []
        original_popen = subprocess.Popen
        with tempfile.TemporaryDirectory() as directory:
            pid_file = Path(directory) / "pids.json"
            launcher = ("import json,os,subprocess,sys,time;from pathlib import Path;"
                        "child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'],"
                        "stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);"
                        f"marker=Path({str(pid_file)!r});tmp=marker.with_suffix('.tmp');"
                        "tmp.write_text(json.dumps([os.getpid(),child.pid]));tmp.replace(marker);"
                        "time.sleep(60)")
            pids = []

            def start(*args, **kwargs):
                process = original_popen(*args, **kwargs)
                processes.append(process)
                # Establish a real descendant before communicate starts its actual timeout.
                deadline = time.monotonic() + 5
                while not pid_file.exists() and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertTrue(pid_file.exists(), "launcher did not create its child")
                pids.extend(json.loads(pid_file.read_text()))
                self.assertEqual(os.getpgid(pids[1]), process.pid)
                if interrupt:
                    communicate = process.communicate
                    first_call = True

                    def interrupted(*args, **kwargs):
                        nonlocal first_call
                        if first_call:
                            first_call = False
                            raise KeyboardInterrupt()
                        return communicate(*args, **kwargs)

                    process.communicate = interrupted
                return process

            def alive(pid):
                try:
                    os.kill(pid, 0)
                except ProcessLookupError:
                    return False
                status = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True,
                                        text=True, timeout=1).stdout.strip()
                # An orphan may briefly remain a zombie until init reaps it; it cannot run.
                return bool(status) and not status.startswith("Z")

            try:
                with (Path(directory) / "console.log").open("w") as output:
                    with patch.object(transport.subprocess, "Popen", side_effect=start):
                        if interrupt:
                            with self.assertRaises(KeyboardInterrupt):
                                transport.run_bounded([sys.executable, "-c", launcher], .15, output=output)
                        else:
                            code, _ = transport.run_bounded([sys.executable, "-c", launcher], .15, output=output)
                            self.assertEqual(code, 124)
                self.assertEqual(len(pids), 2)
                deadline = time.monotonic() + 2
                while any(alive(pid) for pid in pids) and time.monotonic() < deadline:
                    time.sleep(.02)
                for pid in pids:
                    self.assertFalse(alive(pid), f"owned process {pid} survived group termination")
            finally:
                # A regression must fail the test without leaving a live child behind.
                for process in processes:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait(timeout=2)

    def test_timeout_kills_launcher_and_real_descendant(self):
        self._assert_real_process_group_terminated(interrupt=False)

    def test_keyboard_interrupt_kills_launcher_and_real_descendant(self):
        self._assert_real_process_group_terminated(interrupt=True)

    def test_successful_flow_with_failed_independent_probe_is_not_stable_transport(self):
        observed = threading.Event()

        def probe(*args):
            observed.set()
            return {"returncode": 1, "state": None, "offline": True}

        def series(*args, **kwargs):
            self.assertTrue(observed.wait(timeout=2))
            return [{"iteration": 1, "returncode": 0, "seconds": 0}]

        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "new"
            with contextlib.redirect_stdout(io.StringIO()), patch.object(transport, "wait_for_boot"), \
                    patch.object(transport, "verify_server_isolation"), \
                    patch.object(transport, "probe", side_effect=probe), \
                    patch.object(transport, "run_series", side_effect=series):
                status = transport.main(["--serial", "emulator-5582", "--avd-name", "own", "--transport", "server",
                                         "--repetitions", "1", "--out", str(out)])
            summary = json.loads((out / "summary.json").read_text())
            self.assertEqual(status, 1)
            self.assertTrue(summary["flows_passed"])
            self.assertFalse(summary["transport_clean"])
            self.assertFalse(summary["passed"])
            self.assertGreater(summary["probe_failure_count"], 0)
            self.assertEqual(out.stat().st_mode & 0o777, 0o700)

    def test_main_marks_timeout_on_last_or_only_repeat_incomplete(self):
        for codes in ([124], [0, 0, 124]):
            with self.subTest(codes=codes), tempfile.TemporaryDirectory() as directory:
                out = Path(directory) / "new"
                results = [{"iteration": i + 1, "returncode": code, "seconds": 0} for i, code in enumerate(codes)]
                with contextlib.redirect_stdout(io.StringIO()), patch.object(transport, "wait_for_boot"), \
                        patch.object(transport, "probe", return_value={"returncode": 0, "state": "device", "offline": False}), \
                        patch.object(transport, "run_series", return_value=results):
                    code = transport.main(["--serial", "emulator-5582", "--avd-name", "own", "--transport", "direct",
                                           "--repetitions", str(len(codes)), "--out", str(out)])
                summary = json.loads((out / "summary.json").read_text())
                self.assertEqual(code, 1)
                self.assertFalse(summary["complete"])
                self.assertEqual(summary["abort_reason"], "process_timeout")
                self.assertEqual(summary["completed_repetitions"], len(codes) - 1)
                self.assertEqual(summary["recorded_repetitions"], len(codes))

    def test_main_rejects_non_device_state_even_with_successful_adb_exit(self):
        for raw in ("offline\n", "unknown\n", "bootloader\n", ""):
            with self.subTest(raw=raw), tempfile.TemporaryDirectory() as directory:
                out = Path(directory) / "new"
                observed = threading.Event()

                def adb(command, timeout):
                    if command[-1] == "get-state":
                        observed.set()
                        return 0, raw
                    return 0, ""

                def series(*args, **kwargs):
                    self.assertTrue(observed.wait(timeout=2))
                    return [{"iteration": 1, "returncode": 0, "seconds": 0}]

                with contextlib.redirect_stdout(io.StringIO()), patch.object(transport, "wait_for_boot"), \
                        patch.object(transport, "run_bounded", side_effect=adb), \
                        patch.object(transport, "run_series", side_effect=series):
                    code = transport.main(["--serial", "emulator-5582", "--avd-name", "own", "--transport", "direct",
                                           "--repetitions", "1", "--out", str(out)])
                summary = json.loads((out / "summary.json").read_text())
                self.assertEqual(code, 1)
                self.assertTrue(summary["flows_passed"])
                self.assertFalse(summary["transport_clean"])
                self.assertGreater(summary["probe_failure_count"], 0)

    def test_main_preserves_failed_repeat_when_second_inventory_aborts(self):
        original_series = transport.run_series
        outputs = [(0, "List of devices attached\nemulator-5582\tdevice\n"),
                   (0, "List of devices attached\nemulator-5582\tdevice\nother\tconnecting\n")]
        calls = []

        def run(command, timeout, **kwargs):
            calls.append(command)
            return 1, ""

        def series(args, env, event, **kwargs):
            return original_series(args, env, event, run=run, **kwargs)

        with tempfile.TemporaryDirectory() as directory:
            out = Path(directory) / "new"
            with contextlib.redirect_stdout(io.StringIO()), patch.object(transport, "wait_for_boot"), \
                    patch.object(transport, "probe", return_value={"returncode": 0, "state": "device", "offline": False}), \
                    patch.object(transport, "run_bounded", side_effect=outputs), \
                    patch.object(transport, "run_series", side_effect=series):
                code = transport.main(["--serial", "emulator-5582", "--avd-name", "own", "--transport", "server",
                                       "--repetitions", "3", "--out", str(out)])
            summary = json.loads((out / "summary.json").read_text())
            self.assertEqual(code, 1)
            self.assertEqual(len(calls), 1)
            self.assertFalse(summary["complete"])
            self.assertFalse(summary["flows_passed"])
            self.assertEqual(summary["completed_repetitions"], 1)
            self.assertEqual(summary["abort_reason"], "server_isolation")
            self.assertEqual(summary["results"], json.loads((out / "results.json").read_text()))
            self.assertEqual(summary["results"][0]["returncode"], 1)
            phases = [json.loads(line)["phase"] for line in (out / "timeline.jsonl").read_text().splitlines()]
            self.assertIn("series_aborted", phases)
            self.assertNotIn("setup_failed", phases)

    def test_main_restores_signal_handlers_and_persists_interrupted_summary(self):
        managed = (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)
        original_signal = signal.signal
        for interrupted in (False, True):
            with self.subTest(interrupted=interrupted), tempfile.TemporaryDirectory() as directory:
                before = {sig: signal.getsignal(sig) for sig in managed}
                calls = []
                observed = threading.Event()
                worker_threads = []
                out = Path(directory) / "new"

                def install(signum, handler):
                    calls.append(threading.current_thread())
                    return original_signal(signum, handler)

                def probe(*args):
                    worker_threads.append(threading.current_thread())
                    observed.set()
                    return {"returncode": 0, "state": "device", "offline": False}

                def series(*args, results, **kwargs):
                    self.assertTrue(observed.wait(timeout=2))
                    results.append({"iteration": 1, "returncode": 1 if interrupted else 0, "seconds": 0})
                    if interrupted:
                        raise KeyboardInterrupt()
                    return results

                with contextlib.redirect_stdout(io.StringIO()), patch.object(transport, "wait_for_boot"), \
                        patch.object(transport, "probe", side_effect=probe), \
                        patch.object(transport, "run_series", side_effect=series), \
                        patch.object(transport.signal, "signal", side_effect=install):
                    code = transport.main(["--serial", "emulator-5582", "--avd-name", "own", "--transport", "direct",
                                           "--repetitions", "1", "--out", str(out)])
                summary = json.loads((out / "summary.json").read_text())
                self.assertEqual({sig: signal.getsignal(sig) for sig in managed}, before)
                self.assertTrue(calls)
                self.assertTrue(all(thread is threading.main_thread() for thread in calls))
                self.assertTrue(worker_threads)
                self.assertTrue(all(thread is not threading.main_thread() for thread in worker_threads))
                self.assertEqual(code, 130 if interrupted else 0)
                self.assertEqual(summary["complete"], not interrupted)
                if interrupted:
                    self.assertEqual(summary["abort_reason"], "interrupted")
                    self.assertEqual(summary["interrupted_signal"], "SIGINT")
                    self.assertEqual(summary["results"][0]["returncode"], 1)
                    self.assertEqual(summary["completed_repetitions"], 1)

    def _assert_real_runner_signal_cleanup(self, signum):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pids_file = root / "pids.json"
            cleaning = root / "cleaning"
            release = root / "release"
            out = root / "out"
            launcher = ("import os,json,subprocess,sys,time;from pathlib import Path;"
                        "child=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)'],"
                        "stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);"
                        f"marker=Path({str(pids_file)!r});tmp=marker.with_suffix('.tmp');"
                        "tmp.write_text(json.dumps([os.getpid(),child.pid]));tmp.replace(marker);time.sleep(60)")
            wrapper = root / "runner.py"
            wrapper.write_text(
                "import contextlib,importlib.util,sys,time\nfrom pathlib import Path\n"
                f"spec=importlib.util.spec_from_file_location('transport',{str(Path(transport.__file__).resolve())!r})\n"
                "m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)\n"
                "m.wait_for_boot=lambda *args:None\n"
                "m.probe=lambda *args:dict(returncode=0,state='device',offline=False)\n"
                "original_scope=m.interruption_handlers\n"
                "@contextlib.contextmanager\ndef scope():\n"
                " with original_scope() as defer:\n"
                "  def cleanup():\n   defer()\n"
                f"   Path({str(cleaning)!r}).write_text('ready')\n"
                "   deadline=time.monotonic()+5\n"
                f"   while not Path({str(release)!r}).exists() and time.monotonic()<deadline:time.sleep(.01)\n"
                "  yield cleanup\n"
                "m.interruption_handlers=scope\n"
                "def series(args,env,event,results):\n"
                f" with Path({str(root / 'maestro.log')!r}).open('w') as output:\n"
                f"  code,_=m.run_bounded([sys.executable,'-c',{launcher!r}],10,output=output)\n"
                " results.append(dict(iteration=1,returncode=code,seconds=0))\n return results\n"
                "m.run_series=series\n"
                f"raise SystemExit(m.main(['--serial','emulator-5582','--avd-name','own','--transport','direct',"
                f"'--repetitions','1','--out',{str(out)!r}]))\n")
            process = None
            owned = []

            def wait_for(path):
                deadline = time.monotonic() + 5
                while not path.exists() and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertTrue(path.exists(), f"runner did not create {path.name}")

            def alive(pid):
                try:
                    os.kill(pid, 0)
                except ProcessLookupError:
                    return False
                status = subprocess.run(["ps", "-o", "stat=", "-p", str(pid)], capture_output=True,
                                        text=True, timeout=1).stdout.strip()
                return bool(status) and not status.startswith("Z")

            try:
                with (root / "runner.log").open("w") as output:
                    process = subprocess.Popen([sys.executable, str(wrapper)], stdout=output,
                                               stderr=subprocess.STDOUT, start_new_session=True)
                    wait_for(pids_file)
                    owned = json.loads(pids_file.read_text())
                    os.kill(process.pid, signum)
                    wait_for(cleaning)
                    # A second request during cleanup must not skip summary persistence.
                    os.kill(process.pid, signum)
                    release.write_text("continue")
                    self.assertEqual(process.wait(timeout=5), 128 + signum)
                summary = json.loads((out / "summary.json").read_text())
                self.assertFalse(summary["complete"])
                self.assertFalse(summary["passed"])
                self.assertEqual(summary["abort_reason"], "interrupted")
                self.assertEqual(summary["interrupted_signal"], signal.Signals(signum).name)
                for pid in owned:
                    self.assertFalse(alive(pid), f"owned descendant {pid} survived runner signal")
            finally:
                for leader in ([process.pid] if process else []) + owned[:1]:
                    try:
                        os.killpg(leader, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                if process is not None:
                    process.wait(timeout=2)

    def test_sigterm_to_runner_cleans_real_launcher_and_child(self):
        self._assert_real_runner_signal_cleanup(signal.SIGTERM)

    def test_sighup_to_runner_cleans_real_launcher_and_child(self):
        self._assert_real_runner_signal_cleanup(signal.SIGHUP)

    def test_existing_output_cannot_be_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            sentinel = Path(directory) / "summary.json"
            sentinel.write_text("original failed result")
            with self.assertRaises(FileExistsError):
                transport.main(["--serial", "emulator-5582", "--avd-name", "own", "--transport", "server",
                                "--out", directory])
            self.assertEqual(sentinel.read_text(), "original failed result")


if __name__ == "__main__":
    unittest.main()
