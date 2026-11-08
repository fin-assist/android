import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('execution', Path(__file__).with_name('e2e_execution.py'))
execution = importlib.util.module_from_spec(spec)
spec.loader.exec_module(execution)


class ExecutionEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        state = patch.object(execution, 'STATE', self.root / 'execution.json')
        state.start()
        self.addCleanup(state.stop)

    def test_early_setup_failure_is_available_without_any_ui_report_and_without_secret_environment(self):
        with patch.dict(os.environ, {'GITHUB_SHA': 'abc123', 'QASE_API_TOKEN': 'private-value'}):
            execution.update(initialize=True)
            execution.update(result='failed', exit_code=28)
        data = execution.read()
        self.assertEqual(('setup', 'setup', 'failed', 28),
                         (data['phase'], data['category'], data['result'], data['exit_code']))
        self.assertEqual('abc123', data['identity']['GITHUB_SHA'])
        self.assertNotIn('private-value', json.dumps(data))
        self.assertNotIn('QASE_API_TOKEN', json.dumps(data))

    def test_report_cleanup_cannot_turn_a_test_failure_into_a_success(self):
        execution.update(initialize=True)
        execution.update(phase='maestro')
        execution.update(result='failed', exit_code=1)
        execution.update(phase='reporting')
        execution.update(phase='complete', result='passed', exit_code=0)
        data = execution.read()
        self.assertEqual(('maestro', 'test', 'failed'), (data['phase'], data['category'], data['result']))
        self.assertEqual('complete', data['events'][-1]['phase'])

    def test_success_and_budget_expiry_remain_distinct(self):
        execution.update(initialize=True)
        execution.update(phase='native')
        execution.update(phase='complete', result='passed', exit_code=0)
        self.assertEqual('passed', execution.read()['result'])
        execution.update(initialize=True)
        execution.update(phase='components')
        execution.update(result='failed', exit_code=124, failure_category='budget')
        self.assertEqual(('budget', 'components'),
                         (execution.read()['category'], execution.read()['phase']))


class NativeOnlyRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'scripts').mkdir()
        for name in ('e2e.sh', 'e2e_execution.py'):
            shutil.copyfile(Path(__file__).with_name(name), self.root / 'scripts' / name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.calls = self.root / 'calls'
        apk = self.root / 'app/build/outputs/apk/mock/e2e/app-mock-e2e.apk'
        apk.parent.mkdir(parents=True)
        apk.write_bytes(b'application APK')
        adb = self.bin / 'adb'
        adb.write_text('#!' + sys.executable + '\n' + '''import os, sys
from pathlib import Path
with Path(os.environ['CALLS']).open('a') as f: f.write('adb ' + ' '.join(sys.argv[1:]) + '\\n')
if sys.argv[1:] == ['devices']: print('List of devices attached' if os.environ.get('FAKE_NO_DEVICE') else 'emulator-5554 device')
elif 'getprop' in sys.argv: print('Europe/Moscow')
elif sys.argv[1:3] == ['pull', '/sdcard/pf-ui.xml']:
    Path(sys.argv[-1]).write_text('<node resource-id="auth.phone.document"/>')
elif sys.argv[1] == 'pull': raise SystemExit(1)
''')
        adb.chmod(0o755)
        gradle = self.root / 'gradlew'
        gradle.write_text('#!' + sys.executable + '\n' + '''import os, sys
from pathlib import Path
with Path(os.environ['CALLS']).open('a') as f: f.write('gradle ' + ' '.join(sys.argv[1:]) + '\\n')
if ':app:assembleMockE2e' in sys.argv and os.environ.get('FAKE_BUILD_EXIT'):
    raise SystemExit(int(os.environ['FAKE_BUILD_EXIT']))
if os.environ.get('FAKE_NATIVE_EARLY_EXIT'):
    raise SystemExit(int(os.environ['FAKE_NATIVE_EARLY_EXIT']))
p = Path('app/build/outputs/androidTest-results/connected/mock/TEST-native.xml')
p.parent.mkdir(parents=True)
p.write_text('<testsuite><testcase classname="Native" name="method"/></testsuite>')
apk = Path('app/build/outputs/apk/androidTest/mock/e2e/app-mock-e2e-androidTest.apk')
apk.parent.mkdir(parents=True)
apk.write_bytes(b'native test APK')
raise SystemExit(int(os.environ.get('FAKE_NATIVE_EXIT', '0')))
''')
        gradle.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'], CALLS=str(self.calls))

    def seed_old_payload(self):
        names = ('build/e2e/maestro/old?/commands.json', 'build/e2e/maestro-log/old.log',
                 'build/e2e/shots/old.png', 'build/e2e/maestro-output.tgz', 'build/e2e/maestro-output.tmp',
                 'build/e2e/maestro-report.xml', 'build/e2e/native-report.xml', 'build/e2e/jvm-report.xml',
                 'build/e2e/preflight-ui.xml', 'build/e2e/preflight.png', 'build/e2e/logcat.txt',
                 'build/e2e/native/connected/mock/TEST-old.xml', 'build/e2e/native-report/old.html',
                 'build/e2e/coverage/ec/old.ec', 'build/e2e/native-coverage/old.ec',
                 'build/e2e/expected-tests.json', 'build/e2e/execution-status.json', 'build/e2e/qase-delivery.json',
                 'app/build/outputs/androidTest-results/connected/mock/TEST-old.xml',
                 'app/build/outputs/code_coverage/old.ec', 'app/build/reports/androidTests/old.html',
                 'build/reports/e2e-coverage/old.xml')
        paths = [self.root / name for name in names]
        for path in paths:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('<testsuite><testcase classname="Old" name="passed"/></testsuite>')
        test_apk = self.root / 'app/build/outputs/apk/androidTest/mock/e2e/app-mock-e2e-androidTest.apk'
        test_apk.parent.mkdir(parents=True)
        test_apk.write_bytes(b'cached test APK')
        return paths

    def assert_old_payload_removed(self, paths):
        self.assertEqual([], [str(path.relative_to(self.root)) for path in paths if path.exists()])
        self.assertTrue((self.root / 'app/build/outputs/apk/mock/e2e/app-mock-e2e.apk').is_file())
        self.assertTrue((self.root / 'app/build/outputs/apk/androidTest/mock/e2e/app-mock-e2e-androidTest.apk').is_file())

    def test_early_device_setup_failure_cannot_leave_previous_pass_xml_or_raw_artifacts(self):
        paths = self.seed_old_payload()
        self.env['FAKE_NO_DEVICE'] = '1'
        result = subprocess.run(['bash', 'scripts/e2e.sh', '--native-only', '--no-build'], cwd=self.root,
                                env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assert_old_payload_removed(paths)
        data = json.loads((self.root / 'build/e2e/execution.json').read_text())
        self.assertEqual(('failed', 'device'), (data['result'], data['category']))
        self.assertNotIn('gradle ', self.calls.read_text())

    def test_early_build_failure_cleans_payload_but_preserves_current_ci_receipt_and_preparation(self):
        paths = self.seed_old_payload()
        out = self.root / 'build/e2e'
        (out / 'execution.json').write_text(json.dumps({'schema': 1, 'phase': 'setup', 'category': None,
            'result': 'running', 'exit_code': None, 'events': [], 'identity': {'GITHUB_SHA': 'current-sha'}}))
        for name in ('preparation.json', 'sdk-cache.json'):
            (out / name).write_text('current setup telemetry')
        self.env.update(GITHUB_ACTIONS='true', FAKE_BUILD_EXIT='23')
        result = subprocess.run(['bash', 'scripts/e2e.sh', '--native-only'], cwd=self.root,
                                env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(23, result.returncode, result.stderr)
        self.assert_old_payload_removed(paths)
        data = json.loads((out / 'execution.json').read_text())
        self.assertEqual(('failed', 'setup', 23), (data['result'], data['category'], data['exit_code']))
        self.assertEqual({'GITHUB_SHA': 'current-sha'}, data['identity'])
        for name in ('preparation.json', 'sdk-cache.json'):
            self.assertEqual('current setup telemetry', (out / name).read_text())

    def test_connected_build_failure_cannot_copy_old_native_pass_as_current_evidence(self):
        paths = self.seed_old_payload()
        self.env['FAKE_NATIVE_EARLY_EXIT'] = '19'
        result = subprocess.run(['bash', 'scripts/e2e.sh', '--native-only', '--no-build'], cwd=self.root,
                                env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(19, result.returncode, result.stderr)
        self.assert_old_payload_removed(paths)
        self.assertFalse((self.root / 'build/e2e/native').exists())
        data = json.loads((self.root / 'build/e2e/execution.json').read_text())
        self.assertEqual(('failed', 'native', 'test', 19),
                         (data['result'], data['phase'], data['category'], data['exit_code']))

    def test_native_only_runs_without_maestro_or_maestro_preflight_and_copies_native_report(self):
        result = subprocess.run(['bash', 'scripts/e2e.sh', '--native-only', '--no-build'], cwd=self.root,
                                env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        calls = self.calls.read_text()
        self.assertIn('gradle :app:connectedMockE2eAndroidTest', calls)
        self.assertNotIn('uiautomator', calls)
        self.assertNotIn('monkey', calls)
        self.assertTrue((self.root / 'build/e2e/native/connected/mock/TEST-native.xml').is_file())
        data = json.loads((self.root / 'build/e2e/execution.json').read_text())
        self.assertEqual('passed', data['result'])
        self.assertEqual({'app-mock-e2e.apk', 'app-mock-e2e-androidTest.apk'}, set(data['apks']))
        self.assertIn('native', [event['phase'] for event in data['events']])
        self.assertNotIn('maestro', [event['phase'] for event in data['events']])

    def test_failed_native_suite_still_records_the_freshly_built_test_apk(self):
        self.env['FAKE_NATIVE_EXIT'] = '7'
        result = subprocess.run(['bash', 'scripts/e2e.sh', '--native-only', '--no-build'], cwd=self.root,
                                env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(7, result.returncode, result.stderr)
        data = json.loads((self.root / 'build/e2e/execution.json').read_text())
        self.assertEqual(('failed', 'native', 'test', 7),
                         (data['result'], data['phase'], data['category'], data['exit_code']))
        self.assertEqual({'app-mock-e2e.apk', 'app-mock-e2e-androidTest.apk'}, set(data['apks']))

    def test_full_local_runner_preserves_maestro_failure_through_native_success_and_cleanup(self):
        maestro = self.bin / 'maestro'
        maestro.write_text('#!' + sys.executable + '\n' + '''import os, sys
from pathlib import Path
with Path(os.environ['CALLS']).open('a') as f: f.write('maestro failed\\n')
raise SystemExit(17)
''')
        maestro.chmod(0o755)
        sleep = self.bin / 'sleep'
        sleep.write_text('#!/bin/sh\nexit 0\n')
        sleep.chmod(0o755)
        result = subprocess.run(['bash', 'scripts/e2e.sh', '--no-build'], cwd=self.root,
                                env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(17, result.returncode, result.stderr)
        data = json.loads((self.root / 'build/e2e/execution.json').read_text())
        self.assertEqual(('failed', 'maestro', 'test', 17),
                         (data['result'], data['phase'], data['category'], data['exit_code']))
        failure = next(i for i, event in enumerate(data['events'])
                       if event['phase'] == 'maestro' and event['result'] == 'failed')
        native = next(i for i, event in enumerate(data['events']) if event['phase'] == 'native')
        self.assertLess(failure, native, 'first failure must be recorded before the native suite starts')
        self.assertIn('gradle :app:connectedMockE2eAndroidTest', self.calls.read_text())

    def test_native_only_cannot_silently_run_a_partial_flow_selection(self):
        result = subprocess.run(['bash', 'scripts/e2e.sh', '--native-only', '--shard', '1/5'], cwd=self.root,
                                env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(2, result.returncode)
        calls = self.calls.read_text() if self.calls.exists() else ''
        self.assertNotIn('gradle', calls)
        self.assertNotIn('adb install', calls)


if __name__ == '__main__':
    unittest.main()
