"""Compact packaging preserves the real gather verdict, mapping and evidence without raw payload copies."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    loaded = importlib.util.module_from_spec(spec)
    sys.modules[name] = loaded
    spec.loader.exec_module(loaded)
    return loaded


compact = module('compact_result', 'e2e-result.py')
gatherer = module('compact_gatherer', 'e2e-shards.py')
qase = module('compact_qase', 'qase-report.py')


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(data, encoding='utf-8')


class CompactResultTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.canonical, self.raw, self.out = [self.root / name for name in ('canonical', 'raw', 'compact')]
        self.shards = self.raw / 'shards'
        self.native, self.jvm = self.raw / 'e2e-native', self.raw / 'e2e-components'
        self.flow = '.maestro/flows/one.yaml'
        self.native_key, self.jvm_key = 'fixture.NativeTest#native', 'jvm:fixture.ComponentTest#component'
        self.context = {'repository': 'fin-assist/android', 'server_url': 'https://github.com',
                        'source_sha': 'a' * 40, 'run_id': '42', 'run_attempt': '2', 'event_name': 'push',
                        'ref': 'refs/heads/main', 'ref_name': 'main', 'workflow': 'UI tests',
                        'workflow_ref': 'fin-assist/android/.github/workflows/e2e.yml@refs/heads/main',
                        'workflow_sha': 'a' * 40, 'job_results': {key: 'success' for key in compact.JOB_ENV}}
        env = patch.dict(os.environ, {'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '2'})
        env.start()
        self.addCleanup(env.stop)
        write(self.root / self.flow, 'name: One\nappId: fixture\n---\n- launchApp\n')
        write(self.root / 'app/src/androidTestMock/kotlin/fixture/NativeTest.kt',
              'package fixture\nclass NativeTest { @Test fun native() {} }')
        write(self.root / 'feature/example/src/test/kotlin/fixture/ComponentTest.kt',
              'package fixture\nclass ComponentTest { @Test fun component() {} }')
        write(self.root / 'scripts/qase-cases.json', json.dumps({'project': 'PLAINFIN', 'cases': {
            self.flow: 1, self.native_key: 2, self.jvm_key: 2}}))
        self.plan = gatherer.plan([self.flow], {}, 1)

    def generate(self, mode='passed'):
        for directory in (self.shards / 'e2e-shard-1', self.native, self.jvm):
            write(directory / 'execution.json', json.dumps({
                'schema': 1, 'phase': 'complete', 'result': 'passed', 'exit_code': 0, 'category': None,
                'identity': {'GITHUB_SHA': 'a' * 40, 'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '1',
                             'RUNNER_OS': 'Linux', 'ImageVersion': 'pinned'},
                'apks': {'app-mock-e2e.apk': '1' * 64, 'app-mock-e2e-androidTest.apk': '2' * 64},
                'events': [{'phase': 'setup', 'result': 'running', 'at': 100},
                           {'phase': 'complete', 'result': 'passed', 'at': 110}],
                'sdk_packages': {'emulator': {'Pkg.Revision': '37.2.13'}},
            }))
        directory = self.shards / 'e2e-shard-1'
        case = f'<testcase file="{self.flow}" name="One" time="3"/>'
        if mode == 'skipped':
            case = case.replace('/>', '><skipped message="skip"/></testcase>')
        elif mode == 'duplicate':
            case *= 2
        elif mode == 'assertion_then_deadline':
            case = case.replace('/>', '><failure message="assertion"/></testcase>')
        raw_report = directory / 'maestro-report.xml'
        write(raw_report, '<testsuite>' + case + '</testsuite>')
        if mode == 'missing':
            raw_report.unlink()
        elif mode == 'malformed':
            raw_report.write_text('<testsuite><testcase')
        elif mode in ('wrong_source', 'deadline', 'assertion_then_deadline'):
            receipt = json.loads((directory / 'execution.json').read_text())
            if mode == 'wrong_source':
                receipt['identity']['GITHUB_SHA'] = 'b' * 40
            else:
                receipt.update(phase='maestro', category='test' if mode == 'assertion_then_deadline' else 'budget',
                               result='failed', exit_code=17 if mode == 'assertion_then_deadline' else 124,
                               budget_expired=True, budget_expired_at=110)
            (directory / 'execution.json').write_text(json.dumps(receipt))
        write(self.native / 'native/TEST-native.xml', '<testsuite><testcase classname="fixture.NativeTest" name="native" time="1"/></testsuite>')
        write(self.jvm / 'TEST-component.xml', '<testsuite><testcase classname="fixture.ComponentTest" name="component" time="2"/></testsuite>')
        write(directory / 'sdk-cache.json', '{"packages":{"emulator":{"classification":"hit"}},"seconds":{"total":7}}')
        write(directory / 'preparation.json', '{"marks":{"tests-started":101,"tests-finished":110},"cache":"disabled"}')
        write(directory / 'maestro/current?/commands.json', 'raw evidence retained')
        write(directory / 'coverage/example.ec', 'coverage retained')
        gatherer.gather(self.root, self.plan, self.shards, self.canonical, self.native, self.jvm)

    def package(self, budgets=None):
        return compact.package(self.root, self.canonical, self.shards, self.native, self.jvm, self.out,
                               self.context, budgets or {'maestro': 2400, 'native': 600, 'jvm': 900})

    def mapping(self, directory):
        tests = []
        for name, prefix in (('maestro', ''), ('native', ''), ('jvm', 'jvm:')):
            tests += qase.parse_report((directory / (name + '-report.xml')).read_text(), prefix=prefix)
        config = json.loads((self.root / 'scripts/qase-cases.json').read_text())
        return qase.map_results(tests, config['cases'])

    def test_pass_missing_skipped_duplicate_malformed_wrong_source_and_deadline_preserve_gather_exactly(self):
        for mode in ('passed', 'missing', 'skipped', 'duplicate', 'malformed', 'wrong_source', 'deadline', 'assertion_then_deadline'):
            with self.subTest(mode=mode):
                self.generate(mode)
                result = self.package()
                status = json.loads((self.out / 'execution-status.json').read_text())
                self.assertEqual(mode == 'passed', status['passed'], status['problems'])
                self.assertNotIn('passed', result, 'packaging must not invent another verdict')
                for name in compact.CANONICAL:
                    self.assertEqual((self.canonical / name).read_bytes(), (self.out / name).read_bytes())
                self.assertEqual(self.mapping(self.canonical), self.mapping(self.out))
                self.assertEqual(3, len(list(ET.parse(self.out / 'maestro-report.xml').getroot().iter('testcase'))) +
                                 len(list(ET.parse(self.out / 'native-report.xml').getroot().iter('testcase'))) +
                                 len(list(ET.parse(self.out / 'jvm-report.xml').getroot().iter('testcase'))))
                if mode == 'assertion_then_deadline':
                    self.assertEqual('test', status['suites'][0]['category'])
                    self.assertTrue(status['suites'][0]['budget_expired'])

    def test_files_hashes_original_receipt_timing_sdk_and_raw_links_are_self_contained(self):
        self.generate()
        result = self.package()
        self.assertEqual(self.context, result['metadata'])
        self.assertEqual(3, len(result['suites']))
        first = result['suites'][0]
        self.assertEqual({'name': 'e2e-shard-1', 'url': 'https://github.com/fin-assist/android/actions/runs/42/attempts/2#artifacts'}, first['artifact'])
        self.assertEqual(2400, first['budget_seconds'])
        self.assertIsNone(first['maestro_archive_cache'])
        for filename, info in result['files'].items():
            payload = (self.out / filename).read_bytes()
            self.assertEqual({'bytes': len(payload), 'sha256': hashlib.sha256(payload).hexdigest()}, info)
        for key, name in zip(('execution', 'preparation', 'sdk_cache'), compact.EVIDENCE):
            self.assertEqual((self.shards / 'e2e-shard-1' / name).read_bytes(), (self.out / first['evidence'][key]).read_bytes())
        self.assertEqual({'execution-status.json', 'expected-tests.json', 'qase-cases.json', 'maestro-report.xml',
                          'native-report.xml', 'jvm-report.xml'}, {name for name in result['files'] if '/' not in name})
        self.assertFalse(any('coverage' in path or path.endswith('commands.json') for path in result['files']))
        self.assertEqual('raw evidence retained', (self.shards / 'e2e-shard-1/maestro/current?/commands.json').read_text())

    def test_missing_evidence_and_old_attempts_remain_visible_without_fabricated_timings_or_budget(self):
        self.generate()
        (self.native / 'execution.json').unlink()
        self.package(budgets={'maestro': None})
        result = json.loads((self.out / 'result.json').read_text())
        self.assertEqual(1, int(json.loads((self.out / result['suites'][0]['evidence']['execution']).read_text())['identity']['GITHUB_RUN_ATTEMPT']))
        self.assertIsNone(result['suites'][1]['evidence']['execution'])
        self.assertIsNone(result['suites'][0]['budget_seconds'])

    def test_delivery_is_separate_and_manual_absence_never_claims_publication(self):
        self.generate()
        self.context['event_name'] = 'workflow_dispatch'
        self.assertEqual({'path': None, 'reason': 'manual_not_published'}, self.package()['delivery'])
        write(self.canonical / 'qase-delivery.json', '{"schema":1,"result":"failed","case_count":2}')
        result = self.package()
        self.assertEqual({'path': 'qase-delivery.json', 'reason': 'recorded'}, result['delivery'])
        self.assertEqual((self.canonical / 'qase-delivery.json').read_bytes(), (self.out / 'qase-delivery.json').read_bytes())
        self.assertTrue(json.loads((self.out / 'execution-status.json').read_text())['passed'])

    def test_push_or_unknown_event_without_delivery_never_becomes_manual_not_published(self):
        self.generate()
        for event in ('push', None):
            with self.subTest(event=event):
                self.context['event_name'] = event
                self.assertEqual({'path': None, 'reason': 'not_recorded'}, self.package()['delivery'])

    def test_missing_canonical_file_removes_old_bundle_instead_of_republishing_a_pass(self):
        for name in compact.CANONICAL:
            with self.subTest(name=name):
                self.generate()
                self.package()
                (self.canonical / name).unlink()
                with self.assertRaises((ValueError, FileNotFoundError)):
                    self.package()
                self.assertFalse(self.out.exists())

    def test_invalid_metadata_cli_fails_without_retaining_a_previous_pass(self):
        self.generate()
        self.package()
        path = self.root / 'metadata.json'
        path.write_text('{bad metadata')
        result = subprocess.run([sys.executable, compact.__file__, '--metadata', str(path), '--out', str(self.out)],
                                cwd=self.root, capture_output=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.out.exists())

    def test_invalid_budget_cli_removes_owned_previous_pass_before_value_validation(self):
        self.generate()
        for kind in ('maestro', 'native', 'jvm'):
            for value in ('0', 'not-a-number'):
                with self.subTest(kind=kind, value=value):
                    self.package()
                    result = subprocess.run([sys.executable, compact.__file__, '--out', str(self.out),
                                             '--' + kind + '-budget-seconds', value],
                                            cwd=self.root, capture_output=True, text=True, timeout=5)
                    self.assertEqual(2, result.returncode, result.stderr)
                    self.assertIn('process budget must be positive seconds', result.stderr)
                    self.assertFalse(self.out.exists(), 'invalid CLI budget retained the previous PASS bundle')

    def test_help_and_syntax_errors_leave_output_alone_and_foreign_output_is_never_deleted(self):
        self.generate()
        self.package()
        original = (self.out / 'result.json').read_bytes()
        for arguments, code in ((['--help'], 0), (['--unknown'], 2), (['--maestro-budget-seconds'], 2)):
            with self.subTest(arguments=arguments):
                result = subprocess.run([sys.executable, compact.__file__, '--out', str(self.out), *arguments],
                                        cwd=self.root, capture_output=True, timeout=5)
                self.assertEqual(code, result.returncode)
                self.assertEqual(original, (self.out / 'result.json').read_bytes())
        unrelated = self.root / 'foreign-output'
        write(unrelated / 'keep', 'keep')
        result = subprocess.run([sys.executable, compact.__file__, '--out', str(unrelated),
                                 '--maestro-budget-seconds', '0'], cwd=self.root, capture_output=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('keep', (unrelated / 'keep').read_text())

    def test_workflow_only_uploads_compact_output_after_successful_packaging_but_keeps_raw_on_failure(self):
        workflow = (Path(__file__).resolve().parents[1] / '.github/workflows/e2e.yml').read_text()
        package, upload = workflow.split('      - name: Package compact UI result\n', 1)[1].split('      - name: Upload compact UI result\n', 1)
        self.assertIn('id: compact', package)
        compact_upload, raw_upload = upload.split('      - uses: actions/upload-artifact@v7', 1)
        self.assertIn("if: ${{ always() && steps.compact.outcome == 'success' }}", compact_upload)
        self.assertIn('if: always()', raw_upload)
        self.assertIn('name: e2e-reports', raw_upload)

    def test_symlink_evidence_cannot_copy_unrelated_files_and_output_cannot_delete_inputs(self):
        self.generate()
        receipt = self.native / 'execution.json'
        receipt.unlink()
        secret = self.root / 'unrelated-secret'
        secret.write_text('private')
        receipt.symlink_to(secret)
        with self.assertRaisesRegex(ValueError, 'regular input'):
            self.package()
        self.assertFalse(self.out.exists())
        with self.assertRaisesRegex(ValueError, 'input directory'):
            compact.package(self.root, self.canonical, self.shards, self.native, self.jvm, self.root, self.context, {})
        self.assertTrue((self.canonical / 'execution-status.json').exists())

    def test_metadata_only_reads_allowlisted_environment_and_requires_run_source_identity(self):
        env = {name: self.context[key] for key, name in compact.METADATA_ENV.items()}
        env['QASE_API_TOKEN'] = 'never-copy-this'
        result = compact.metadata(env)
        self.assertNotIn('never-copy-this', json.dumps(result))
        for key in ('source_sha', 'run_id', 'run_attempt', 'repository', 'server_url'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                compact.metadata({}, dict(self.context, **{key: None}))

    def test_unrelated_output_directory_or_file_cannot_be_deleted_by_a_cli_typo(self):
        self.generate()
        for path in (self.root / 'app', self.root / 'unrelated-file'):
            with self.subTest(path=path):
                if path.name == 'unrelated-file':
                    path.write_text('keep')
                else:
                    write(path / 'keep', 'keep')
                with self.assertRaisesRegex(ValueError, 'output'):
                    compact.package(self.root, self.canonical, self.shards, self.native, self.jvm, path, self.context, {})
                self.assertEqual('keep', (path if path.is_file() else path / 'keep').read_text())

    def test_previous_bundle_with_foreign_payload_wrong_schema_or_symlink_is_not_owned(self):
        self.generate()
        for mode in ('extra-file', 'unlisted-name', 'traversal', 'schema2', 'symlink'):
            with self.subTest(mode=mode):
                self.out = self.root / ('prior-' + mode)
                self.package()
                index = self.out / 'result.json'
                data = json.loads(index.read_text())
                if mode == 'extra-file':
                    foreign = self.out / 'notes.txt'
                    foreign.write_text('keep')
                elif mode in ('unlisted-name', 'traversal'):
                    name = 'other.json' if mode == 'unlisted-name' else '../outside'
                    foreign = self.out / name
                    foreign.write_text('keep')
                    data['files'][name] = {'bytes': 4, 'sha256': hashlib.sha256(b'keep').hexdigest()}
                elif mode == 'schema2':
                    data['schema'] = 2
                    foreign = self.out / 'expected-tests.json'
                else:
                    foreign = self.root / 'outside-receipt'
                    foreign.write_text('keep')
                    link = self.out / 'evidence/e2e-shard-1/execution.json'
                    link.unlink()
                    link.symlink_to(foreign)
                index.write_text(json.dumps(data))
                kept = foreign.read_bytes()
                with self.assertRaisesRegex(ValueError, 'unrecognized output'):
                    self.package()
                self.assertTrue(index.exists(), 'an unrecognized bundle was removed')
                self.assertEqual(kept, foreign.read_bytes())


if __name__ == '__main__':
    unittest.main()
