import importlib.util
import json
import os
import re
import shlex
import signal
import sys
import tarfile
import time
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('e2e_cache', Path(__file__).with_name('e2e-cache.py'))
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class CleanAvdCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        environment = patch.dict(os.environ, {'ANDROID_HOME': str(self.root / 'sdk')})
        environment.start()
        self.addCleanup(environment.stop)
        self.avd = self.root / 'device.avd'
        self.avd.mkdir()

    def ready_snapshot(self):
        snapshot = self.avd / 'snapshots/clean'
        snapshot.mkdir(parents=True)
        (snapshot / 'snapshot.pb').write_bytes(b'metadata')
        (snapshot / 'hardware.ini').write_text('hw.ramSize=4096\nhw.cpu.ncore=4\ndisk.dataPartition.size=6g\n')
        (snapshot / 'ram.bin').write_bytes(b'ram')
        (self.avd / 'finassist-clean.json').write_text(json.dumps({'key': 'compatible', 'boot_id': '12345678-1234-4234-8234-123456789abc', 'disk_size_bytes': 6 * 1024 ** 3}))

    def test_cache_miss_corrupt_or_incompatible_snapshot_is_not_usable(self):
        self.assertFalse(cache.usable(self.avd, 'compatible'))
        self.ready_snapshot()
        self.assertTrue(cache.usable(self.avd, 'compatible'))
        self.assertFalse(cache.usable(self.avd, 'different-sdk'))
        (self.avd / 'snapshots/clean/ram.bin').write_bytes(b'')
        self.assertFalse(cache.usable(self.avd, 'compatible'))
        (self.avd / 'finassist-clean.json').write_text('{broken')
        self.assertFalse(cache.usable(self.avd, 'compatible'))

    def test_named_snapshot_uses_ram_bin_without_requiring_optional_mapped_ram(self):
        self.ready_snapshot()
        self.assertTrue(cache.snapshot_exists(self.avd))
        snapshot = self.avd / 'snapshots/clean'
        self.assertFalse((snapshot / 'ram.img').exists())
        (snapshot / 'ram.bin').rename(snapshot / 'ram.img')
        self.assertFalse(cache.snapshot_exists(self.avd))
        (snapshot / 'ram.bin').write_bytes(b'')
        self.assertFalse(cache.snapshot_exists(self.avd))

    def test_effective_disk_size_is_validated_against_saved_snapshot_and_not_requested_size(self):
        self.ready_snapshot()
        self.assertEqual(6442450944, cache.snapshot_disk_size(self.avd))
        self.assertTrue(cache.usable(self.avd, 'compatible'))
        metadata = self.avd / 'finassist-clean.json'
        data = json.loads(metadata.read_text())
        for wrong in [4294967296, '6442450944', True, None]:
            data['disk_size_bytes'] = wrong
            metadata.write_text(json.dumps(data))
            self.assertFalse(cache.usable(self.avd, 'compatible'))
        del data['disk_size_bytes']
        metadata.write_text(json.dumps(data))
        self.assertFalse(cache.usable(self.avd, 'compatible'))

    def test_missing_invalid_or_injected_disk_size_cannot_become_action_input(self):
        self.ready_snapshot()
        hardware = self.avd / 'snapshots/clean/hardware.ini'
        for invalid in ['', '0', '-1', '6g; touch should-not-exist', '6.0g', '1e9', '9999999999999999999T']:
            with self.subTest(value=invalid):
                hardware.write_text('disk.dataPartition.size=' + invalid + '\n')
                with self.assertRaises(ValueError):
                    cache.snapshot_disk_size(self.avd)
                self.assertFalse(cache.usable(self.avd, 'compatible'))
        for invalid in ['hw.ramSize=4096\n', 'disk.dataPartition.size=6g\ndisk.dataPartition.size=4g\n']:
            hardware.write_text(invalid)
            with self.assertRaises(ValueError):
                cache.snapshot_disk_size(self.avd)

    def test_seal_with_invalid_effective_size_cannot_publish_metadata_or_action_input(self):
        self.ready_snapshot()
        metadata = self.avd / 'finassist-clean.json'
        metadata.unlink()
        (self.avd / 'snapshots/clean/hardware.ini').write_text('disk.dataPartition.size=6g; invalid\n')
        with patch.object(cache, 'AVD', self.avd), patch.object(cache, 'META', metadata), \
             patch.object(cache, 'STATE', self.root / 'state.json'), patch.object(cache, 'wait_stopped'), \
             patch.object(cache, 'assert_ci_key'), patch.object(cache, 'output') as out, \
             patch('sys.argv', ['e2e-cache.py', 'seal']):
            cache.write({'key': 'compatible', 'boot_id': '12345678-1234-4234-8234-123456789abc', 'marks': {}})
            with self.assertRaises(ValueError):
                cache.main()
            self.assertFalse(metadata.exists())
            out.assert_not_called()
            self.assertNotIn('save-start', cache.state()['marks'])

    def test_invalid_restored_device_is_discarded_without_removing_other_avds(self):
        ini = self.root / 'device.ini'
        ini.write_text('path=old')
        sibling = self.root / 'other.avd'
        sibling.mkdir()
        with patch.object(cache, 'AVD', self.avd), patch.object(cache, 'AVD_ROOT', self.root), \
             patch.object(cache, 'NAME', 'device'), patch.object(cache, 'reload_ci_key'), patch.object(cache, 'STATE', self.root / 'state.json'), \
             patch.object(cache, 'output') as out, patch('sys.argv', ['e2e-cache.py', 'validate']):
            cache.write({'key': 'new', 'marks': {}})
            cache.main()
            out.assert_called_once_with('usable', 'false')
        self.assertFalse(self.avd.exists())
        self.assertFalse(ini.exists())
        self.assertTrue(sibling.exists())

    def test_seal_requires_a_stopped_emulator_and_complete_snapshot(self):
        metadata = self.avd / 'finassist-clean.json'
        with patch.object(cache, 'AVD', self.avd), patch.object(cache, 'META', metadata), patch.object(cache, 'assert_ci_key'), \
             patch.object(cache, 'STATE', self.root / 'state.json'), patch.object(cache, 'output') as out, \
             patch('sys.argv', ['e2e-cache.py', 'seal']):
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
            out.assert_called_once_with('disk-size', 6 * 1024 ** 3)
            self.assertIn('save-start', cache.state()['marks'])
            self.assertEqual({'value': '4096'}, cache.state()['snapshot_hardware']['fields']['hw.ramSize'])

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
                    'cpu': ['model'], 'config': {'api': '34', 'gpu': 'swiftshader'}, 'action': 'a' * 40, 'procedure': 'v1'}
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
             patch.object(cache, 'run_tests', return_value=17), \
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

    def test_direct_cold_path_keeps_real_failure_red_without_snapshot_validation(self):
        with patch.object(cache, 'STATE', self.root / 'direct-state.json'), patch.object(cache, 'assert_clean'), \
             patch.object(cache, 'cache_key') as key, patch.object(cache, 'assert_snapshot_loaded') as loaded, \
             patch.object(cache, 'run_tests', return_value=19), \
             patch('sys.argv', ['e2e-cache.py', 'test', '--direct-cold', '--shard', '1/4']):
            cache.write({'marks': {}})
            with self.assertRaises(SystemExit) as result:
                cache.main()
            self.assertEqual(19, result.exception.code)
            self.assertEqual('fresh-cold-direct', cache.state()['boot_mode'])
            self.assertEqual('disabled', cache.state()['cache'])
            self.assertIn('direct-boot-ready', cache.state()['marks'])
            self.assertIn('tests-started', cache.state()['marks'])
            self.assertIn('tests-finished', cache.state()['marks'])
            self.assertNotIn('fallback-boot-ready', cache.state()['marks'])
            key.assert_not_called()
            loaded.assert_not_called()

    def test_native_cold_path_has_its_own_budget_and_failure_is_not_snapshot_retried(self):
        with patch.object(cache, 'STATE', self.root / 'native-state.json'), patch.object(cache, 'assert_clean'), \
             patch.object(cache, 'run_tests', return_value=7) as run, \
             patch('sys.argv', ['e2e-cache.py', 'test', '--direct-cold', '--native-only']):
            cache.write({'marks': {}})
            with self.assertRaises(SystemExit) as result:
                cache.main()
            self.assertEqual(7, result.exception.code)
            run.assert_called_once_with(None, timeout=600, native_only=True)
            self.assertIn('tests-finished', cache.state()['marks'])
            with patch('sys.argv', ['e2e-cache.py', 'fallback']), patch.object(cache, 'wait_stopped'), patch.object(cache, 'output') as out:
                cache.main()
                out.assert_called_once_with('needed', 'false')

    def test_workflow_configured_process_budget_reaches_the_actual_suite_coordinator(self):
        for native in (False, True):
            arguments = ['e2e-cache.py', 'test', '--direct-cold', '--timeout', '731']
            arguments += ['--native-only'] if native else ['--shard', '1/5']
            with self.subTest(native=native), patch.object(cache, 'STATE', self.root / 'budget-state.json'), \
                 patch.object(cache, 'assert_clean'), patch.object(cache, 'run_tests', return_value=0) as run, \
                 patch('sys.argv', arguments):
                cache.write({'marks': {}})
                with self.assertRaises(SystemExit) as result:
                    cache.main()
                self.assertEqual(0, result.exception.code)
                if native:
                    run.assert_called_once_with(None, timeout=731, native_only=True)
                else:
                    run.assert_called_once_with('1/5', timeout=731)

    def test_nonpositive_or_unbounded_suite_budget_is_rejected_before_device_preparation(self):
        for budget in ('0', '-1', 'nan', 'inf'):
            with self.subTest(budget=budget), patch.object(cache, 'assert_clean') as prepare, \
                 patch('sys.argv', ['e2e-cache.py', 'test', '--direct-cold', '--timeout', budget]):
                with self.assertRaises(SystemExit) as result:
                    cache.main()
                self.assertEqual(2, result.exception.code)
                prepare.assert_not_called()

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

    def test_snapshot_boundary_rejects_dirty_device_and_sdk_drift_before_any_save_or_boot_id(self):
        for dirty, drift in [(True, False), (False, True)]:
            with self.subTest(dirty=dirty, drift=drift), patch.object(cache, 'STATE', self.root / 'state.json'), \
                 patch.object(cache, 'assert_ci_key'), patch.object(cache, 'cache_key', return_value='new' if drift else 'expected'), \
                 patch.object(cache, 'adb', return_value='package:android\n' + ('package:ru.finassist.pf.mock.e2e\n' if dirty else '')) as adb, \
                 patch('sys.argv', ['e2e-cache.py', 'snapshot']):
                cache.write({'key': 'expected', 'marks': {}})
                with self.assertRaises(RuntimeError):
                    cache.main()
                self.assertEqual([('shell', 'pm', 'list', 'packages')], [call.args for call in adb.call_args_list])
                self.assertNotIn('boot_id', cache.state())
                self.assertNotIn('snapshot-saved', cache.state()['marks'])

    def test_snapshot_boundary_checks_cleanliness_before_recording_boot_and_saving(self):
        identity = '12345678-1234-4234-8234-123456789abc'
        with patch.object(cache, 'STATE', self.root / 'state.json'), patch.object(cache, 'assert_ci_key'), \
             patch.object(cache, 'cache_key', return_value='expected'), \
             patch.object(cache, 'adb', side_effect=['package:android\n', identity, 'OK']) as adb, \
             patch('sys.argv', ['e2e-cache.py', 'snapshot']):
            cache.write({'key': 'expected', 'marks': {}})
            cache.main()
            self.assertEqual([('shell', 'pm', 'list', 'packages'),
                              ('shell', 'cat', '/proc/sys/kernel/random/boot_id'),
                              ('emu', 'avd', 'snapshot', 'save', 'clean')], [call.args for call in adb.call_args_list])
            self.assertEqual(identity, cache.state()['boot_id'])
            self.assertIn('snapshot-saved', cache.state()['marks'])

    def test_test_boundary_silent_cold_boot_cannot_start_tests_and_requests_fallback(self):
        metadata = self.root / 'meta.json'
        metadata.write_text(json.dumps({'boot_id': '12345678-1234-4234-8234-123456789abc'}))
        with patch.object(cache, 'STATE', self.root / 'state.json'), patch.object(cache, 'META', metadata), \
             patch.object(cache, 'cache_key', return_value='expected'), \
             patch.object(cache, 'adb', side_effect=['package:android\n', '87654321-1234-4234-8234-123456789abc']), \
             patch.object(cache.subprocess, 'run') as run, patch('sys.argv', ['e2e-cache.py', 'test', '--shard', '2/4']):
            cache.write({'key': 'expected', 'marks': {}})
            with self.assertRaisesRegex(RuntimeError, 'cold-booted'):
                cache.main()
            run.assert_not_called()
            self.assertNotIn('tests-started', cache.state()['marks'])
            with patch('sys.argv', ['e2e-cache.py', 'fallback']), patch.object(cache, 'wait_stopped'), patch.object(cache, 'output') as out:
                cache.main()
                out.assert_called_once_with('needed', 'true')

    def test_failed_cached_or_just_published_snapshot_is_visible_in_annotations(self):
        for cache_state, marks in [('hit', {}), ('miss-or-invalid', {'save-start': 1})]:
            with self.subTest(cache=cache_state), patch.object(cache, 'STATE', self.root / 'state.json'), \
                 patch.object(cache, 'wait_stopped'), patch.object(cache, 'output') as out, \
                 patch('builtins.print') as print_message, patch('sys.argv', ['e2e-cache.py', 'fallback']):
                cache.write({'key': 'clean-avd-v2-example', 'cache': cache_state, 'marks': marks})
                cache.main()
                out.assert_called_once_with('needed', 'true')
                warning = print_message.call_args.args[0]
                self.assertIn('::warning::Clean AVD snapshot clean-avd-v2-example', warning)
                self.assertIn('Actions > Caches', warning)
                self.assertIn('cache_warning', cache.state())

    def test_hardware_telemetry_redacts_paths_secrets_serials_and_arbitrary_strings(self):
        config = ('hw.ramSize=4096\nhw.keyboard=yes\nhw.cpu.arch=x86_64\n'
                  'disk.systemPartition.initPath=/private/sdk/system.img\n'
                  'disk.dataPartition.initPath=/private/sdk/userdata.img\n'
                  'custom.serial=unique-device-serial\ncustom.token=secret-token-value\n'
                  'custom.key=private-key-value\ncustom.note=arbitrary-sensitive-content\n')
        summary = cache.hardware_summary(config)
        serialized = json.dumps(summary)
        for forbidden in ['/private', 'unique-device-serial', 'secret-token-value', 'private-key-value', 'arbitrary-sensitive-content']:
            self.assertNotIn(forbidden, serialized)
        self.assertEqual({'value': '4096'}, summary['fields']['hw.ramSize'])
        self.assertEqual({'value': 'x86_64'}, summary['fields']['hw.cpu.arch'])
        self.assertEqual(['disk.systemPartition.initPath'], summary['path_keys'])
        self.assertNotIn('digest', summary['fields']['custom.token'])

    def test_validate_retains_expected_hardware_before_loader_can_remove_snapshot(self):
        self.ready_snapshot()
        with patch.object(cache, 'AVD', self.avd), patch.object(cache, 'reload_ci_key'), \
             patch.object(cache, 'STATE', self.root / 'state.json'), patch.object(cache, 'output') as out, \
             patch('sys.argv', ['e2e-cache.py', 'validate']):
            cache.write({'key': 'compatible', 'marks': {}})
            cache.main()
            (self.avd / 'snapshots/clean/hardware.ini').unlink()
            self.assertEqual({'value': '4'}, cache.state()['snapshot_hardware']['fields']['hw.cpu.ncore'])
            self.assertIn(('disk-size', 6 * 1024 ** 3), [call.args for call in out.call_args_list])

    def test_boot_id_failure_records_sanitized_hardware_diff_without_starting_tests(self):
        self.ready_snapshot()
        metadata = self.root / 'meta.json'
        metadata.write_text(json.dumps({'boot_id': '12345678-1234-4234-8234-123456789abc'}))
        with patch.object(cache, 'AVD', self.avd), patch.object(cache, 'STATE', self.root / 'state.json'), \
             patch.object(cache, 'META', metadata), patch.object(cache, 'cache_key', return_value='expected'), \
             patch.object(cache, 'adb', side_effect=['package:android\n', '87654321-1234-4234-8234-123456789abc']), \
             patch.object(cache.subprocess, 'run') as run, patch('builtins.print'), \
             patch('sys.argv', ['e2e-cache.py', 'test', '--shard', '2/4']):
            cache.write({'key': 'expected', 'marks': {}})
            cache.capture_snapshot_hardware()
            (self.avd / 'snapshots/clean/hardware.ini').unlink()
            (self.avd / 'hardware-qemu.ini').write_text('hw.ramSize=2048\nhw.cpu.ncore=4\ncustom.token=do-not-output\n')
            with self.assertRaisesRegex(RuntimeError, 'cold-booted'):
                cache.main()
            run.assert_not_called()
            self.assertNotIn('tests-started', cache.state()['marks'])
            diagnostics = cache.state()['hardware_diagnostics']
            self.assertIn({'key': 'hw.ramSize', 'saved': '4096', 'current': '2048'}, diagnostics['changed_fields'])
            self.assertNotIn('do-not-output', json.dumps(diagnostics))
            self.assertEqual({'saved': 0, 'current': 0}, diagnostics['path_counts'])

    def test_audited_action_revision_ignores_unrelated_workflow_edits_but_not_action_changes(self):
        action = 'uses: reactivecircus/android-emulator-runner@' + 'a' * 40
        self.assertEqual(cache.action_revision(action), cache.action_revision(action + '\n# reports comment'))
        self.assertNotEqual(cache.action_revision(action), cache.action_revision(action.replace('a' * 40, 'b' * 40)))
        for invalid in ['uses: reactivecircus/android-emulator-runner@v2', action + '\nuses: reactivecircus/android-emulator-runner@' + 'b' * 40]:
            with self.assertRaises(ValueError):
                cache.action_revision(invalid)

    def test_fingerprint_reads_installed_revisions_and_rejects_missing_sdk(self):
        sdk = self.root / 'sdk'
        image = sdk / 'system-images/android-34/google_apis/x86_64'
        image.mkdir(parents=True)
        (sdk / 'emulator').mkdir()
        (sdk / 'emulator/source.properties').write_text('Pkg.Revision=36.1.2\n')
        (image / 'source.properties').write_text('Pkg.Revision=14\n')
        env = dict(ANDROID_HOME=str(sdk), AVD_API='34', AVD_TARGET='google_apis', AVD_ARCH='x86_64',
                   RUNNER_OS='Linux', RUNNER_ARCH='X64', ImageOS='ubuntu24', ImageVersion='v1', AVD_DISK='4096M')
        original_read_text = Path.read_text
        def read_text(path, *args, **kwargs):
            if str(path) == '/proc/cpuinfo':
                return 'vendor_id : Intel\nmodel name : Xeon\nflags : kvm\nprocessor : 1\n'
            return original_read_text(path, *args, **kwargs)
        with patch.dict(os.environ, env), patch.object(Path, 'read_text', read_text):
            self.assertIn('36.1.2', cache.fingerprint()['packages']['emulator'])
            requested_key = cache.cache_key()
            with patch.dict(os.environ, {'ImageVersion': 'v2'}):
                self.assertNotEqual(requested_key, cache.cache_key())
            with patch.dict(os.environ, {'AVD_DISK': '8192M'}):
                self.assertNotEqual(requested_key, cache.cache_key())
            (image / 'source.properties').unlink()
            with self.assertRaises(FileNotFoundError):
                cache.fingerprint()


class ShardExitBarrierTest(unittest.TestCase):
    def run_interrupted(self, observations, archive):
        process = Mock(pid=12345)
        process.poll.return_value = 0  # The leader has exited while its descendant remains alive.
        process.wait.side_effect = [subprocess.TimeoutExpired('shard', 1), 0]
        now = [0.0]

        def sleep(seconds):
            now[0] += seconds

        with patch.object(cache.subprocess, 'Popen', return_value=process), \
             patch.object(cache.os, 'killpg'), \
             patch.object(cache.subprocess, 'run', side_effect=observations) as inspect, \
             patch.object(cache.time, 'monotonic', side_effect=lambda: now[0]), \
             patch.object(cache.time, 'sleep', side_effect=sleep), \
             patch.object(cache, 'archive_maestro_output', side_effect=archive):
            try:
                cache.run_tests('1/4', timeout=1, grace=0)
            finally:
                self.assertLessEqual(now[0], 5)
                for call in inspect.call_args_list:
                    self.assertGreater(call.kwargs['timeout'], 0)
                    self.assertLessEqual(call.kwargs['timeout'], 5)

    def test_archive_waits_for_descendant_to_leave_d_state(self):
        states = iter(['12345 D\n67890 R\n', '12345 Zsl\n', '12345 Z\n67890 D\n'])
        observed = []

        def inspect(*args, **kwargs):
            observed.append(next(states))
            return subprocess.CompletedProcess(args[0], 0, observed[-1])

        def archive():
            self.assertEqual(3, len(observed), 'archive started before the descendant stopped')

        with self.assertRaises(subprocess.TimeoutExpired):
            self.run_interrupted(inspect, archive)

    def test_persistent_live_descendant_blocks_archive_with_bounded_failure(self):
        for status in ('D', 'R', 'S', 'Zl', 'Zsl'):
            with self.subTest(status=status):
                archive = Mock()
                with self.assertRaisesRegex(RuntimeError, 'process group'):
                    self.run_interrupted(lambda *a, **kw: subprocess.CompletedProcess(a[0], 0, f'12345 {status}\n'), archive)
                archive.assert_not_called()

    def test_absent_group_or_only_zombies_can_be_archived(self):
        for snapshot in ('', '67890 D\n', '12345 Z\n12345 Z+\n67890 R\n'):
            with self.subTest(snapshot=snapshot):
                archive = Mock()
                with self.assertRaises(subprocess.TimeoutExpired):
                    self.run_interrupted(lambda *a, **kw: subprocess.CompletedProcess(a[0], 0, snapshot), archive)
                archive.assert_called_once_with()

    def test_process_inspection_failure_blocks_archive(self):
        for error in (OSError('ps unavailable'), subprocess.TimeoutExpired('ps', 5),
                      subprocess.CalledProcessError(1, 'ps')):
            with self.subTest(error=error):
                archive = Mock()
                expected = RuntimeError if isinstance(error, subprocess.TimeoutExpired) else type(error)
                with self.assertRaises(expected):
                    self.run_interrupted(Mock(side_effect=error), archive)
                archive.assert_not_called()


class ShardProcessTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.old_cwd = Path.cwd()
        os.chdir(self.root)
        self.addCleanup(os.chdir, self.old_cwd)
        (self.root / 'scripts').mkdir()

    def script(self, body):
        path = self.root / 'scripts/e2e.sh'
        path.write_text('#!' + sys.executable + '\n' + body)
        path.chmod(0o755)

    def run_prepared_script(self, shard, timeout=0.5, grace=0.1):
        """Start the collection deadline after the fake scenario is prepared, not during Python startup."""
        real_popen = cache.subprocess.Popen
        children = []

        def start(args, **kwargs):
            process = real_popen(args, **kwargs)
            if args[0] == 'scripts/e2e.sh':
                children.append(process)
                deadline = time.monotonic() + 5
                while not Path('scenario.ready').exists() and time.monotonic() < deadline:
                    self.assertIsNone(process.poll(), 'fake scenario exited before its readiness marker')
                    time.sleep(0.01)
                self.assertTrue(Path('scenario.ready').exists(), 'fake scenario did not become ready within 5s')
            return process

        try:
            with patch.object(cache.subprocess, 'Popen', side_effect=start):
                return cache.run_tests(shard, timeout=timeout, grace=grace)
        finally:
            # These two fixtures have one fake process and no descendants. Reap it even if the assertion fails.
            for process in children:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=2)

    def test_timeout_stops_term_resistant_descendant_before_archiving_unsafe_paths(self):
        child = """import os, signal, time
from pathlib import Path
folder = Path('build/e2e/maestro/forgot?')
def terminated(_signal, _frame):
    (folder / 'term?.json').write_text('termination evidence')
signal.signal(signal.SIGTERM, terminated)
Path('child.ready').write_text(str(os.getpid()))
while True:
    (folder / 'commands.json').write_text(str(time.monotonic()))
    time.sleep(0.02)
"""
        self.script("""import os, subprocess, sys, time
from pathlib import Path
folder = Path('build/e2e/maestro/forgot?')
folder.mkdir(parents=True)
(folder / 'commands.json').write_text('partial')
Path('build/e2e/execution.json').write_text('{"schema":1,"phase":"maestro","result":"running","category":null,"exit_code":null,"events":[]}')
Path('parent.pid').write_text(str(os.getpid()))
subprocess.Popen([sys.executable, '-c', %r])
while True: time.sleep(0.02)
""" % child)
        started = time.monotonic()
        try:
            execution_spec = importlib.util.spec_from_file_location('e2e_execution', Path(cache.__file__).with_name('e2e_execution.py'))
            execution = importlib.util.module_from_spec(execution_spec)
            execution_spec.loader.exec_module(execution)
            coordinator = cache.run_command
            # Keep this real-process regression short while exercising the command CLI's actual deadline handling.
            def bounded(command, timeout):
                return coordinator(command, timeout=timeout, grace=0.2, archive_output=True)
            with patch.dict(sys.modules, {'e2e_execution': execution}), \
                 patch.object(cache, 'run_command', side_effect=bounded), self.assertRaises(SystemExit) as result:
                cache.command_main(['--timeout', '1', '--', 'scripts/e2e.sh'])
            self.assertEqual(124, result.exception.code)
            # Include the 5 s post-KILL barrier as well as the 1 s timeout and TERM grace.
            self.assertLess(time.monotonic() - started, 8)
            self.assertFalse(Path('build/e2e/maestro').exists())
            receipt = json.loads(Path('build/e2e/execution.json').read_text())
            self.assertEqual(('budget', 'failed', 124), (receipt['category'], receipt['result'], receipt['exit_code']))
            self.assertTrue(receipt['budget_expired'])
            with tarfile.open('build/e2e/maestro-output.tgz') as stream:
                self.assertIn('maestro/forgot?/commands.json', stream.getnames())
                self.assertEqual(b'termination evidence', stream.extractfile('maestro/forgot?/term?.json').read())
            for name in ('parent.pid', 'child.ready'):
                pid = int(Path(name).read_text())
                status = subprocess.run(['ps', '-p', str(pid), '-o', 'stat='], capture_output=True, text=True, timeout=2)
                self.assertTrue(not status.stdout.strip() or status.stdout.strip().startswith('Z'), status.stdout)
        finally:
            # A failing regression must not leave the old implementation's child behind.
            for name in ('parent.pid', 'child.ready'):
                if Path(name).exists():
                    try:
                        os.kill(int(Path(name).read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    def test_failed_shard_keeps_exit_status_and_archives_remaining_evidence(self):
        self.script("""from pathlib import Path
p = Path('build/e2e/maestro/name?/commands.json')
p.parent.mkdir(parents=True)
p.write_text('failed command')
raise SystemExit(17)
""")
        self.assertEqual(17, cache.run_tests('2/4', timeout=2))
        self.assertFalse(Path('build/e2e/maestro').exists())
        with tarfile.open('build/e2e/maestro-output.tgz') as stream:
            self.assertEqual(b'failed command', stream.extractfile('maestro/name?/commands.json').read())

    def bash_exit_trap_deadline(self, earlier_failure=False):
        helper = Path(cache.__file__).with_name('e2e_execution.py').resolve()
        python = shlex.quote(sys.executable)
        receipt = python + ' ' + shlex.quote(str(helper))
        path = self.root / 'scripts/e2e.sh'
        path.write_text('#!/bin/bash\nset -e\n' + f'''
receipt() {{ {receipt} "$@"; }}
finish() {{
    local code=$?
    receipt finish "$code"
    cp build/e2e/execution.json trap-receipt.json
    printf finished > trap.finished
    exit "$code"
}}
receipt init
receipt phase maestro
trap finish EXIT
mkdir -p 'build/e2e/maestro/current?'
printf 'current trace' > 'build/e2e/maestro/current?/commands.json'
''' + ('receipt finish 17\n' if earlier_failure else '') + '''
bash -c 'printf "%s" "$$" > child.pid; printf ready > scenario.ready; exec sleep 60'
''')
        path.chmod(0o755)
        with self.assertRaises(subprocess.TimeoutExpired):
            self.run_prepared_script('2/5', timeout=0.3, grace=5)
        self.assertEqual('finished', Path('trap.finished').read_text(), 'real Bash EXIT trap did not finish')
        data = json.loads(Path('build/e2e/execution.json').read_text())
        expected = ('test', 17) if earlier_failure else ('budget', 124)
        self.assertEqual(expected, (data['category'], data['exit_code']))
        self.assertEqual(('maestro', 'failed'), (data['phase'], data['result']))
        self.assertTrue(data['budget_expired'])
        # The EXIT trap itself must see the deadline, before the post-barrier coordinator annotation.
        trapped = json.loads(Path('trap-receipt.json').read_text())
        self.assertEqual(expected, (trapped['category'], trapped['exit_code']))
        self.assertFalse(Path('build/e2e/maestro').exists())
        with tarfile.open('build/e2e/maestro-output.tgz') as archive:
            self.assertEqual(b'current trace', archive.extractfile('maestro/current?/commands.json').read())
        status = subprocess.run(['ps', '-p', Path('child.pid').read_text(), '-o', 'stat='],
                                capture_output=True, text=True, timeout=2)
        self.assertTrue(not status.stdout.strip() or status.stdout.strip().startswith('Z'), status.stdout)

    def test_bash_exit_trap_records_deadline_before_termination_status(self):
        self.bash_exit_trap_deadline()

    def test_bash_exit_trap_preserves_assertion_recorded_before_deadline(self):
        self.bash_exit_trap_deadline(earlier_failure=True)

    def test_deadline_during_report_collection_preserves_the_first_assertion_failure(self):
        self.script("""import json, signal, time
from pathlib import Path
# Deliberately exceed the collection deadline during startup: the marker, not wall time, starts the scenario.
time.sleep(0.6)
signal.signal(signal.SIGTERM, signal.SIG_IGN)
folder = Path('build/e2e/maestro/failed?')
folder.mkdir(parents=True)
(folder / 'commands.json').write_text('assertion failure before collection')
Path('build/e2e/execution.json').write_text(json.dumps({
    'phase': 'maestro', 'category': 'test', 'result': 'failed', 'exit_code': 17,
}))
Path('scenario.ready').write_text('ready')
while True: time.sleep(0.02)
""")
        with self.assertRaises(subprocess.TimeoutExpired):
            self.run_prepared_script('2/5')
        data = json.loads(Path('build/e2e/execution.json').read_text())
        self.assertEqual(('maestro', 'test', 'failed', 17),
                         (data['phase'], data['category'], data['result'], data['exit_code']))
        self.assertTrue(data['budget_expired'])
        with tarfile.open('build/e2e/maestro-output.tgz') as archive:
            self.assertEqual(b'assertion failure before collection',
                             archive.extractfile('maestro/failed?/commands.json').read())

    def test_invalid_receipt_cannot_prevent_preservation_of_timeout_trace(self):
        self.script("""import signal, time
from pathlib import Path
signal.signal(signal.SIGTERM, signal.SIG_IGN)
folder = Path('build/e2e/maestro/failed?')
folder.mkdir(parents=True)
(folder / 'commands.json').write_text('partial trace')
Path('build/e2e/execution.json').write_text('{invalid')
Path('scenario.ready').write_text('ready')
while True: time.sleep(0.02)
""")
        with self.assertRaises(subprocess.TimeoutExpired):
            self.run_prepared_script('2/5')
        self.assertEqual('{invalid', Path('build/e2e/execution.json').read_text())
        self.assertFalse(Path('build/e2e/maestro').exists())
        with tarfile.open('build/e2e/maestro-output.tgz') as archive:
            self.assertEqual(b'partial trace', archive.extractfile('maestro/failed?/commands.json').read())

    def test_sigterm_of_coordinator_stops_shard_and_archives_evidence(self):
        self.script("""import os, signal, time
from pathlib import Path
signal.signal(signal.SIGTERM, signal.SIG_IGN)
p = Path('build/e2e/maestro/name?/commands.json')
p.parent.mkdir(parents=True)
p.write_text('interrupted command')
Path('parent.pid').write_text(str(os.getpid()))
while True: time.sleep(0.02)
""")
        module = Path(cache.__file__).resolve()
        code = ("import importlib.util; s=importlib.util.spec_from_file_location('cache', " + repr(str(module)) +
                "); m=importlib.util.module_from_spec(s); s.loader.exec_module(m); m.run_tests('3/4', timeout=30, grace=0.2)")
        process = subprocess.Popen([sys.executable, '-c', code], start_new_session=True)
        try:
            deadline = time.monotonic() + 3
            while not Path('parent.pid').exists() and time.monotonic() < deadline:
                time.sleep(0.01)
            self.assertTrue(Path('parent.pid').exists())
            os.kill(process.pid, signal.SIGTERM)
            self.assertEqual(143, process.wait(timeout=8))
            self.assertFalse(Path('build/e2e/maestro').exists())
            with tarfile.open('build/e2e/maestro-output.tgz') as stream:
                self.assertEqual(b'interrupted command', stream.extractfile('maestro/name?/commands.json').read())
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            if Path('parent.pid').exists():
                try:
                    os.kill(int(Path('parent.pid').read_text()), signal.SIGKILL)
                except ProcessLookupError:
                    pass

    def test_no_raw_output_keeps_existing_archive(self):
        p = Path('build/e2e/maestro-output.tgz')
        p.parent.mkdir(parents=True)
        p.write_bytes(b'archive already created by e2e.sh')
        self.script('raise SystemExit(0)\n')
        self.assertEqual(0, cache.run_tests('1/4', timeout=2))
        self.assertEqual(b'archive already created by e2e.sh', p.read_bytes())


class DisposableCiAdbKeyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        environment = patch.dict(os.environ, {'ANDROID_HOME': str(self.root / 'sdk')})
        environment.start()
        self.addCleanup(environment.stop)
        for name, filename in [('KEY', 'adbkey'), ('KEY_PUBLIC', 'adbkey.pub'), ('KEY_RECEIPT', 'receipt.json')]:
            patcher = patch.object(cache, name, self.root / filename)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_developer_keys_cannot_be_generated_or_reloaded_by_ci_helper(self):
        cache.KEY.write_text('existing developer key')
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'false', 'RUNNER_ENVIRONMENT': 'self-hosted'}), \
             patch.object(cache.subprocess, 'run') as run:
            with self.assertRaisesRegex(RuntimeError, 'GitHub-hosted'):
                cache.initialize_ci_key()
            with self.assertRaisesRegex(RuntimeError, 'GitHub-hosted'):
                cache.reload_ci_key()
            self.assertEqual('existing developer key', cache.KEY.read_text())
            run.assert_not_called()

    def test_generated_pair_is_verified_then_reloaded_after_restore_without_logging_keys(self):
        calls = []
        def command(args, **kwargs):
            calls.append(args)
            if args[1] == 'keygen':
                cache.KEY.write_text('disposable private')
                cache.KEY_PUBLIC.write_text('disposable public')
                self.assertTrue(kwargs['capture_output'])
            return subprocess.CompletedProcess(args, 0)
        with patch.dict(os.environ, {'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted'}), \
             patch.object(cache.subprocess, 'run', side_effect=command):
            cache.initialize_ci_key()
            cache.reload_ci_key()
            self.assertEqual([[cache.adb_executable(), 'kill-server'], [cache.adb_executable(), 'keygen', str(cache.KEY)],
                              [cache.adb_executable(), 'kill-server'], [cache.adb_executable(), 'start-server']], calls)
            self.assertEqual(0o600, cache.KEY.stat().st_mode & 0o777)
            self.assertNotIn('disposable private', cache.KEY_RECEIPT.read_text())
            cache.KEY.write_text('unrelated host key')
            with self.assertRaisesRegex(RuntimeError, 'not generated'):
                cache.reload_ci_key()
            self.assertEqual(4, len(calls))



class CacheWorkflowSafetyTest(unittest.TestCase):
    def test_native_has_an_independent_cold_device_and_reports_require_every_suite(self):
        workflow = (Path(__file__).parent.parent / '.github/workflows/e2e.yml').read_text()
        native = workflow.split('\n  native:\n', 1)[1].split('\n  components:\n', 1)[0]
        self.assertIn('test --direct-cold --native-only', native)
        self.assertNotIn('Install Maestro', native)
        self.assertNotIn('matrix.shard', native)
        self.assertIn('timeout-minutes: 25', native)
        reports = workflow.split('\n  reports:\n', 1)[1]
        self.assertIn('needs: [build, shard, native, components]', reports)
        self.assertIn("needs.native.result != 'success'", reports)
        self.assertIn("needs.components.result != 'success'", reports)
        self.assertIn('name: e2e-native', reports)
        self.assertIn('--native-dir build/e2e-native --jvm-dir build/e2e-components', reports)

    def test_workflow_keeps_ui_postmerge_only_and_caches_only_checksum_addressed_archive(self):
        workflow = (Path(__file__).parent.parent / '.github/workflows/e2e.yml').read_text()
        self.assertNotIn('pull_request:', workflow)
        self.assertIn('branches: [main]', workflow)
        self.assertIn('contents: read', workflow)
        self.assertIn('maestro-archive-v1-${{ runner.os }}-${{ runner.arch }}-${{ env.MAESTRO_VERSION }}-${{ env.MAESTRO_SHA256 }}', workflow)
        self.assertIn('path: ${{ runner.temp }}/maestro-package', workflow)
        self.assertNotIn('restore-keys: maestro', workflow)
        self.assertEqual(1, workflow.count('run-command --timeout 540 -- ./gradlew :app:assembleMockE2e'))
        self.assertIn('COMPONENT_TEST_SECONDS: 900', workflow)
        self.assertIn('run-command --timeout "$COMPONENT_TEST_SECONDS" -- ./gradlew :feature:analytics:impl:testDebugUnitTest', workflow)
        self.assertIn('--jvm-budget-seconds "$COMPONENT_TEST_SECONDS"', workflow)
        self.assertEqual(3, workflow.count('--shard ${{ matrix.shard }}/${{ env.SHARDS }} --timeout "$MAESTRO_TEST_SECONDS"'))
        self.assertIn('--native-only --timeout "$NATIVE_TEST_SECONDS"', workflow)
        self.assertIn('--maestro-budget-seconds "$MAESTRO_TEST_SECONDS"', workflow)
        self.assertIn('--native-budget-seconds "$NATIVE_TEST_SECONDS"', workflow)
        self.assertIn('name: e2e-result-attempt-${{ github.run_attempt }}', workflow)
        self.assertNotIn('timeout --kill-after=10s 900s', workflow)

    def test_shard_budget_accounts_for_preparation_and_reserves_cleanup_time(self):
        self.assertEqual(2400, cache.maestro_budget(2400, None, 9000, None))
        self.assertEqual(2700, cache.maestro_budget(2700, '1000', 1330, '52'))
        self.assertEqual(2699.875, cache.maestro_budget(2700, '1000', 1330.125, '52'))
        self.assertEqual(2629.875, cache.maestro_budget(2700, '1000', 1400.125, '52'))
        self.assertEqual(0, cache.maestro_budget(2700, '1000', 4031, '52'))
        self.assertEqual(2310, cache.maestro_budget(2400, '1000', 1300, '45'))
        for started, minutes in [('1000', None), (None, '52'), ('1000', '0'), ('1000', 'bad')]:
            with self.subTest(started=started, minutes=minutes), self.assertRaises(ValueError):
                cache.maestro_budget(2700, started, 1300, minutes)

    def test_workflow_shares_the_shard_timeout_with_the_budget_coordinator(self):
        workflow = (Path(__file__).parent.parent / '.github/workflows/e2e.yml').read_text()
        shard = workflow.split('\n  shard:\n', 1)[1].split('\n  native:\n', 1)[0]
        self.assertIn('MAESTRO_TEST_SECONDS: 2700', workflow)
        self.assertIn('job_timeout_minutes: [52]', shard)
        self.assertIn('timeout-minutes: ${{ matrix.job_timeout_minutes }}', shard)
        self.assertIn('E2E_SHARD_JOB_MINUTES: ${{ matrix.job_timeout_minutes }}', shard)
        self.assertIn("steps.merge.outputs.interrupted == 'false'", self.step('Send results to Qase'))

    def test_shard_upload_excludes_unfinished_raw_output_but_keeps_other_evidence(self):
        workflow = (Path(__file__).parent.parent / '.github/workflows/e2e.yml').read_text()
        upload = next(block for block in workflow.split('      - uses: actions/upload-artifact@v7')
                      if 'name: e2e-shard-${{ matrix.shard }}' in block)
        self.assertIn('if: always()', upload)
        paths = re.search(r'          path: \|\n((?:            .+\n)+)', upload)
        self.assertIsNotNone(paths, 'upload must retain other reports when raw writers have not stopped')
        self.assertEqual(['build/e2e/', '!build/e2e/maestro/**'],
                         [line.strip() for line in paths[1].splitlines()])

    def step(self, name):
        workflow = (Path(__file__).parent.parent / '.github/workflows/e2e.yml').read_text()
        return next(block for block in workflow.split('      - name: ') if block.startswith(name + '\n'))

    def runs(self, name, fields, avd_cache=True):
        condition = re.search(r'^        if: (.+)$', self.step(name), re.MULTILINE).group(1)
        expression = re.sub(r'steps\.[\w-]+\.(?:outcome|outputs\.[\w-]+)',
                            lambda match: repr(fields.get(match.group(), '')), condition)
        expression = expression.removeprefix('${{').removesuffix('}}').strip()
        expression = expression.replace('inputs.avd_cache', repr(avd_cache))
        expression = expression.replace('matrix.shard', repr(fields.get('matrix.shard', 1)))
        expression = re.sub(r'!(?!=)', ' not ', expression)
        return eval(expression.replace('&&', ' and ').replace('||', ' or ').strip(), {'__builtins__': {}})

    def test_default_push_and_manual_false_use_direct_cold_without_snapshot_fallback(self):
        for selected in (None, False):
            with self.subTest(input=selected):
                # Even stale success/fallback outputs must never activate the opt-in snapshot path.
                fields = {'steps.avd.outcome': 'success', 'steps.avd.outputs.usable': 'true',
                          'steps.seal.outcome': 'success', 'steps.ui.outcome': 'skipped',
                          'steps.fallback.outputs.needed': 'true'}
                self.assertTrue(self.runs('Run UI tests on a fresh cold AVD', fields, selected))
                for step in ['Restore clean AVD', 'Run UI tests from clean snapshot', 'Save clean AVD',
                             'Decide whether clean cold boot is needed',
                             'Run UI tests on a fresh AVD after snapshot boot failure', 'Preserve UI test failure']:
                    self.assertFalse(self.runs(step, fields, selected))
        self.assertFalse(self.runs('Run UI tests on a fresh cold AVD', {}, True))
        self.assertNotIn('continue-on-error', self.step('Run UI tests on a fresh cold AVD'))

    def test_only_shard_one_publishes_changed_sdk_packages(self):
        for shard in range(1, 5):
            for name, package in [('emulator', 'emulator'), ('system image', 'image')]:
                for needed in ('true', 'false'):
                    fields = {'matrix.shard': shard, f'steps.sdk-install.outputs.{package}-save': needed}
                    self.assertEqual(shard == 1 and needed == 'true',
                                     self.runs(f'Save current {name} SDK package', fields))

    def test_each_preparation_failure_reaches_one_fresh_cold_attempt_without_cache_save(self):
        for failed in ['Validate restored AVD', 'Create clean AVD snapshot', 'Seal clean AVD']:
            with self.subTest(failed=failed):
                self.assertIn('continue-on-error: true', self.step(failed))
                fields = {'steps.avd.outcome': 'success', 'steps.avd.outputs.usable': 'false',
                          'steps.create.outcome': 'success', 'steps.seal.outcome': 'success',
                          'steps.avd-cache.outputs.cache-hit': 'false', 'steps.ui.outcome': 'skipped'}
                failed_id = re.search(r'^        id: (.+)$', self.step(failed), re.MULTILINE).group(1)
                fields[f'steps.{failed_id}.outcome'] = 'failure'
                if failed_id == 'avd':
                    fields['steps.create.outcome'] = 'skipped'
                if failed_id != 'seal':
                    fields['steps.seal.outcome'] = 'skipped'
                self.assertFalse(self.runs('Save clean AVD', fields))
                self.assertFalse(self.runs('Run UI tests from clean snapshot', fields))
                self.assertTrue(self.runs('Decide whether clean cold boot is needed', fields))
                fields['steps.fallback.outputs.needed'] = 'true'
                self.assertTrue(self.runs('Initialize fresh fallback CI adb key', fields))
                self.assertTrue(self.runs('Run UI tests on a fresh AVD after snapshot boot failure', fields))
                self.assertFalse(self.runs('Preserve UI test failure', fields))

    def test_successful_hit_and_miss_run_warm_tests_but_actual_test_failure_stays_red(self):
        for hit in ['true', 'false']:
            fields = {'steps.avd.outcome': 'success', 'steps.avd.outputs.usable': hit,
                      'steps.seal.outcome': 'skipped' if hit == 'true' else 'success',
                      'steps.avd-cache.outputs.cache-hit': hit}
            self.assertTrue(self.runs('Run UI tests from clean snapshot', fields))
            self.assertEqual(hit == 'false', self.runs('Save clean AVD', fields))
            fields['steps.ui.outcome'] = 'success'
            self.assertFalse(self.runs('Decide whether clean cold boot is needed', fields))
            fields.update({'steps.ui.outcome': 'failure', 'steps.fallback.outputs.needed': 'false'})
            self.assertTrue(self.runs('Decide whether clean cold boot is needed', fields))
            self.assertFalse(self.runs('Run UI tests on a fresh AVD after snapshot boot failure', fields))
            self.assertTrue(self.runs('Preserve UI test failure', fields))

    def test_failed_warm_cache_hit_gets_one_cold_attempt_and_cold_test_failure_stays_red(self):
        fields = {'steps.ui.outcome': 'failure', 'steps.fallback.outputs.needed': 'true'}
        self.assertTrue(self.runs('Decide whether clean cold boot is needed', fields))
        self.assertTrue(self.runs('Run UI tests on a fresh AVD after snapshot boot failure', fields))
        self.assertFalse(self.runs('Preserve UI test failure', fields))
        self.assertNotIn('continue-on-error', self.step('Run UI tests on a fresh AVD after snapshot boot failure'))

    def test_warm_action_keeps_the_effective_disk_size_while_cold_creation_keeps_requested_size(self):
        warm = self.step('Run UI tests from clean snapshot')
        self.assertIn('disk-size: ${{ steps.avd.outputs.disk-size || steps.seal.outputs.disk-size }}', warm)
        self.assertNotIn('disk-size: ${{ env.AVD_DISK }}', warm)
        self.assertIn('disk-size: ${{ env.AVD_DISK }}', self.step('Create clean AVD snapshot'))
        self.assertIn('disk-size: ${{ env.AVD_DISK }}', self.step('Run UI tests on a fresh AVD after snapshot boot failure'))

    def test_failed_warm_diagnostics_upload_precedes_cold_fallback_and_cannot_fail_the_job(self):
        workflow = (Path(__file__).parent.parent / '.github/workflows/e2e.yml').read_text()
        diagnostic = self.step('Publish failed snapshot preparation diagnostics')
        self.assertIn('continue-on-error: true', diagnostic)
        self.assertIn('name: e2e-preparation-${{ matrix.shard }}', diagnostic)
        self.assertIn('path: build/e2e/preparation.json', diagnostic)
        self.assertLess(workflow.index('- name: Publish failed snapshot preparation diagnostics'),
                        workflow.index('- name: Decide whether clean cold boot is needed'))
        self.assertTrue(self.runs('Publish failed snapshot preparation diagnostics', {'steps.ui.outcome': 'failure'}))
        self.assertTrue(self.runs('Publish failed snapshot preparation diagnostics', {'steps.ui.outcome': 'skipped'}))
        self.assertFalse(self.runs('Publish failed snapshot preparation diagnostics', {'steps.ui.outcome': 'success'}))
        self.assertIn('pattern: e2e-shard-*', workflow)

    def test_sdk_commands_before_emulator_action_do_not_assume_sdk_tools_on_path(self):
        preparation = self.step('Prepare current SDK packages')
        self.assertIn('run: python3 scripts/e2e-sdk-cache.py prepare', preparation)
        helper = Path(__file__).with_name('e2e-sdk-cache.py').read_text()
        self.assertIn("str(root / 'cmdline-tools/latest/bin/sdkmanager')", helper)
        self.assertIn("command('licenses', ['--licenses'], LICENSE_SECONDS)", helper)
        self.assertIn("['--install', 'platform-tools', 'emulator', image, '--channel=0']", helper)
        self.assertNotRegex(preparation, r'(?m)(?:\||^)[ \t]*sdkmanager\s')
        with patch.dict(os.environ, {'ANDROID_HOME': '/sdk-without-path', 'PATH': '/bin'}):
            self.assertEqual('/sdk-without-path/platform-tools/adb', cache.adb_executable())

    def test_cache_is_explicitly_saved_before_any_test_installation(self):
        workflow = (Path(__file__).parent.parent / '.github/workflows/e2e.yml').read_text()
        self.assertLess(workflow.index('- name: Save clean AVD'), workflow.index('- name: Run UI tests from'))
        self.assertNotIn('uses: actions/cache@', workflow)
        self.assertNotIn('~/.android/adb*', workflow)
        self.assertEqual(2, workflow.count('            ~/.android/adbkey\n'))
        self.assertEqual(2, workflow.count('            ~/.android/adbkey.pub\n'))
        self.assertEqual(2, workflow.count('            ~/.android/finassist-ci-adb.json\n'))
        self.assertLess(workflow.index('- name: Initialize disposable CI adb key'), workflow.index('- name: Restore clean AVD'))
        self.assertIn('continue-on-error: true', self.step('Restore clean AVD'))
        self.assertIn('continue-on-error: true', self.step('Save clean AVD'))
        self.assertIn('force-avd-creation: false', workflow)
        self.assertIn('-snapshot clean -no-snapshot-save', workflow)
        self.assertIn("steps.fallback.outputs.needed != 'true'", workflow)
        self.assertEqual(1, workflow.count('- name: Run UI tests on a fresh AVD'))
        self.assertIn('shard: [1, 2, 3, 4, 5]', workflow)


if __name__ == '__main__':
    unittest.main()
