"""Offline contract checks for the opt-in SDK preparation experiment."""
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("sdk_benchmark", Path(__file__).with_name("sdk-preparation-benchmark.py"))
SDK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SDK)
ROOT = Path(__file__).resolve().parents[1]


class BenchmarkTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {
            "GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted", "GITHUB_RUN_ID": "1234",
            "ANDROID_HOME": str(self.root / "sdk"), "RUNNER_OS": "Linux", "RUNNER_ARCH": "X64",
            "ImageOS": "ubuntu24", "ImageVersion": "20261026.1",
            "GITHUB_OUTPUT": str(self.root / "outputs"), "GITHUB_STEP_SUMMARY": str(self.root / "summary"),
        })
        self.env.start()
        self.addCleanup(self.env.stop)
        self.result = patch.object(SDK, "RESULT", self.root / "result.json")
        self.result.start()
        self.addCleanup(self.result.stop)

    def packages(self, revision="1.2.3"):
        manager = SDK.sdk_root() / "cmdline-tools/latest/bin/sdkmanager"
        manager.parent.mkdir(parents=True, exist_ok=True)
        manager.touch()
        for relative in [*SDK.PACKAGES.values(), "platform-tools", "cmdline-tools/latest"]:
            path = SDK.sdk_root() / relative
            path.mkdir(parents=True, exist_ok=True)
            (path / "source.properties").write_text(f"Pkg.Revision={revision}\n")
            (path / "binary").write_bytes(b"package")

    def test_local_execution_cannot_delete_sdk(self):
        self.packages()
        with patch.dict(os.environ, {"RUNNER_ENVIRONMENT": "self-hosted"}):
            with self.assertRaisesRegex(ValueError, "disposable"):
                SDK.start("baseline", 1)
        self.assertTrue((SDK.sdk_root() / "emulator/binary").exists())

    def test_start_deletes_only_selected_packages_and_retains_preinstalled_revisions(self):
        self.packages("2.3.4")
        untouched = SDK.sdk_root() / ".android/adbkey"
        untouched.parent.mkdir()
        untouched.write_text("do not touch")
        SDK.start("warm", 3)
        self.assertFalse((SDK.sdk_root() / "emulator").exists())
        self.assertFalse((SDK.sdk_root() / SDK.PACKAGES["image"]).exists())
        self.assertTrue((SDK.sdk_root() / "platform-tools/binary").exists())
        self.assertEqual(untouched.read_text(), "do not touch")
        state = json.loads(SDK.RESULT.read_text())
        self.assertEqual(state["hosted_preinstalled_packages"]["image"]["revision"], "2.3.4")

    def test_symlink_directory_cannot_delete_another_directory(self):
        outside = self.root / "other"
        outside.mkdir()
        manager = SDK.sdk_root() / "cmdline-tools/latest/bin/sdkmanager"
        manager.parent.mkdir(parents=True)
        manager.touch()
        (SDK.sdk_root() / "emulator").symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "symlink"):
            SDK.start("seed", 1)
        self.assertTrue(outside.exists())

    def test_missing_or_invalid_revision_rejected(self):
        self.packages()
        p = SDK.sdk_root() / "emulator/source.properties"
        for value in ["", "Pkg.Revision=oops", "Pkg.Revision=1\nPkg.Revision=2"]:
            p.write_text(value)
            with self.assertRaises(ValueError):
                SDK.manifest(SDK.sdk_root())
        p.unlink()
        with self.assertRaises(FileNotFoundError):
            SDK.manifest(SDK.sdk_root())

    def test_runner_and_revision_mismatch_invalidate_comparison(self):
        self.packages()
        expected = SDK.manifest(SDK.sdk_root())
        SDK.check_expected(expected, expected)
        with patch.dict(os.environ, {"ImageVersion": "other"}):
            with self.assertRaisesRegex(ValueError, "comparison invalid"):
                SDK.check_expected(SDK.manifest(SDK.sdk_root()), expected)
        self.packages("2.0")
        with self.assertRaises(ValueError):
            SDK.check_expected(SDK.manifest(SDK.sdk_root()), expected)

    def test_keys_separate_packages_runs_and_platform_without_host_cpu(self):
        self.packages()
        manifest = SDK.manifest(SDK.sdk_root())
        key = SDK.cache_key("emulator", manifest, "1234")
        self.assertTrue(key.startswith("sdk-prep-v1-1234-emulator-"))
        self.assertNotEqual(key, SDK.cache_key("image", manifest, "1234"))
        self.assertNotEqual(key, SDK.cache_key("emulator", manifest, "1235"))
        with patch.dict(os.environ, {"RUNNER_ARCH": "ARM64"}):
            self.assertNotEqual(key, SDK.cache_key("emulator", SDK.manifest(SDK.sdk_root()), "1234"))
        with self.assertRaises(ValueError):
            SDK.cache_key("emulator", manifest, "bad\nkey")

    def test_prepare_seed_and_warm_use_identical_online_commands_and_timeouts(self):
        self.packages()
        commands = []
        def install(command, **kwargs):
            commands.append((command, kwargs["timeout"]))
            self.packages()
        with patch.object(SDK.subprocess, "run", side_effect=install):
            SDK.start("seed", 1)
            SDK.prepare("seed", "unused")
            expected = self.root / "manifest.json"
            outputs = (self.root / "outputs").read_text()
            self.assertIn("emulator-key=sdk-prep-v1-1234-emulator-", outputs)
            SDK.finish()
            self.assertGreaterEqual(json.loads(SDK.RESULT.read_text())["seconds"]["total"], 0)
            SDK.start("warm", 1)
            SDK.prepare("warm", str(expected))
            SDK.start("baseline", 4)
            SDK.prepare("baseline", str(expected))
        self.assertEqual(commands[:2], commands[2:4])
        self.assertEqual(commands[:2], commands[4:])
        self.assertEqual(commands[0][1], 120)
        self.assertEqual(commands[1][1], 600)
        self.assertEqual(commands[1][0][1:], ["--install", "platform-tools", "emulator", SDK.IMAGE, "--channel=0"])

    def test_missing_manager_refuses_deletion(self):
        self.packages()
        (SDK.sdk_root() / "cmdline-tools/latest/bin/sdkmanager").unlink()
        with self.assertRaisesRegex(ValueError, "refusing package deletion"):
            SDK.start("seed", 1)
        self.assertTrue((SDK.sdk_root() / "emulator/binary").exists())

    def test_mismatch_keeps_actual_manifest_and_red_result(self):
        self.packages()
        expected = self.root / "expected.json"
        expected.write_text(json.dumps(SDK.manifest(SDK.sdk_root())))
        SDK.start("warm", 2)
        with patch.object(SDK.subprocess, "run", side_effect=lambda *a, **kw: self.packages("2.0")):
            with self.assertRaisesRegex(ValueError, "comparison invalid"):
                SDK.prepare("warm", str(expected))
        state = json.loads(SDK.RESULT.read_text())
        self.assertFalse(state["comparison_valid"])
        self.assertEqual(state["manifest"]["packages"]["image"]["revision"], "2.0")
        self.assertIn("install_finished", state)
        with self.assertRaisesRegex(ValueError, "cannot be reported"):
            SDK.finish()
        self.assertNotIn("emulator-key=", (self.root / "outputs").read_text())

    def test_sdk_timeout_leaves_no_success_or_cache_key(self):
        self.packages()
        SDK.start("seed", 1)
        with patch.object(SDK.subprocess, "run", side_effect=SDK.subprocess.TimeoutExpired("sdkmanager", 120)):
            with self.assertRaises(SDK.subprocess.TimeoutExpired):
                SDK.prepare("seed", "unused")
        self.assertNotIn("comparison_valid", json.loads(SDK.RESULT.read_text()))
        self.assertNotIn("emulator-key=", (self.root / "outputs").read_text())

    def test_sdk_concurrency_group_requires_isolating_suffix(self):
        route = (ROOT / ".github/workflows/e2e.yml").read_text()
        suffix = "${{ inputs.sdk_benchmark && '-sdk-benchmark' || '' }}"
        expected = "  group: e2e-${{ github.ref }}" + suffix

        def require_isolated_group(workflow):
            # Missing/false input retains the ordinary UI group; true uses a distinct SDK group.
            self.assertIn(expected, workflow.splitlines())

        require_isolated_group(route)
        # Prove the guard rejects the regression that lets an SDK run cancel ordinary UI.
        with self.assertRaises(AssertionError):
            require_isolated_group(route.replace(suffix, ""))

    def test_reusable_manual_route_warm_exact_hits_seed_only_writer(self):
        text = (ROOT / ".github/workflows/sdk-preparation-benchmark.yml").read_text()
        self.assertIn("on:\n  workflow_call:\n", text)
        self.assertNotRegex(text, r"(?m)^  (push|pull_request|schedule):")
        seed, measure = text.split("\n  measure:\n")
        self.assertEqual(seed.count("uses: actions/cache/save@"), 2)
        self.assertNotIn("actions/cache/save@", measure)
        self.assertEqual(measure.count("fail-on-cache-miss: true"), 2)
        self.assertIn('test "$EMULATOR_HIT" = true && test "$IMAGE_HIT" = true', measure)
        self.assertIn("shard: [1, 2, 3, 4]", measure)
        self.assertEqual(text.count("timeout-minutes: 15"), 2)
        self.assertNotRegex(text, r"android-emulator-runner|gradlew|e2e\.sh|maestro|adb ")
        route = (ROOT / ".github/workflows/e2e.yml").read_text()
        self.assertIn("type: boolean\n        default: false", route)
        self.assertIn("if: github.event_name == 'workflow_dispatch' && inputs.sdk_benchmark", route)
        self.assertIn("if: github.event_name != 'workflow_dispatch' || !inputs.sdk_benchmark", route)
        self.assertIn("if: ${{ !cancelled() && !inputs.sdk_benchmark }}", route)
        self.assertIn("uses: ./.github/workflows/sdk-preparation-benchmark.yml", route)


if __name__ == "__main__":
    unittest.main()
