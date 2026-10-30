"""Behaviour of the runtime dependency guard, including FIN-184's regression."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('check-compose-classpath.py')
spec = importlib.util.spec_from_file_location('compose_classpath', SCRIPT)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


def aligned():
    versions = dict(zip(guard.MODULES, ('1.7.8', '1.7.8', '2.8.7')))
    return {name: {'baseline': versions.copy(), 'resolved': versions.copy()}
            for name in guard.CONFIGURATIONS}


class ComposeClasspathTest(unittest.TestCase):
    def test_aligned_wrappers(self):
        self.assertEqual([], guard.check(aligned()))

    def test_regression_in_each_variant_and_module(self):
        for configuration in guard.CONFIGURATIONS:
            for module, version in zip(guard.MODULES, ('1.9.0', '1.9.0', '2.10.0')):
                with self.subTest(configuration=configuration, module=module):
                    report = aligned()
                    report[configuration]['resolved'][module] = version
                    self.assertEqual([f'{configuration}: {module} resolved {version}, expected '
                                      f'{report[configuration]["baseline"][module]}'], guard.check(report))

    def test_downgrade_is_also_misalignment(self):
        report = aligned()
        report[guard.CONFIGURATIONS[0]]['resolved'][guard.MODULES[0]] = '1.6.0'
        self.assertIn('resolved 1.6.0, expected 1.7.8', guard.check(report)[0])

    def test_android_implementation_cannot_hide_behind_aligned_wrapper(self):
        report = aligned()
        report[guard.CONFIGURATIONS[0]]['resolved']['androidx.compose.ui:ui-android'] = '1.9.0'
        self.assertIn('ui-android resolved 1.9.0, expected 1.7.8', guard.check(report)[0])

    def test_aligned_android_implementations(self):
        report = aligned()
        for entry in report.values():
            entry['resolved'].update({module + '-android': version for module, version in entry['baseline'].items()})
        self.assertEqual([], guard.check(report))

    def test_missing_configuration_module_or_baseline_fails_closed(self):
        original = aligned()
        for configuration in guard.CONFIGURATIONS:
            report = copy.deepcopy(original)
            del report[configuration]
            self.assertTrue(guard.check(report))
            for section in ('baseline', 'resolved'):
                for module in guard.MODULES:
                    report = copy.deepcopy(original)
                    del report[configuration][section][module]
                    self.assertTrue(guard.check(report))

    def test_cli_success_and_actionable_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.json'
            report = aligned()
            path.write_text(json.dumps(report))
            result = subprocess.run([sys.executable, str(SCRIPT), str(path)],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(0, result.returncode)
            self.assertIn('every checked runtime classpath', result.stdout)
            report['prodReleaseRuntimeClasspath']['resolved'][guard.MODULES[2]] = '2.10.0'
            path.write_text(json.dumps(report))
            result = subprocess.run([sys.executable, str(SCRIPT), str(path)],
                                    capture_output=True, text=True, timeout=10)
            self.assertEqual(1, result.returncode)
            self.assertIn('prodReleaseRuntimeClasspath: androidx.lifecycle:lifecycle-runtime resolved 2.10.0, expected 2.8.7',
                          result.stderr)
            self.assertIn('dependencyInsight', result.stderr)

    def test_cli_rejects_missing_and_malformed_report(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'report.json'
            for content in (None, '{', '[]', '{"mockE2eRuntimeClasspath":null}'):
                with self.subTest(content=content):
                    if content is not None:
                        path.write_text(content)
                    result = subprocess.run([sys.executable, str(SCRIPT), str(path)],
                                            capture_output=True, text=True, timeout=10)
                    self.assertEqual(1, result.returncode)
                    self.assertIn('Invalid Compose classpath report:', result.stderr)


if __name__ == '__main__':
    unittest.main()
