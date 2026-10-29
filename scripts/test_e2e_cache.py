import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('e2e_cache', Path(__file__).with_name('e2e-cache.py'))
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class CleanAvdCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.avd = self.root / 'device.avd'
        self.avd.mkdir()

    def ready_snapshot(self):
        snapshot = self.avd / 'snapshots/clean'
        snapshot.mkdir(parents=True)
        (snapshot / 'snapshot.pb').write_bytes(b'metadata')
        (snapshot / 'ram.img').write_bytes(b'ram')
        (self.avd / 'finassist-clean.json').write_text(json.dumps({'key': 'compatible', 'boot_id': '12345678-1234-4234-8234-123456789abc'}))

    def test_cache_miss_corrupt_or_incompatible_snapshot_is_not_usable(self):
        self.assertFalse(cache.usable(self.avd, 'compatible'))
        self.ready_snapshot()
        self.assertTrue(cache.usable(self.avd, 'compatible'))
        self.assertFalse(cache.usable(self.avd, 'different-sdk'))
        (self.avd / 'snapshots/clean/ram.img').write_bytes(b'')
        self.assertFalse(cache.usable(self.avd, 'compatible'))
        (self.avd / 'finassist-clean.json').write_text('{broken')
        self.assertFalse(cache.usable(self.avd, 'compatible'))

    def test_invalid_restored_device_is_discarded_without_removing_other_avds(self):
        ini = self.root / 'device.ini'
        ini.write_text('path=old')
        sibling = self.root / 'other.avd'
        sibling.mkdir()
        with patch.object(cache, 'AVD', self.avd), patch.object(cache, 'AVD_ROOT', self.root), \
             patch.object(cache, 'NAME', 'device'), patch.object(cache, 'STATE', self.root / 'state.json'), \
             patch.object(cache, 'output') as out, patch('sys.argv', ['e2e-cache.py', 'validate']):
            cache.write({'key': 'new', 'marks': {}})
            cache.main()
            out.assert_called_once_with('usable', 'false')
        self.assertFalse(self.avd.exists())
        self.assertFalse(ini.exists())
        self.assertTrue(sibling.exists())

    def test_seal_requires_a_stopped_emulator_and_complete_snapshot(self):
        metadata = self.avd / 'finassist-clean.json'
        with patch.object(cache, 'AVD', self.avd), patch.object(cache, 'META', metadata), \
             patch.object(cache, 'STATE', self.root / 'state.json'), patch('sys.argv', ['e2e-cache.py', 'seal']):
            cache.write({'key': 'compatible', 'boot_id': '12345678-1234-4234-8234-123456789abc', 'marks': {}})
            with patch.object(cache, 'wait_stopped', side_effect=TimeoutError('live')):
                with self.assertRaises(TimeoutError):
                    cache.main()
            self.assertFalse(metadata.exists())
            with patch.object(cache, 'wait_stopped'):
                with self.assertRaisesRegex(RuntimeError, 'missing or incomplete'):
                    cache.main()
                self.assertFalse(metadata.exists())
                self.ready_snapshot()
                metadata.unlink()
                cache.main()
            self.assertTrue(cache.usable(self.avd, 'compatible'))
            self.assertIn('save-start', cache.state()['marks'])

    def test_wait_stopped_waits_for_exit_and_rejects_a_live_emulator_at_timeout(self):
        process = f'qemu-system-x86_64 -avd {cache.NAME} -no-window'
        with patch.object(cache.subprocess, 'check_output', side_effect=[process, 'ps -eo args']), \
             patch.object(cache.time, 'sleep') as sleep:
            cache.wait_stopped()
            sleep.assert_called_once_with(1)
        with patch.object(cache.subprocess, 'check_output', return_value=process), \
             patch.object(cache.time, 'monotonic', side_effect=[0, 121]):
            with self.assertRaisesRegex(TimeoutError, 'refusing to cache'):
                cache.wait_stopped()

    def test_installed_app_and_test_apk_are_rejected(self):
        for package in ['ru.finassist.pf', 'ru.finassist.pf.mock.e2e', 'ru.finassist.pf.mock.e2e.test']:
            with self.subTest(package=package), patch.object(cache, 'adb', return_value=f'package:android\npackage:{package}\n'):
                with self.assertRaisesRegex(RuntimeError, 'FinAssist APK'):
                    cache.assert_clean()
        with patch.object(cache, 'adb', return_value='package:android\npackage:com.android.settings\n'):
            cache.assert_clean()

    def test_package_manager_failure_is_not_a_clean_device(self):
        for response in ['', 'Error: package manager unavailable']:
            with patch.object(cache, 'adb', return_value=response):
                with self.assertRaisesRegex(RuntimeError, 'package manager'):
                    cache.assert_clean()

    def test_cold_boot_is_not_misreported_as_a_snapshot_hit(self):
        saved = {'boot_id': '12345678-1234-4234-8234-123456789abc'}
        cache.assert_snapshot_loaded(saved, saved['boot_id'])
        with self.assertRaisesRegex(RuntimeError, 'cold-booted'):
            cache.assert_snapshot_loaded(saved, '87654321-1234-4234-8234-123456789abc')

    def test_emulator_console_KO_is_a_failure_even_if_adb_exits_zero(self):
        completed = subprocess.CompletedProcess([], 0, 'KO: snapshot failed\n', '')
        with patch.object(cache.subprocess, 'run', return_value=completed):
            with self.assertRaises(RuntimeError):
                cache.adb('emu', 'avd', 'snapshot', 'save', 'clean')

    def test_key_changes_with_each_compatibility_dimension(self):
        baseline = {'packages': {'emulator': '36.1', 'image': '14'}, 'runner': {'os': 'Linux', 'arch': 'X64'},
                    'cpu': ['model'], 'config': {'api': '34', 'gpu': 'swiftshader'}, 'workflow': 'v1', 'helper': 'v1'}
        with patch.object(cache, 'fingerprint', return_value=baseline):
            original = cache.cache_key()
        for field in baseline:
            changed = dict(baseline, **{field: 'changed'})
            with self.subTest(field=field), patch.object(cache, 'fingerprint', return_value=changed):
                self.assertNotEqual(original, cache.cache_key())

    def test_test_failure_cannot_be_retried_as_preparation(self):
        with patch.object(cache, 'STATE', self.root / 'state.json'), patch.object(cache, 'assert_clean'), \
             patch.object(cache, 'cache_key', return_value='key'), patch.object(cache, 'assert_snapshot_loaded'), \
             patch.object(cache, 'boot_id'), patch.object(cache, 'META', self.root / 'meta.json'), \
             patch.object(cache.subprocess, 'run', return_value=subprocess.CompletedProcess([], 17)), \
             patch('sys.argv', ['e2e-cache.py', 'test', '--shard', '2/4']):
            (self.root / 'meta.json').write_text('{}')
            cache.write({'key': 'key', 'marks': {}})
            with self.assertRaises(SystemExit) as result:
                cache.main()
            self.assertEqual(17, result.exception.code)
            self.assertIn('tests-started', cache.state()['marks'])
            with patch('sys.argv', ['e2e-cache.py', 'fallback']), patch.object(cache, 'wait_stopped'), patch.object(cache, 'output') as out:
                cache.main()
                out.assert_called_once_with('needed', 'false')

    def test_preparation_failure_allows_one_cold_attempt(self):
        with patch.object(cache, 'STATE', self.root / 'state.json'), patch.object(cache, 'assert_clean', side_effect=RuntimeError('dirty')), \
             patch('sys.argv', ['e2e-cache.py', 'test', '--shard', '2/4']):
            cache.write({'marks': {}})
            with self.assertRaises(RuntimeError):
                cache.main()
            self.assertNotIn('tests-started', cache.state()['marks'])
            with patch('sys.argv', ['e2e-cache.py', 'fallback']), patch.object(cache, 'wait_stopped'), patch.object(cache, 'output') as out:
                cache.main()
                out.assert_called_once_with('needed', 'true')

    def test_fingerprint_reads_installed_revisions_and_rejects_missing_sdk(self):
        sdk = self.root / 'sdk'
        image = sdk / 'system-images/android-34/google_apis/x86_64'
        image.mkdir(parents=True)
        (sdk / 'emulator').mkdir()
        (sdk / 'emulator/source.properties').write_text('Pkg.Revision=36.1.2\n')
        (image / 'source.properties').write_text('Pkg.Revision=14\n')
        env = dict(ANDROID_HOME=str(sdk), AVD_API='34', AVD_TARGET='google_apis', AVD_ARCH='x86_64',
                   RUNNER_OS='Linux', RUNNER_ARCH='X64', ImageOS='ubuntu24', ImageVersion='v1')
        original_read_text = Path.read_text
        def read_text(path, *args, **kwargs):
            if str(path) == '/proc/cpuinfo':
                return 'vendor_id : Intel\nmodel name : Xeon\nflags : kvm\nprocessor : 1\n'
            return original_read_text(path, *args, **kwargs)
        with patch.dict(os.environ, env), patch.object(Path, 'read_text', read_text):
            self.assertIn('36.1.2', cache.fingerprint()['packages']['emulator'])
            (image / 'source.properties').unlink()
            with self.assertRaises(FileNotFoundError):
                cache.fingerprint()


class CacheWorkflowSafetyTest(unittest.TestCase):
    def test_cache_is_explicitly_saved_before_any_test_installation(self):
        workflow = (Path(__file__).parent.parent / '.github/workflows/e2e.yml').read_text()
        self.assertLess(workflow.index('- name: Save clean AVD'), workflow.index('- name: Run UI tests from'))
        self.assertNotIn('uses: actions/cache@', workflow)
        self.assertNotIn('~/.android/adb', workflow)
        self.assertIn('force-avd-creation: false', workflow)
        self.assertIn('-snapshot clean -no-snapshot-save', workflow)
        self.assertIn("steps.fallback.outputs.needed != 'true'", workflow)
        self.assertEqual(1, workflow.count('- name: Run UI tests on a fresh AVD'))
        self.assertIn('shard: [1, 2, 3, 4]', workflow)


if __name__ == '__main__':
    unittest.main()
