"""Exercise real SDK preparation processes against disposable fake SDKs; never start Android."""
import contextlib
import functools
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import e2e_execution as execution

SPEC = importlib.util.spec_from_file_location('sdk_preparation', Path(__file__).with_name('e2e-sdk-cache.py'))
SDK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SDK)
ROOT = Path(__file__).resolve().parents[1]
LICENSE = 'SDK license\nRequired terms, with exact whitespace.\n'
NORMALIZED_LICENSE = 'SDK license Required terms, with exact whitespace.'

FAKE_MANAGER = '''#!/usr/bin/env python3
from pathlib import Path
import json, os, shutil, subprocess, sys, time
root = Path(os.environ['ANDROID_HOME'])
phase = 'licenses' if '--licenses' in sys.argv else 'install'
with (root.parent / 'calls.jsonl').open('a') as stream:
    stream.write(json.dumps(sys.argv[1:]) + '\\n')
print('sdk ' + phase + ' started', flush=True)
assert sys.stdin.readline() == 'y\\n'
mode = os.environ.get('FAKE_SDK_MODE', '')
if mode == phase + '-timeout':
    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
    (root.parent / 'child.pid').write_text(str(child.pid))
    time.sleep(60)
if mode == phase + '-exit124':
    sys.exit(124)
if mode == phase + '-fail':
    print('specific SDK failure', file=sys.stderr, flush=True)
    sys.exit(28)
if mode == 'large-output':
    print('x' * 150000)
    print('last SDK diagnostic')
if phase == 'licenses':
    shutil.copytree(root.parent / 'template/licenses', root / 'licenses', dirs_exist_ok=True)
elif mode != 'invalid-install':
    for path in (root.parent / 'template').iterdir():
        shutil.copytree(path, root / path.name, dirs_exist_ok=True)
'''


class SdkPreparationTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        previous = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, previous)
        self.sdk = self.root / 'sdk'
        manager = self.sdk / 'cmdline-tools/latest/bin/sdkmanager'
        manager.parent.mkdir(parents=True)
        manager.write_text(FAKE_MANAGER)
        manager.chmod(0o755)
        env = patch.dict(os.environ, {
            'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'ANDROID_HOME': str(self.sdk),
            'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64', 'ImageOS': 'ubuntu26', 'ImageVersion': 'test',
            'AVD_API': '34', 'AVD_TARGET': 'google_apis', 'AVD_ARCH': 'x86_64',
            'GITHUB_OUTPUT': str(self.root / 'outputs'), 'FAKE_SDK_MODE': '',
        })
        env.start()
        self.addCleanup(env.stop)
        self.template = self.root / 'template'
        self.make_packages(self.template)
        execution.update(initialize=True)

    def make_packages(self, base):
        for relative, binary, revision in [('platform-tools', 'adb', '37.0.0'),
                                           ('emulator', 'emulator', '37.2.13'),
                                           ('system-images/android-34/google_apis/x86_64', 'system.img', '14')]:
            directory = base / relative
            directory.mkdir(parents=True)
            (directory / 'source.properties').write_text('Pkg.Revision=' + revision + '\n')
            (directory / binary).write_text('SDK binary fixture')
            (directory / binary).chmod(0o755)
            (directory / 'package.xml').write_text(
                '<repo><license id="android-sdk-license">' + LICENSE + '</license><localPackage path="' +
                relative.replace('/', ';') + '"><uses-license ref="android-sdk-license"/></localPackage></repo>')
        (base / 'licenses').mkdir()
        (base / 'licenses/android-sdk-license').write_text(
            '\n' + hashlib.sha1(NORMALIZED_LICENSE.encode()).hexdigest() + '\n')

    def initialize(self, warm):
        SDK.paths()
        if warm:
            for path in self.template.iterdir():
                shutil.copytree(path, self.sdk / path.name)
            data = json.loads(SDK.STATE.read_text())
            for name, path in SDK.package_paths()[1].items():
                os.environ[name.upper() + '_OUTCOME'] = 'success'
                os.environ[name.upper() + '_MATCHED_KEY'] = SDK.final_key(data['packages'][name]['prefix'], SDK.metadata(path))
        SDK.recover()

    def prepare(self, warm=True, mode=''):
        self.initialize(warm)
        with patch.dict(os.environ, {'FAKE_SDK_MODE': mode}), contextlib.redirect_stdout(io.StringIO()):
            return SDK.prepare()

    def detail(self):
        return json.loads(SDK.STATE.read_text())['preparation']

    def phases(self):
        return ['licenses' if '--licenses' in json.loads(line) else 'install'
                for line in (self.root / 'calls.jsonl').read_text().splitlines()]

    def test_verified_cache_and_accepted_licenses_skip_global_licenses_but_always_check_online(self):
        self.assertEqual(0, self.prepare())
        self.assertEqual(['install'], self.phases())
        self.assertEqual('already_accepted', self.detail()['licenses'])
        self.assertEqual(('complete', 'passed'), (self.detail()['phase'], self.detail()['result']))
        calls = json.loads((self.root / 'calls.jsonl').read_text())
        self.assertIn('system-images;android-34;google_apis;x86_64', calls)
        self.assertIn('--verbose', calls)
        self.assertIn('--sdk_root=' + str(self.sdk), calls)
        self.assertEqual('hit-current', json.loads(SDK.STATE.read_text())['packages']['emulator']['classification'])

    def test_sdk_jaxb_license_normalization_preserves_paragraphs_and_non_ascii_spaces(self):
        text = '\n    Terms and Conditions\n\n    This  is the\n    SDK.\n'
        expected = 'Terms and Conditions\n\nThis is the SDK.'
        self.assertEqual(hashlib.sha1(expected.encode()).hexdigest(), SDK.license_hash(text))
        text = '\u00a0Required\u00a0terms\u00a0'
        self.assertEqual(hashlib.sha1(text.encode()).hexdigest(), SDK.license_hash(text))

    def test_formatted_xml_license_uses_sdk_normalized_hash_instead_of_raw_xml_text(self):
        self.initialize(True)
        path = self.sdk / 'emulator/package.xml'
        path.write_text(path.read_text().replace(LICENSE, '\n    SDK license\n    Required  terms, with exact whitespace.\n'))
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, SDK.prepare())
        self.assertEqual(['install'], self.phases())

    def test_cold_sdk_accepts_real_prompts_then_installs_and_validates(self):
        self.assertEqual(0, self.prepare(warm=False))
        self.assertEqual(['licenses', 'install'], self.phases())
        self.assertEqual([120, 600], [step['timeout_seconds'] for step in self.detail()['steps']])
        self.assertEqual('miss', json.loads(SDK.STATE.read_text())['packages']['image']['classification'])

    def test_missing_or_wrong_accepted_hash_cannot_skip_license_preparation(self):
        for content in ['', 'a' * 40 + '\n']:
            with self.subTest(content=content):
                self.initialize(warm=not (self.sdk / 'emulator').exists())
                (self.sdk / 'licenses/android-sdk-license').write_text(content)
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, SDK.prepare())
                self.assertEqual('preparation_required', self.detail()['licenses'])
                self.assertEqual('licenses', self.detail()['steps'][0]['phase'])

    def test_installer_failure_keeps_exact_exit_and_raw_stderr_without_success_finalization(self):
        self.assertEqual(28, self.prepare(mode='install-fail'))
        self.assertEqual(('setup', 'setup', 28),
                         (execution.read()['phase'], execution.read()['category'], execution.read()['exit_code']))
        self.assertEqual('install', self.detail()['phase'])
        self.assertIn('specific SDK failure', SDK.STATE.with_name('sdk-install.log').read_text())
        self.assertNotIn('actual', json.loads(SDK.STATE.read_text())['packages']['emulator'])
        with patch.object(sys, 'argv', ['receipt', 'finish', 'failure']):
            execution.main()
        self.assertEqual(28, execution.read()['exit_code'])

    def test_license_failure_stops_before_install(self):
        self.assertEqual(28, self.prepare(warm=False, mode='licenses-fail'))
        self.assertEqual(['licenses'], self.phases())
        self.assertEqual(('licenses', 'failed', 28),
                         (self.detail()['phase'], self.detail()['result'], self.detail()['exit_code']))

    def test_child_exit124_is_not_claimed_as_an_observed_deadline(self):
        self.assertEqual(124, self.prepare(mode='install-exit124'))
        self.assertFalse(self.detail()['steps'][0]['deadline_expired'])
        self.assertEqual('setup', execution.read()['category'])

    def test_real_setup_deadline_stops_children_retains_partial_log_and_does_not_mark_ui_budget(self):
        for phase in ('licenses', 'install'):
            with self.subTest(phase=phase):
                # Reuse one prepared fixture: force licenses by removing their acceptance file.
                if phase == 'licenses':
                    self.initialize(False)
                else:
                    for path in self.template.iterdir():
                        shutil.copytree(path, self.sdk / path.name, dirs_exist_ok=True)
                    SDK.paths()
                    SDK.recover()
                execution.update(initialize=True)
                run = SDK.PROCESS.run_command
                with patch.object(SDK, 'LICENSE_SECONDS', 2), patch.object(SDK, 'INSTALL_SECONDS', 2), \
                     patch.object(SDK.PROCESS, 'run_command', functools.partial(run, grace=0.1)), \
                     patch.dict(os.environ, {'FAKE_SDK_MODE': phase + '-timeout'}), \
                     contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(124, SDK.prepare())
                self.assertEqual(phase, self.detail()['phase'])
                self.assertTrue(self.detail()['steps'][-1]['deadline_expired'])
                self.assertIn('sdk ' + phase + ' started', SDK.STATE.with_name('sdk-' + phase + '.log').read_text())
                self.assertEqual('setup', execution.read()['category'])
                self.assertFalse(execution.STATE.with_suffix('.deadline').exists())
                child = int((self.root / 'child.pid').read_text())
                status = subprocess.run(['ps', '-p', str(child), '-o', 'stat='], capture_output=True, text=True, timeout=2)
                self.assertTrue(status.returncode != 0 or status.stdout.strip().startswith('Z'), status.stdout)

    def test_large_diagnostic_is_bounded_and_retains_tail_and_original_size(self):
        self.assertEqual(0, self.prepare(mode='large-output'))
        step = self.detail()['steps'][0]
        self.assertGreater(step['log_bytes'], SDK.LOG_BYTES)
        self.assertTrue(step['log_truncated'])
        log = SDK.STATE.with_name(step['log']).read_bytes()
        self.assertEqual(SDK.LOG_BYTES, len(log))
        self.assertIn(b'last SDK diagnostic', log)

    def test_cleanup_error_cannot_erase_an_already_observed_setup_deadline(self):
        self.initialize(True)
        run = SDK.PROCESS.run_command
        # Real child termination is covered above; simulate a barrier failure without leaving a live child.
        for error in (RuntimeError('barrier failure'), PermissionError('barrier failure')):
            with self.subTest(error=type(error).__name__):
                execution.update(initialize=True)
                process = Mock(pid=12345678)
                process.wait.side_effect = subprocess.TimeoutExpired(['fake-sdkmanager'], 600)
                with patch.object(SDK.PROCESS, 'run_command', functools.partial(run, grace=0)), \
                     patch.object(SDK.PROCESS.subprocess, 'Popen', return_value=process), \
                     patch.object(SDK.PROCESS.os, 'killpg', side_effect=ProcessLookupError), \
                     patch.object(SDK.PROCESS, 'wait_for_process_group', side_effect=error), \
                     contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(124, SDK.prepare())
                step = self.detail()['steps'][-1]
                self.assertTrue(step['deadline_expired'])
                self.assertEqual((type(error).__name__, 124), (step['cleanup_error'], step['exit_code']))
                self.assertEqual(('setup', 124), (execution.read()['category'], execution.read()['exit_code']))
                self.assertFalse(execution.STATE.with_suffix('.deadline').exists())
                self.assertNotIn('actual', json.loads(SDK.STATE.read_text())['packages']['emulator'])

    def test_incomplete_binaries_and_invalid_platform_tools_forbid_license_skip(self):
        self.initialize(True)
        for relative, operation in [
            ('emulator/emulator', 'missing'), ('emulator/emulator', 'not_executable'),
            ('platform-tools/adb', 'missing'), ('platform-tools/adb', 'not_executable'),
            ('system-images/android-34/google_apis/x86_64/system.img', 'missing'),
            ('platform-tools/package.xml', 'wrong_identity'),
        ]:
            with self.subTest(relative=relative, operation=operation):
                for path in self.template.iterdir():
                    shutil.copytree(path, self.sdk / path.name, dirs_exist_ok=True)
                path = self.sdk / relative
                if operation == 'missing':
                    path.unlink()
                elif operation == 'not_executable':
                    path.chmod(0o644)
                else:
                    path.write_text(path.read_text().replace('path="platform-tools"', 'path="other"'))
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, SDK.prepare())
                self.assertEqual('preparation_required', self.detail()['licenses'])
                self.assertEqual('licenses', self.detail()['steps'][0]['phase'])

    def test_online_success_cannot_hide_a_still_missing_binary(self):
        self.initialize(True)
        (self.sdk / 'emulator/emulator').unlink()
        with patch.dict(os.environ, {'FAKE_SDK_MODE': 'invalid-install'}), \
             contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, SDK.prepare())
        self.assertEqual(('validate', 'failed'), (self.detail()['phase'], self.detail()['result']))

    def test_successful_process_with_no_valid_install_still_fails_closed(self):
        self.assertEqual(1, self.prepare(warm=False, mode='invalid-install'))
        self.assertEqual(('validate', 'failed'), (self.detail()['phase'], self.detail()['result']))
        self.assertEqual('setup', execution.read()['category'])

    def test_later_sdk_failure_cannot_replace_an_existing_first_failure(self):
        execution.update(phase='setup', result='failed', exit_code=17)
        self.assertEqual(28, self.prepare(mode='install-fail'))
        self.assertEqual(17, execution.read()['exit_code'])
        self.assertEqual(28, self.detail()['exit_code'])

    def test_license_reference_traversal_and_wrong_package_identity_are_rejected(self):
        self.initialize(True)
        root, packages = SDK.package_paths()
        path = self.sdk / 'emulator/package.xml'
        original = path.read_text()
        for bad in [original.replace('ref="android-sdk-license"', 'ref="../../outside"'),
                    original.replace('path="emulator"', 'path="other"'),
                    original.replace(LICENSE, LICENSE + 'changed')]:
            with self.subTest(bad=bad[:30]):
                path.write_text(bad)
                with self.assertRaises(ValueError):
                    SDK.validate_installed(root, packages)
        path.write_text(original)
        SDK.validate_installed(root, packages)

    def test_cli_rejects_non_hosted_sdk_without_touching_it(self):
        env = dict(os.environ, RUNNER_ENVIRONMENT='self-hosted')
        result = subprocess.run([sys.executable, str(ROOT / 'scripts/e2e-sdk-cache.py'), 'prepare'],
                                env=env, capture_output=True, text=True, timeout=10)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'calls.jsonl').exists())


if __name__ == '__main__':
    unittest.main()
