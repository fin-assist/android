"""FIN-473: distinguish actual coordinator deadlines from a child's chosen exit status."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).with_name('e2e-cache.py').resolve()


class BoundedCommandTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.receipt = self.root / 'build/e2e/execution.json'
        self.receipt.parent.mkdir(parents=True)

    def run_command(self, code, phase='setup', previous_failure=False):
        data = {'schema': 1, 'phase': phase, 'result': 'running', 'category': None, 'exit_code': None, 'events': []}
        if previous_failure:
            data.update(result='failed', category='test', exit_code=17)
        self.receipt.write_text(json.dumps(data))
        result = subprocess.run([sys.executable, str(SCRIPT), 'run-command', '--timeout', '5', '--',
                                 sys.executable, '-c', 'raise SystemExit(' + str(code) + ')'],
                                cwd=self.root, capture_output=True, text=True, timeout=15)
        return result, json.loads(self.receipt.read_text())

    def test_success_does_not_claim_the_whole_ui_suite_completed(self):
        result, receipt = self.run_command(0)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(('running', 'setup', None, None),
                         (receipt['result'], receipt['phase'], receipt['category'], receipt['exit_code']))

    def test_ordinary_failure_is_recorded_immediately_as_setup_failure(self):
        result, receipt = self.run_command(23)
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertEqual(('failed', 'setup', 23),
                         (receipt['result'], receipt['category'], receipt['exit_code']))

    def test_child_exit_137_or_124_is_not_evidence_of_a_deadline(self):
        for phase, expected in (('setup', 'setup'), ('components', 'test')):
            for code in (137, 124):
                with self.subTest(phase=phase, code=code):
                    result, receipt = self.run_command(code, phase)
                    self.assertEqual(code, result.returncode, result.stderr)
                    self.assertEqual(('failed', expected, code),
                                     (receipt['result'], receipt['category'], receipt['exit_code']))
                    self.assertNotIn('budget_expired', receipt)

    def test_later_command_failure_preserves_the_first_assertion_failure(self):
        result, receipt = self.run_command(137, 'maestro', previous_failure=True)
        self.assertEqual(137, result.returncode, result.stderr)
        self.assertEqual(('failed', 'maestro', 'test', 17),
                         (receipt['result'], receipt['phase'], receipt['category'], receipt['exit_code']))

    def test_nonpositive_or_unbounded_deadlines_and_missing_command_never_start_a_child(self):
        marker = self.root / 'started'
        command = [sys.executable, '-c', 'from pathlib import Path; Path("started").write_text("started")']
        for timeout in ('0', '-1', 'nan', 'inf'):
            with self.subTest(timeout=timeout):
                result = subprocess.run([sys.executable, str(SCRIPT), 'run-command', '--timeout', timeout,
                                         '--', *command], cwd=self.root, capture_output=True, text=True, timeout=5)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertFalse(marker.exists())
        result = subprocess.run([sys.executable, str(SCRIPT), 'run-command', '--timeout', '1'],
                                cwd=self.root, capture_output=True, text=True, timeout=5)
        self.assertEqual(2, result.returncode, result.stderr)
        self.assertFalse(marker.exists())


if __name__ == '__main__':
    unittest.main()
