"""Offline behavior and workflow guards for production SDK caching and cold UI preparation."""
import importlib.util
import json
import os
from pathlib import Path
import re
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('e2e_sdk_cache', Path(__file__).with_name('e2e-sdk-cache.py'))
SDK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SDK)


class SdkCacheTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sdk = self.root / 'sdk'
        manager = self.sdk / 'cmdline-tools/latest/bin/sdkmanager'
        manager.parent.mkdir(parents=True)
        manager.touch()
        env = patch.dict(os.environ, {
            'GITHUB_ACTIONS': 'true', 'RUNNER_ENVIRONMENT': 'github-hosted', 'ANDROID_HOME': str(self.sdk),
            'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64', 'ImageOS': 'ubuntu24', 'ImageVersion': 'v1',
            'AVD_API': '34', 'AVD_TARGET': 'google_apis', 'AVD_ARCH': 'x86_64',
            'GITHUB_STEP_SUMMARY': str(self.root / 'summary'),
        })
        env.start()
        self.addCleanup(env.stop)
        state = patch.object(SDK, 'STATE', self.root / 'sdk-cache.json')
        state.start()
        self.addCleanup(state.stop)
        self.outputs = {}
        out = patch.object(SDK, 'output', side_effect=lambda name, value: self.outputs.update({name: value}))
        out.start()
        self.addCleanup(out.stop)

    def data(self):
        return json.loads(SDK.STATE.read_text())

    def install(self, revision='37.2.13'):
        for name, path in SDK.package_paths()[1].items():
            path.mkdir(parents=True, exist_ok=True)
            (path / 'source.properties').write_text(f'Pkg.Revision={revision if name == "emulator" else "14"}\n')
            (path / 'binary').write_bytes(b'current SDK package')

    def restore_hit(self):
        SDK.paths()
        self.install()
        state = self.data()
        for name, path in SDK.package_paths()[1].items():
            os.environ[f'{name.upper()}_OUTCOME'] = 'success'
            os.environ[f'{name.upper()}_MATCHED_KEY'] = SDK.final_key(state['packages'][name]['prefix'], SDK.metadata(path))
        SDK.recover()

    def test_miss_installs_normally_and_exposes_actual_immutable_keys(self):
        SDK.paths()
        self.assertEqual('true', self.outputs['emulator-restore'])
        SDK.recover()
        self.install()
        SDK.finalize()
        SDK.complete()
        for name, entry in self.data()['packages'].items():
            self.assertEqual('miss', entry['classification'])
            self.assertEqual('true', self.outputs[f'{name}-save'])
            self.assertTrue(entry['final_key'].startswith(entry['prefix']))
        self.assertGreaterEqual(self.data()['seconds']['total'], self.data()['seconds']['restore'])

    def test_current_prefix_hit_does_not_resave_and_updated_revision_gets_new_key(self):
        self.restore_hit()
        SDK.finalize()
        original = self.data()
        for name, entry in original['packages'].items():
            self.assertEqual('hit-current', entry['classification'])
            self.assertEqual('false', self.outputs[f'{name}-save'])
        self.install('38.0.0')  # Online sdkmanager may update a restored revision.
        SDK.finalize()
        updated = self.data()['packages']
        self.assertEqual('hit-updated', updated['emulator']['classification'])
        self.assertNotEqual(original['packages']['emulator']['final_key'], updated['emulator']['final_key'])
        self.assertEqual(original['packages']['emulator']['prefix'], updated['emulator']['prefix'])
        self.assertEqual('true', self.outputs['emulator-save'])
        self.assertEqual('false', self.outputs['image-save'])

    def test_failed_or_incompatible_restore_discards_only_initially_absent_selected_package(self):
        for outcome, matched in [('failure', 'old-key'), ('success', 'wrong-metadata-key'), ('success', '')]:
            with self.subTest(outcome=outcome, matched=matched):
                for path in SDK.package_paths()[1].values():
                    if path.exists():
                        SDK.shutil.rmtree(path)
                SDK.paths()
                self.install()
                untouched = self.sdk / 'platform-tools/adb'
                untouched.parent.mkdir(exist_ok=True)
                untouched.write_text('keep')
                with patch.dict(os.environ, {'EMULATOR_OUTCOME': outcome, 'EMULATOR_MATCHED_KEY': matched}):
                    SDK.recover()
                self.assertFalse((self.sdk / 'emulator').exists())
                self.assertEqual('keep', untouched.read_text())
                self.assertEqual(matched, self.data()['packages']['emulator']['matched_key'])
                self.install()
                SDK.finalize()
                self.assertEqual('miss', self.data()['packages']['emulator']['classification'])

    def test_failed_restore_leaving_a_file_is_removed_for_normal_install(self):
        SDK.paths()
        (self.sdk / 'emulator').write_text('partial extraction')
        with patch.dict(os.environ, {'EMULATOR_OUTCOME': 'failure'}):
            SDK.recover()
        self.assertFalse((self.sdk / 'emulator').exists())
        self.install()
        SDK.finalize()
        self.assertEqual('miss', self.data()['packages']['emulator']['classification'])

    def test_hosted_preinstalled_packages_are_not_overlaid_or_removed(self):
        self.install()
        SDK.paths()
        self.assertEqual('false', self.outputs['emulator-restore'])
        with patch.dict(os.environ, {'EMULATOR_OUTCOME': 'skipped', 'IMAGE_OUTCOME': 'skipped'}):
            SDK.recover()
        self.assertTrue((self.sdk / 'emulator/binary').is_file())
        self.install('38.0.0')
        SDK.finalize()
        self.assertEqual('preinstalled', self.data()['packages']['emulator']['classification'])
        self.assertEqual('38.0.0', self.data()['packages']['emulator']['actual']['revision'])
        self.assertEqual('false', self.outputs['emulator-save'])
        self.assertEqual('false', self.outputs['image-save'])

    def test_delete_recovery_requires_hosted_runner_and_confined_package_paths(self):
        SDK.paths()
        self.install()
        with patch.dict(os.environ, {'RUNNER_ENVIRONMENT': 'self-hosted'}):
            with self.assertRaisesRegex(ValueError, 'GitHub-hosted'):
                SDK.recover()
        self.assertTrue((self.sdk / 'emulator/binary').exists())
        (self.sdk / 'cmdline-tools/latest/bin/sdkmanager').unlink()
        with self.assertRaisesRegex(ValueError, 'SDK manager'):
            SDK.recover()
        self.assertTrue((self.sdk / 'emulator/binary').exists())

    def test_symlink_and_requested_path_traversal_are_rejected(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (self.sdk / 'emulator').symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'escapes'):
            SDK.paths()
        self.assertTrue(outside.exists())
        (self.sdk / 'emulator').unlink()
        with patch.dict(os.environ, {'AVD_TARGET': '../../.android'}):
            with self.assertRaisesRegex(ValueError, 'Invalid requested'):
                SDK.paths()

    def test_prefix_varies_with_requested_package_runner_and_action_but_not_cpu(self):
        root, paths = SDK.package_paths()
        original = SDK.prefix('image', root, paths['image'])
        with patch.dict(os.environ, {'CPU_MODEL': 'other', 'CPU_FLAGS': 'different'}):
            self.assertEqual(original, SDK.prefix('image', root, paths['image']))
        for key, value in [('RUNNER_ARCH', 'ARM64'), ('ImageVersion', 'v2'), ('AVD_API', '35')]:
            with self.subTest(key=key), patch.dict(os.environ, {key: value}):
                r, p = SDK.package_paths()
                self.assertNotEqual(original, SDK.prefix('image', r, p['image']))
        changed_workflow = self.root / 'changed.yml'
        changed_workflow.write_text('uses: reactivecircus/android-emulator-runner@' + 'b' * 40)
        with patch.object(SDK, 'WORKFLOW', changed_workflow):
            self.assertNotEqual(original, SDK.prefix('image', root, paths['image']))

    def test_sdk_install_failure_cannot_finalize_missing_or_bad_actual_metadata(self):
        SDK.paths()
        SDK.recover()
        with self.assertRaises(FileNotFoundError):
            SDK.finalize()
        self.install()
        (self.sdk / 'emulator/source.properties').write_text('Pkg.Revision=not-a-version\n')
        with self.assertRaises(ValueError):
            SDK.finalize()
        self.assertNotIn('emulator-key', self.outputs)

    def test_workflow_keeps_online_checks_one_writer_before_tests_and_opt_in_only_avd(self):
        workflow = (ROOT / '.github/workflows/e2e.yml').read_text()
        steps = {part.splitlines()[0]: part for part in re.split(r'(?m)^      - name: ', workflow)[1:]}
        self.assertIn('avd_cache:\n        description:', workflow)
        self.assertRegex(workflow, r'avd_cache:[\s\S]*?type: boolean\n        default: false')
        first = workflow.index('scripts/e2e-cache.py mark sdk-start')
        restore = workflow.index('name: Restore emulator SDK package')
        online = workflow.index('timeout 600 "$sdkmanager_path" --install platform-tools')
        save = workflow.index('name: Save current emulator SDK package')
        sdk_end = workflow.index('scripts/e2e-sdk-cache.py complete')
        emulator = workflow.index('uses: reactivecircus/android-emulator-runner@')
        self.assertLess(first, restore)
        self.assertLess(restore, online)
        self.assertLess(online, save)
        self.assertLess(save, sdk_end)
        self.assertLess(sdk_end, emulator)
        self.assertIn('timeout 120 "$sdkmanager_path" --licenses', workflow)
        for package in ('emulator', 'image'):
            self.assertIn(f'steps.sdk-{package}.outputs.cache-matched-key', workflow)
            block = steps[f'Save current {"emulator" if package == "emulator" else "system image"} SDK package']
            self.assertIn(f"if: matrix.shard == 1 && steps.sdk-install.outputs.{package}-save == 'true'", block)
            self.assertNotIn('.android', block)
        for name in ('Fingerprint the optional AVD cache', 'Initialize disposable CI adb key', 'Restore clean AVD',
                     'Validate restored AVD', 'Create clean AVD snapshot', 'Seal clean AVD', 'Save clean AVD',
                     'Mark cache ready', 'Run UI tests from clean snapshot', 'Publish failed snapshot preparation diagnostics',
                     'Decide whether clean cold boot is needed', 'Initialize fresh fallback CI adb key',
                     'Run UI tests on a fresh AVD after snapshot boot failure', 'Preserve UI test failure'):
            self.assertIn('if: inputs.avd_cache', steps[name])
        direct = steps['Run UI tests on a fresh cold AVD']
        self.assertIn('if: ${{ !inputs.avd_cache }}', direct)
        self.assertNotIn('continue-on-error', direct)
        self.assertIn('--direct-cold --shard', direct)
        self.assertIn('-no-snapshot-load -no-snapshot-save -wipe-data', direct)
        self.assertEqual({SDK.action_revision()}, set(re.findall(r'uses: reactivecircus/android-emulator-runner@([a-f0-9]{40})', workflow)))


if __name__ == '__main__':
    unittest.main()
