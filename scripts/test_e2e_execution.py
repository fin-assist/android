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
        adb = self.bin / 'adb'
        adb.write_text('#!' + sys.executable + '\n' + '''import os, sys
from pathlib import Path
with Path(os.environ['CALLS']).open('a') as f: f.write('adb ' + ' '.join(sys.argv[1:]) + '\\n')
if sys.argv[1:] == ['devices']: print('emulator-5554 device')
elif 'getprop' in sys.argv: print('Europe/Moscow')
''')
        adb.chmod(0o755)
        gradle = self.root / 'gradlew'
        gradle.write_text('#!' + sys.executable + '\n' + '''import os, sys
from pathlib import Path
with Path(os.environ['CALLS']).open('a') as f: f.write('gradle ' + ' '.join(sys.argv[1:]) + '\\n')
p = Path('app/build/outputs/androidTest-results/connected/mock/TEST-native.xml')
p.parent.mkdir(parents=True)
p.write_text('<testsuite><testcase classname="Native" name="method"/></testsuite>')
''')
        gradle.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ['PATH'], CALLS=str(self.calls))

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
        self.assertIn('native', [event['phase'] for event in data['events']])
        self.assertNotIn('maestro', [event['phase'] for event in data['events']])

    def test_native_only_cannot_silently_run_a_partial_flow_selection(self):
        result = subprocess.run(['bash', 'scripts/e2e.sh', '--native-only', '--shard', '1/5'], cwd=self.root,
                                env=self.env, capture_output=True, text=True, timeout=10)
        self.assertEqual(2, result.returncode)
        calls = self.calls.read_text() if self.calls.exists() else ''
        self.assertNotIn('gradle', calls)
        self.assertNotIn('adb install', calls)


if __name__ == '__main__':
    unittest.main()
