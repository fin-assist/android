import importlib.util
import json
import os
import re
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
        environment = patch.dict(os.environ, {'ANDROID_HOME': str(self.root / 'sdk')})
        environment.start()
        self.addCleanup(environment.stop)
        self.avd = self.root / 'device.avd'
        self.avd.mkdir()

    def ready_snapshot(self):
        snapshot = self.avd / 'snapshots/clean'
        snapshot.mkdir(parents=True)
        (snapshot / 'snapshot.pb').write_bytes(b'metadata')
        (snapshot / 'hardware.ini').write_text('hw.ramSize=4096\nhw.cpu.ncore=4\n')
        (snapshot / 'ram.bin').write_bytes(b'ram')
        (self.avd / 'finassist-clean.json').write_text(json.dumps({'key': 'compatible', 'boot_id': '12345678-1234-4234-8234-123456789abc'}))

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
             patch.object(cache, 'STATE', self.root / 'state.json'), patch.object(cache, 'output'), \
             patch('sys.argv', ['e2e-cache.py', 'validate']):
            cache.write({'key': 'compatible', 'marks': {}})
            cache.main()
            (self.avd / 'snapshots/clean/hardware.ini').unlink()
            self.assertEqual({'value': '4'}, cache.state()['snapshot_hardware']['fields']['hw.cpu.ncore'])

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
    def step(self, name):
        workflow = (Path(__file__).parent.parent / '.github/workflows/e2e.yml').read_text()
        return next(block for block in workflow.split('      - name: ') if block.startswith(name + '\n'))

    def runs(self, name, fields):
        condition = re.search(r'^        if: (.+)$', self.step(name), re.MULTILINE).group(1)
        expression = re.sub(r'steps\.[\w-]+\.(?:outcome|outputs\.[\w-]+)',
                            lambda match: repr(fields.get(match.group(), '')), condition)
        return eval(expression.replace('&&', ' and ').replace('||', ' or '), {'__builtins__': {}})

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
        preparation = self.step('Prepare SDK and fingerprint the AVD')
        self.assertIn('sdkmanager_path="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"', preparation)
        self.assertIn('yes | "$sdkmanager_path" --licenses', preparation)
        self.assertIn('"$sdkmanager_path" --install platform-tools', preparation)
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
        self.assertIn('shard: [1, 2, 3, 4]', workflow)


if __name__ == '__main__':
    unittest.main()
