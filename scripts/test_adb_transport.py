import argparse
import contextlib
import io
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
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
        with patch.object(transport, "run_bounded", return_value=(0, "List of devices attached\nemulator-5582\tdevice\n")):
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
            self.assertEqual(events[-1], ("series_aborted", {"reason": "process_timeout", "completed_repetitions": 1}))

    def test_timeout_kills_owned_process_group(self):
        # Real child sleep exercises the timeout instead of mocking the implementation's timer.
        processes = []
        original_popen = transport.subprocess.Popen

        def start(*args, **kwargs):
            process = original_popen(*args, **kwargs)
            processes.append(process)
            return process

        with patch.object(transport.subprocess, "Popen", side_effect=start):
            status, _ = transport.run_bounded([sys.executable, "-c", "import time;time.sleep(30)"], .15)
        self.assertEqual(status, 124)
        with self.assertRaises(ProcessLookupError):
            os.kill(processes[0].pid, 0)

    def test_successful_flow_with_failed_independent_probe_is_not_stable_transport(self):
        observed = threading.Event()

        def probe(*args):
            observed.set()
            return {"returncode": 1, "state": None, "offline": True}

        def series(*args):
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
