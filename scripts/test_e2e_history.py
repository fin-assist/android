"""History is a consumer of canonical gather results, not a second test-verdict engine."""
import contextlib
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
import zipfile


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    loaded = importlib.util.module_from_spec(spec)
    sys.modules[name] = loaded
    spec.loader.exec_module(loaded)
    return loaded


history = module('ui_history', 'e2e-history.py')
gather = module('ui_history_gather', 'e2e-shards.py')
REPO = 'fin-assist/android'
SHA = 'a' * 40


def encoded(value):
    return (json.dumps(value, sort_keys=True) + '\n').encode()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(value if isinstance(value, bytes) else value.encode())


def run_metadata(attempt=1, conclusion='success', run_id=42):
    return {'id': run_id, 'head_sha': SHA, 'run_attempt': attempt, 'repository': {'full_name': REPO}, 'head_repository': {'full_name': REPO},
            'event': 'push', 'path': '.github/workflows/e2e.yml', 'head_branch': 'main', 'name': 'UI tests', 'status': 'completed',
            'conclusion': conclusion}


def artifact_metadata(attempt=1, run_id=42):
    return {'id': 900 + attempt, 'name': f'e2e-result-attempt-{attempt}', 'expired': False,
            'workflow_run': {'id': run_id, 'head_sha': SHA}}


def archive(files):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items():
            z.writestr(name, data)
    return out.getvalue()


def reindex(files, index=None):
    index = copy.deepcopy(index or history.load_json(files['result.json']))
    index['files'] = {name: {'sha256': history.digest(data), 'bytes': len(data)}
                      for name, data in files.items() if name != 'result.json'}
    files['result.json'] = encoded(index)
    return files


class HistoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.flow = '.maestro/flows/one.yaml'
        self.native_key = 'fixture.NativeTest#native'
        self.jvm_key = 'jvm:fixture.ComponentTest#component'
        self.cases = {self.flow: 1, self.native_key: 2, self.jvm_key: 2}
        write(self.root / self.flow, 'name: One\nappId: fixture\n---\n- launchApp\n')
        write(self.root / 'app/src/androidTestMock/kotlin/fixture/NativeTest.kt',
              'package fixture\nclass NativeTest { @Test fun native() {} }')
        write(self.root / 'feature/example/src/test/kotlin/fixture/ComponentTest.kt',
              'package fixture\nclass ComponentTest { @Test fun component() {} }')
        write(self.root / 'scripts/qase-cases.json', encoded({'project': 'PLAINFIN', 'cases': self.cases}))
        env = patch.dict(os.environ, {'GITHUB_SHA': SHA, 'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '1'})
        env.start()
        self.addCleanup(env.stop)

    def fixture(self, mode='passed', attempt=1):
        raw, canonical = self.root / 'raw', self.root / 'canonical'
        dirs = {'maestro': raw / 'shards/e2e-shard-1', 'native': raw / 'e2e-native', 'jvm': raw / 'e2e-components'}
        receipt = {'schema': 1, 'phase': 'complete', 'category': None, 'result': 'passed', 'exit_code': 0,
                   'identity': {'GITHUB_SHA': SHA, 'GITHUB_RUN_ID': '42', 'GITHUB_RUN_ATTEMPT': '1',
                                'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64', 'ImageOS': 'ubuntu24',
                                'ImageVersion': '20260101.1', 'MAESTRO_VERSION': '2.11.0', 'MAESTRO_SHA256': '1' * 64},
                   'apks': {'app-mock-e2e.apk': '2' * 64, 'app-mock-e2e-androidTest.apk': '3' * 64},
                   'java_launcher': 'openjdk version 21', 'gradle_daemon_configuration_sha256': '4' * 64,
                   'sdk_packages': {'emulator': {'Pkg.Revision': '37.2.13'}, 'image': {'Pkg.Revision': '14'}}}
        for directory in dirs.values():
            write(directory / 'execution.json', encoded(receipt))
            write(directory / 'preparation.json', encoded({'marks': {'tests-started': 100, 'tests-finished': 110},
                                                         'boot_mode': 'fresh-cold-direct', 'cache': 'disabled'}))
            write(directory / 'sdk-cache.json', encoded({'packages': {name: {'classification': 'miss',
                  'actual': {'revision': '14', 'source_properties_sha256': '5' * 64}}
                  for name in ('image', 'emulator')}}))
        maestro = f'<testcase file="{self.flow}" name="One" time="3"/>'
        if mode == 'test' or mode == 'assertion_then_deadline':
            maestro = maestro.replace('/>', '><failure message="assertion"/></testcase>')
        elif mode == 'skipped':
            maestro = maestro.replace('/>', '><skipped message="skip"/></testcase>')
        elif mode == 'duplicate':
            maestro *= 2
        write(dirs['maestro'] / 'maestro-report.xml', '<testsuite>' + maestro + '</testsuite>')
        if mode in ('setup', 'device', 'budget', 'reporting', 'assertion_then_deadline'):
            changed = copy.deepcopy(receipt)
            cause = 'test' if mode == 'assertion_then_deadline' else mode
            changed.update(phase='maestro' if mode in ('budget', 'assertion_then_deadline') else mode,
                           category=cause, result='not_run' if mode in ('setup', 'device') else 'failed', exit_code=17)
            if mode in ('budget', 'assertion_then_deadline'):
                changed['budget_expired'] = True
            write(dirs['maestro'] / 'execution.json', encoded(changed))
            if mode != 'assertion_then_deadline':
                (dirs['maestro'] / 'maestro-report.xml').unlink()
        elif mode == 'malformed_report':
            write(dirs['maestro'] / 'maestro-report.xml', '<testsuite><testcase')
        elif mode == 'wrong_source':
            changed = copy.deepcopy(receipt)
            changed['identity']['GITHUB_SHA'] = 'b' * 40
            write(dirs['maestro'] / 'execution.json', encoded(changed))
        write(dirs['native'] / 'native/TEST-native.xml', '<testsuite><testcase classname="fixture.NativeTest" name="native" time="1"/></testsuite>')
        write(dirs['jvm'] / 'TEST-jvm.xml', '<testsuite><testcase classname="fixture.ComponentTest" name="component" time="2"/></testsuite>')
        plan = gather.plan([self.flow], {}, 1)
        gather.gather(self.root, plan, raw / 'shards', canonical, dirs['native'], dirs['jvm'])
        files = {name: (canonical / name).read_bytes() for name in history.CANONICAL - {'qase-cases.json'}}
        files['qase-cases.json'] = (self.root / 'scripts/qase-cases.json').read_bytes()
        index = {'schema': 1, 'metadata': {'repository': REPO, 'server_url': 'https://github.com',
                 'source_sha': SHA, 'run_id': '42', 'run_attempt': str(attempt), 'event_name': 'push',
                 'ref': 'refs/heads/main', 'ref_name': 'main', 'workflow': 'UI tests',
                 'workflow_ref': REPO + '/.github/workflows/e2e.yml@refs/heads/main', 'workflow_sha': SHA,
                 'job_results': {'shard': 'success', 'native': 'success', 'components': 'success'}},
                 'suites': [], 'delivery': {'path': None, 'reason': 'not_recorded'},
                 'raw_reports': {'name': 'e2e-reports', 'url': 'ignored'}}
        for kind, directory in dirs.items():
            evidence = {}
            for key, name in [('execution', 'execution.json'), ('preparation', 'preparation.json'), ('sdk_cache', 'sdk-cache.json')]:
                path = f'evidence/{directory.name}/{name}'
                evidence[key] = path
                files[path] = (directory / name).read_bytes()
            index['suites'].append({'kind': kind, 'index': 1 if kind == 'maestro' else 0, 'budget_seconds': 20,
                                    'maestro_archive_cache': None, 'evidence': evidence,
                                    'artifact': {'name': directory.name, 'url': 'ignored'}})
        return reindex(files, index)

    def evaluate(self, files, run=None, artifact=None):
        run = run or run_metadata()
        blob = archive(files)
        artifact = dict(artifact or artifact_metadata(run['run_attempt'], run['id']))
        artifact.setdefault('digest', 'sha256:' + history.digest(blob))
        report = history.history(REPO, [run], {(run['id'], run['run_attempt']): [blob]}, [artifact])
        return report['attempts'][-1]

    def change_json(self, files, path, change):
        value = history.load_json(files[path])
        change(value)
        files[path] = encoded(value)
        reindex(files)

    def test_canonical_real_gather_main_and_failure_paths(self):
        for mode in ('passed', 'test', 'setup', 'device', 'budget', 'reporting', 'assertion_then_deadline',
                     'skipped', 'duplicate', 'malformed_report'):
            with self.subTest(mode=mode):
                row = self.evaluate(self.fixture(mode), run_metadata(conclusion='success' if mode == 'passed' else 'failure'))
                self.assertEqual('verified', row['evidence_status'], row)
                self.assertEqual(mode == 'passed', row['application_passed'])
                self.assertEqual(mode == 'passed', row['full_pass'])
                if mode == 'passed':
                    self.assertEqual({'passed': 3, 'expected': 3}, row['components'])
                    self.assertEqual({'passed': 2, 'expected': 2}, row['qase_cases'])
                    self.assertEqual(10, row['suites'][1]['process_seconds'])
                    self.assertEqual(10, row['suites'][1]['remaining_budget_seconds'])
                if mode in ('setup', 'device', 'budget', 'reporting'):
                    maestro = next(s for s in row['suites'] if s['kind'] == 'maestro')
                    self.assertEqual(mode, maestro['category'])
                    self.assertEqual(1, maestro['missing'])
                    self.assertEqual(1, row['qase_cases']['skipped'])
                if mode == 'assertion_then_deadline':
                    maestro = next(s for s in row['suites'] if s['kind'] == 'maestro')
                    self.assertEqual('test', maestro['category'])
                    self.assertTrue(maestro['budget_expired'])
                    self.assertEqual(1, row['qase_cases']['failed'])

    def test_first_and_intermediate_failures_survive_latest_pass_and_duplicate_inputs(self):
        first, middle, last = self.fixture('test'), self.fixture('device', 2), self.fixture('passed', 3)
        runs = [run_metadata(3), run_metadata(1, 'failure'), run_metadata(2, 'cancelled'), run_metadata(1, 'failure')]
        report = history.history(REPO, runs, {(42, 1): [archive(first), archive(first)], (42, 2): [archive(middle)], (42, 3): [archive(last)]},
                                 [artifact_metadata(1) | {'digest': 'sha256:' + history.digest(archive(first))}, artifact_metadata(2) | {'digest': 'sha256:' + history.digest(archive(middle))}, artifact_metadata(3) | {'digest': 'sha256:' + history.digest(archive(last))}])
        self.assertEqual(1, report['selected_runs'])
        self.assertEqual(0, report['first_attempt']['verified_full_pass'])
        self.assertEqual({'failure': 1}, report['first_attempt']['workflow_states'])
        self.assertEqual([1, 2, 3], [r['attempt'] for r in report['attempts']])
        self.assertEqual('cancelled', report['attempts'][1]['workflow_conclusion'])
        self.assertEqual('verified', report['attempts'][0]['evidence_status'])
        self.assertFalse(report['attempts'][0]['application_passed'])
        self.assertEqual(1, report['attempts'][0]['qase_cases']['failed'])
        self.assertEqual('verified', report['attempts'][1]['evidence_status'])
        middle_suite = next(s for s in report['attempts'][1]['suites'] if s['kind'] == 'maestro')
        self.assertEqual('device', middle_suite['category'])
        self.assertEqual(1, middle_suite['missing'])
        self.assertTrue(report['attempts'][2]['full_pass'])
        report = history.history(REPO, [run_metadata()], {(42, 1): [archive(first), archive(last)]}, [artifact_metadata()])
        self.assertEqual('conflicting_attempt_input', report['attempts'][0]['error'])
        report = history.history(REPO, [run_metadata(), run_metadata(conclusion='failure')], {}, [])
        self.assertEqual('conflicting_run_metadata', report['attempts'][0]['error'])

    def test_workflow_and_jobs_veto_full_pass_without_altering_application_or_qase(self):
        files = self.fixture()
        for conclusion in ('failure', 'cancelled', 'timed_out'):
            row = self.evaluate(files, run_metadata(conclusion=conclusion))
            self.assertTrue(row['application_passed'])
            self.assertFalse(row['full_pass'])
            self.assertEqual(2, row['qase_cases']['passed'])
        run = run_metadata() | {'jobs': [{'status': 'completed', 'conclusion': 'failure'}]}
        self.assertFalse(self.evaluate(files, run)['full_pass'])
        self.change_json(files, 'result.json', lambda x: x['metadata']['job_results'].update(native='failure'))
        self.assertFalse(self.evaluate(files)['full_pass'])

    def test_independent_workflow_and_event_qualify_acceptance_not_artifact_claims(self):
        for event, path, expected in [('pull_request', '.github/workflows/ci.yml', 'ineligible'),
                                      ('pull_request', '.github/workflows/e2e.yml', 'ineligible'),
                                      ('push', '.github/workflows/ci.yml', 'ineligible'),
                                      ('push', None, 'unknown'),
                                      (None, '.github/workflows/e2e.yml', 'unknown')]:
            with self.subTest(event=event, path=path):
                files = self.fixture()
                self.change_json(files, 'result.json', lambda x: x['metadata'].update(event_name=event))
                row = self.evaluate(files, run_metadata() | {'event': event, 'path': path})
                self.assertEqual('verified', row['evidence_status'])
                self.assertTrue(row['application_passed'], 'keep canonical observations, not UI acceptance')
                self.assertEqual({'passed': 2, 'expected': 2}, row['qase_cases'])
                self.assertFalse(row['full_pass'])
                self.assertIsNone(row['cohort'])
                self.assertEqual(expected, row['control_plane']['status'])
                self.assertEqual(expected + '_control_plane', row['cohort_status'])
        for head in ({'full_name': 'attacker/android'}, None):
            row = self.evaluate(self.fixture(), run_metadata() | {'head_repository': head})
            self.assertTrue(row['application_passed'])
            self.assertFalse(row['full_pass'])
            self.assertIsNone(row['cohort'])
            self.assertEqual('unknown' if head is None else 'ineligible', row['control_plane']['status'])
        files = self.fixture()
        self.change_json(files, 'result.json', lambda x: x['metadata'].update(event_name='workflow_dispatch'))
        row = self.evaluate(files, run_metadata() | {'event': 'workflow_dispatch'})
        self.assertTrue(row['full_pass'])
        self.assertEqual('eligible', row['control_plane']['status'])

    def test_delivery_is_separate_and_schema_checked(self):
        files = self.fixture()
        for result in ('failed', 'published', 'not_configured', 'pending'):
            files['qase-delivery.json'] = encoded({'schema': 1, 'result': result, 'run_id': 123, 'case_count': 2, 'unmapped_count': 0})
            self.change_json(files, 'result.json', lambda x: x.update(delivery={'path': 'qase-delivery.json', 'reason': 'recorded'}))
            row = self.evaluate(files)
            self.assertTrue(row['full_pass'])
            self.assertEqual(result, row['qase_delivery'])
        for broken in ({'schema': 1, 'result': 'published'},
                       {'schema': 1, 'result': 'published', 'run_id': None, 'case_count': 2, 'unmapped_count': 0},
                       {'schema': 1, 'result': 'published', 'run_id': 1, 'case_count': 1, 'unmapped_count': 0}):
            files['qase-delivery.json'] = encoded(broken)
            reindex(files)
            self.assertFalse(self.evaluate(files)['full_pass'])
        self.change_json(files, 'qase-delivery.json', lambda x: x.update(result='unknown'))
        self.assertEqual('invalid_delivery', self.evaluate(files)['error'])

    def test_missing_cancelled_unavailable_and_legacy_remain_in_denominator(self):
        report = history.history(REPO, [run_metadata(conclusion='cancelled')], {}, [], [42, 43], {(43, 1): 'github_request_failed'})
        self.assertEqual(2, report['selected_runs'])
        self.assertEqual({'cancelled': 1, 'unavailable': 1}, report['first_attempt']['workflow_states'])
        self.assertEqual(['legacy_or_missing', 'invalid'], [r['evidence_status'] for r in report['attempts']])
        self.assertEqual(0, report['first_attempt']['verified_full_pass'])

    def test_source_run_attempt_provider_hash_and_unknown_receipt_fail_closed(self):
        changes = [('source_mismatch', 'result.json', lambda x: x['metadata'].update(source_sha='b' * 40)),
                   ('attempt_mismatch', 'result.json', lambda x: x['metadata'].update(run_attempt='2')),
                   ('unknown_schema', 'result.json', lambda x: x.update(schema=2)),
                   ('unknown_receipt_schema', 'evidence/e2e-native/execution.json', lambda x: x.update(schema=9)),
                   ('receipt_source_mismatch', 'evidence/e2e-native/execution.json', lambda x: x['identity'].update(GITHUB_SHA='b' * 40)),
                   ('receipt_attempt_mismatch', 'evidence/e2e-native/execution.json', lambda x: x['identity'].update(GITHUB_RUN_ATTEMPT='2')),
                   ('invalid_receipt_deadline', 'evidence/e2e-native/execution.json', lambda x: x.update(budget_expired='true')),
                   ('invalid_suite_counts', 'execution-status.json', lambda x: x['suites'][0].update(planned=True)),
                   ('contradictory_canonical_status', 'execution-status.json', lambda x: x.update(problems=['missing']))]
        for code, path, change in changes:
            with self.subTest(code=code):
                files = self.fixture()
                self.change_json(files, path, change)
                row = self.evaluate(files)
                self.assertEqual(code, row['error'])
                self.assertFalse(row['full_pass'])
                self.assertNotIn('application_passed', row)
        files = self.fixture()
        files['native-report.xml'] += b' '
        self.assertEqual('hash_mismatch', self.evaluate(files)['error'])
        bad_artifact = artifact_metadata()
        bad_artifact['workflow_run']['head_sha'] = 'b' * 40
        self.assertEqual('artifact_source_mismatch', self.evaluate(self.fixture(), artifact=bad_artifact)['error'])
        self.assertEqual('receipt_source_mismatch', self.evaluate(self.fixture('wrong_source'))['error'])
        self.assertEqual('artifact_digest_mismatch', self.evaluate(self.fixture(), artifact=artifact_metadata() | {'digest': 'sha256:' + 'f' * 64})['error'])
        row = self.evaluate(self.fixture(), artifact=artifact_metadata() | {'digest': None})
        self.assertEqual('unverified', row['evidence_status'])
        self.assertFalse(row['full_pass'])


    def test_every_passing_suite_requires_original_receipt_and_emulator_apks(self):
        for folder in ('e2e-shard-1', 'e2e-native', 'e2e-components'):
            files = self.fixture()
            files.pop(f'evidence/{folder}/execution.json')
            self.change_json(files, 'result.json', lambda x: next(s for s in x['suites'] if s['evidence']['execution'] == f'evidence/{folder}/execution.json')['evidence'].update(execution=None))
            self.assertEqual('missing_receipt', self.evaluate(files)['error'])
        for folder in ('e2e-shard-1', 'e2e-native'):
            files = self.fixture()
            self.change_json(files, f'evidence/{folder}/execution.json', lambda x: x['apks'].pop('app-mock-e2e-androidTest.apk'))
            self.assertEqual('missing_apk_identity', self.evaluate(files)['error'])

    def test_failed_attempt_cannot_attest_observed_results_without_every_suite_receipt(self):
        for folder in ('e2e-shard-1', 'e2e-native', 'e2e-components'):
            files = self.fixture('test')
            files.pop(f'evidence/{folder}/execution.json')
            self.change_json(files, 'result.json', lambda x: next(s for s in x['suites'] if s['evidence']['execution'] == f'evidence/{folder}/execution.json')['evidence'].update(execution=None))
            row = self.evaluate(files, run_metadata(conclusion='failure'))
            self.assertEqual('missing_receipt', row['error'])
            self.assertEqual('invalid', row['evidence_status'])
            self.assertNotIn('qase_cases', row, 'unattested observations cannot become current Qase passes')

    def test_every_duplicate_zip_must_match_provider_digest_regardless_of_order(self):
        files = self.fixture()
        original = archive(files)
        other = io.BytesIO()
        with zipfile.ZipFile(other, 'w', zipfile.ZIP_STORED) as z:
            for name, data in files.items():
                z.writestr(name, data)
        repacked = other.getvalue()
        self.assertNotEqual(history.digest(original), history.digest(repacked))
        artifact = artifact_metadata() | {'digest': 'sha256:' + history.digest(original)}
        for sources in ([original, repacked], [repacked, original]):
            report = history.history(REPO, [run_metadata()], {(42, 1): sources}, [artifact])
            self.assertEqual('artifact_digest_mismatch', report['attempts'][0]['error'])
            self.assertFalse(report['attempts'][0]['full_pass'])
        report = history.history(REPO, [run_metadata()], {(42, 1): [original, original]}, [artifact])
        self.assertTrue(report['attempts'][0]['full_pass'])

    def test_broken_deflate_is_one_invalid_row_and_keeps_other_runs(self):
        out = io.BytesIO()
        with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
            z.writestr('result.json', '{}')
        broken = bytearray(out.getvalue())
        broken[30 + len('result.json')] = 7  # Reserved DEFLATE block type, valid ZIP directory/header.
        invalid_utf8 = bytearray(out.getvalue())
        central = invalid_utf8.index(b'PK\x01\x02')
        invalid_utf8[6:8] = (0x800).to_bytes(2, 'little')
        invalid_utf8[central + 8:central + 10] = (0x800).to_bytes(2, 'little')
        invalid_utf8[30] = invalid_utf8[central + 46] = 0xff
        files = self.fixture()
        good = archive(files)
        for malformed in (bytes(broken), bytes(invalid_utf8)):
            report = history.history(REPO, [run_metadata(), run_metadata(run_id=43)],
                                     {(42, 1): [good], (43, 1): [malformed]},
                                     [artifact_metadata() | {'digest': 'sha256:' + history.digest(good)},
                                      artifact_metadata(run_id=43) | {'digest': 'sha256:' + history.digest(malformed)}])
            self.assertEqual(2, report['selected_runs'])
            self.assertTrue(report['attempts'][0]['full_pass'])
            self.assertEqual('invalid_zip', report['attempts'][1]['error'])
            self.assertEqual('invalid', report['attempts'][1]['evidence_status'])

    def test_canonical_duplicates_unknown_keys_missing_and_unsafe_xml_rejected(self):
        for data in (b'<testsuite><testcase classname="unknown" name="native"/></testsuite>',
                     b'<testsuite/>',
                     b'<testsuite><testcase classname="fixture.NativeTest" name="native"/><testcase classname="fixture.NativeTest" name="native"/></testsuite>',
                     b'<!DOCTYPE testsuite [<!ENTITY x "payload">]><testsuite/>',
                     '<!DOCTYPE testsuite [<!ENTITY x "probe">]><testsuite><testcase name="&x;"/></testsuite>'.encode('utf-16-le')):
            files = self.fixture()
            files['native-report.xml'] = data
            reindex(files)
            self.assertIn(self.evaluate(files)['error'], {'canonical_inventory_mismatch', 'unsafe_xml'})

    def test_dtd_and_entity_guards_reject_xml_with_otherwise_matching_inventory(self):
        dtd = '<!DOCTYPE testsuite><testsuite><testcase classname="fixture.NativeTest" name="native"/></testsuite>'
        entity = '<!DOCTYPE testsuite [<!ENTITY method "native">]><testsuite><testcase classname="fixture.NativeTest" name="&method;"/></testsuite>'
        for data in (dtd.encode('utf-8'), entity.encode('utf-8'), entity.encode('utf-16-le')):
            files = self.fixture()
            files['native-report.xml'] = data
            reindex(files)
            row = self.evaluate(files)
            self.assertEqual('unsafe_xml', row.get('error'), 'the inventory itself is valid; the XML guard must reject it')
            self.assertFalse(row['full_pass'])
            self.assertNotIn('application_passed', row)

    def test_cohort_requires_exact_source_inventory_workflow_tools_runner_sdk_not_apk_signing(self):
        base = self.evaluate(self.fixture())
        self.assertIsNotNone(base['cohort'])
        for path, change in [
            ('result.json', lambda x: x['metadata'].update(workflow_sha='b' * 40)),
            ('evidence/e2e-native/execution.json', lambda x: x['identity'].update(ImageVersion='new')),
            ('evidence/e2e-shard-1/execution.json', lambda x: x['identity'].update(MAESTRO_VERSION='new')),
            ('evidence/e2e-native/sdk-cache.json', lambda x: x['packages']['image']['actual'].update(revision='new')),
            ('qase-cases.json', lambda x: x['cases'].update({self.flow: 3})),
        ]:
            files = self.fixture()
            self.change_json(files, path, change)
            self.assertNotEqual(base['cohort'], self.evaluate(files)['cohort'])
        files = self.fixture()
        self.change_json(files, 'evidence/e2e-native/execution.json', lambda x: x['apks'].update({'app-mock-e2e.apk': 'f' * 64}))
        self.assertEqual(base['cohort'], self.evaluate(files)['cohort'])
        self.change_json(files, 'result.json', lambda x: x['metadata'].update(workflow_sha=None))
        row = self.evaluate(files)
        self.assertTrue(row['application_passed'])
        self.assertIsNone(row['cohort'])
        self.assertEqual('incomplete_configuration', row['cohort_status'])

    def test_avd_cache_outcome_does_not_split_the_snapshot_policy_cohort(self):
        rows = {}
        for outcome in ('hit', 'miss-or-invalid', 'disabled', 'unknown-outcome'):
            files = self.fixture()
            self.change_json(files, 'evidence/e2e-native/preparation.json',
                             lambda x: x.update(cache=outcome, boot_mode='snapshot-verified'))
            rows[outcome] = self.evaluate(files)
        self.assertIsNotNone(rows['hit']['cohort'])
        self.assertEqual(rows['hit']['cohort'], rows['miss-or-invalid']['cohort'])
        self.assertNotEqual(rows['hit']['cohort'], rows['disabled']['cohort'])
        self.assertIsNone(rows['unknown-outcome']['cohort'])
        for outcome in ('hit', 'miss-or-invalid', 'disabled'):
            native = next(s for s in rows[outcome]['suites'] if s['kind'] == 'native')
            self.assertEqual(outcome, native['cache']['avd'])
            self.assertEqual('disabled' if outcome == 'disabled' else 'snapshot', native['cache']['avd_policy'])

    def test_reconstructed_observations_never_look_like_a_failed_verified_cohort_member(self):
        original = archive(self.fixture())
        verified = archive(self.fixture(attempt=2))
        artifact = artifact_metadata(2) | {'digest': 'sha256:' + history.digest(verified)}
        report = history.history(REPO, [run_metadata(1), run_metadata(2)],
                                 {(42, 1): [original], (42, 2): [verified]}, [artifact], reconstructed=True)
        replay, observed = report['attempts']
        self.assertTrue(replay['application_passed'])
        self.assertFalse(replay['full_pass'])
        self.assertIsNone(replay['cohort'])
        self.assertEqual('reconstructed', replay['cohort_status'])
        self.assertTrue(observed['full_pass'])
        self.assertIsNotNone(observed['cohort'])
        self.assertEqual([{'run_id': 42, 'attempt': 2, 'full_pass': True}],
                         report['cohorts'][observed['cohort']])
        self.assertEqual(1, report['first_attempt']['reconstructed_application_pass'])

    def test_unknown_java_avd_cache_or_receipt_sdk_remains_incomparable(self):
        for path, change in [
            ('evidence/e2e-native/execution.json', lambda x: x.update(java_launcher='')),
            ('evidence/e2e-native/execution.json', lambda x: x.update(java_launcher='unavailable')),
            ('evidence/e2e-native/execution.json', lambda x: x.pop('sdk_packages')),
            ('evidence/e2e-native/preparation.json', lambda x: x.pop('cache')),
            ('evidence/e2e-native/preparation.json', lambda x: x.update(cache=['hit'])),
            ('evidence/e2e-native/preparation.json', lambda x: x.update(cache={})),
        ]:
            files = self.fixture()
            self.change_json(files, path, change)
            row = self.evaluate(files)
            self.assertTrue(row['application_passed'])
            self.assertIsNone(row['cohort'])
            self.assertEqual('incomplete_configuration', row['cohort_status'])

    def test_reconstruction_cannot_bypass_a_provider_record(self):
        blob = archive(self.fixture())
        key = (42, 1)
        artifact = artifact_metadata() | {'digest': 'sha256:' + history.digest(blob)}
        report = history.history(REPO, [run_metadata()], {key: [blob]}, [artifact], reconstructed=True)
        self.assertEqual('verified', report['attempts'][0]['evidence_status'])
        self.assertEqual('github_artifact', report['attempts'][0]['artifact_provenance'])
        self.assertTrue(report['attempts'][0]['full_pass'])
        artifact['digest'] = 'sha256:' + 'f' * 64
        report = history.history(REPO, [run_metadata()], {key: [blob]}, [artifact], reconstructed=True)
        self.assertEqual('artifact_digest_mismatch', report['attempts'][0]['error'])
        self.assertFalse(report['attempts'][0]['full_pass'])
        report = history.history(REPO, [run_metadata()], {key: [blob]}, [], reconstructed=True)
        self.assertEqual('reconstructed', report['attempts'][0]['evidence_status'])
        self.assertTrue(report['attempts'][0]['application_passed'])
        self.assertFalse(report['attempts'][0]['full_pass'])
        self.assertEqual(0, report['first_attempt']['verified_full_pass'])
        self.assertEqual(1, report['first_attempt']['reconstructed_application_pass'])

    def test_unknown_cache_missing_marks_and_negative_remaining_budget_are_not_guessed(self):
        files = self.fixture()
        self.change_json(files, 'evidence/e2e-native/preparation.json', lambda x: x.update(marks={}))
        self.change_json(files, 'evidence/e2e-native/sdk-cache.json', lambda x: x['packages']['emulator'].update(classification='hit'))
        row = self.evaluate(files)
        native = next(s for s in row['suites'] if s['kind'] == 'native')
        self.assertIsNone(native['process_seconds'])
        self.assertEqual('unknown', native['cache']['emulator'])
        self.assertEqual('unknown', native['cache']['maestro_archive'])
        self.change_json(files, 'evidence/e2e-native/preparation.json', lambda x: x.update(marks={'tests-started': 100, 'tests-finished': 130}))
        native = next(s for s in self.evaluate(files)['suites'] if s['kind'] == 'native')
        self.assertEqual(-10, native['remaining_budget_seconds'])

    def test_zip_traversal_symlinks_duplicates_sizes_json_duplicates_and_directory_symlinks(self):
        files = self.fixture()
        for name in ('../outside', '/absolute', 'evidence/../../outside', 'evidence\\execution.json'):
            with self.subTest(name=name):
                with self.assertRaisesRegex(history.Invalid, 'unsafe_zip_path'):
                    history.bundle_files(archive(files | {name: b'x'}))
        out = io.BytesIO()
        with zipfile.ZipFile(out, 'w') as z:
            link = zipfile.ZipInfo('result.json')
            link.create_system = 3
            link.external_attr = (stat.S_IFLNK | 0o777) << 16
            z.writestr(link, 'outside')
        with self.assertRaisesRegex(history.Invalid, 'unsafe_zip_entry'):
            history.bundle_files(out.getvalue())
        with self.assertRaisesRegex(history.Invalid, 'duplicate_json_member'):
            history.load_json(b'{"schema":1,"schema":1}')
        with self.assertRaisesRegex(history.Invalid, 'invalid_number'):
            history.load_json(b'{"bad":NaN}')
        with patch.object(history, 'MAX_FILE', 10):
            with self.assertRaisesRegex(history.Invalid, 'file_too_large'):
                history.bundle_files(archive({'result.json': b'x' * 11}))
        with patch.object(history, 'MAX_BUNDLE', 10):
            with self.assertRaisesRegex(history.Invalid, 'bundle_too_large'):
                history.bundle_files(archive({'result.json': b'x' * 11}))
        out = io.BytesIO()
        with contextlib.redirect_stderr(io.StringIO()), zipfile.ZipFile(out, 'w') as z:
            z.writestr('result.json', b'{}')
            z.writestr('result.json', b'{}')
        with self.assertRaisesRegex(history.Invalid, 'duplicate_zip_entry'):
            history.bundle_files(out.getvalue())
        folder = self.root / 'links'
        folder.mkdir()
        (folder / 'result.json').symlink_to(self.root / 'scripts/qase-cases.json')
        with self.assertRaisesRegex(history.Invalid, 'unsafe_bundle_path'):
            history.bundle_files(folder)

    def test_offline_cli_deterministic_safe_markdown_and_explicit_reconstruction(self):
        bundle = self.root / 'bundle.zip'
        bundle.write_bytes(archive(self.fixture()))
        metadata = self.root / 'metadata.json'
        metadata.write_bytes(encoded({'repository': REPO, 'runs': [run_metadata()], 'artifacts': [artifact_metadata() | {'digest': 'sha256:' + history.digest(bundle.read_bytes())}]}))
        args = [sys.executable, str(Path(history.__file__)), '--metadata', str(metadata), '--bundle', f'42:1={bundle}', '--format', 'json']
        first = self.run_cli(args)
        self.assertEqual(first, self.run_cli(args))
        self.assertTrue(json.loads(first)['attempts'][0]['full_pass'])
        metadata.write_bytes(encoded({'repository': REPO, 'runs': [run_metadata()], 'artifacts': []}))
        result = json.loads(self.run_cli(args + ['--local-reconstruction']))
        self.assertEqual('local_reconstruction', result['attempts'][0]['artifact_provenance'])
        self.assertEqual('reconstructed', result['attempts'][0]['evidence_status'])
        self.assertTrue(result['attempts'][0]['application_passed'])
        self.assertFalse(result['attempts'][0]['full_pass'])
        self.assertEqual(0, result['first_attempt']['verified_full_pass'])
        self.assertEqual(1, result['first_attempt']['reconstructed_application_pass'])
        self.assertEqual('ambiguous_artifact_metadata', json.loads(self.run_cli(args))['attempts'][0]['error'])
        text = history.markdown(result)
        self.assertIn('local_reconstruction', text)
        self.assertNotIn('](javascript:', history.escape('[x](javascript:bad)|<script>\n'))
        self.assertIn('42 / 1](https://github.com/fin-assist/android/', text)

    def run_cli(self, args):
        process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        try:
            out, err = process.communicate(timeout=5)
            self.assertEqual(0, process.returncode, err)
            return out
        finally:
            history.stop_process(process)

    def test_online_collect_fetches_every_attempt_and_downloads_only_fixed_api_endpoint(self):
        bundles = {1: archive(self.fixture('test')), 2: archive(self.fixture('device', 2)), 3: archive(self.fixture('passed', 3))}
        calls = []
        def get(endpoint, binary=False):
            calls.append(endpoint)
            if '/workflows/' in endpoint:
                return {'workflow_runs': [run_metadata(3)]}
            if '/artifacts?' in endpoint:
                return {'artifacts': [artifact_metadata(a) | {'digest': 'sha256:' + history.digest(bundles[a]), 'archive_download_url': 'https://evil.invalid/secret'} for a in (1, 2, 3)]}
            if '/jobs?' in endpoint:
                return {'jobs': [], 'total_count': 0}
            if '/attempts/' in endpoint:
                attempt = int(endpoint.rsplit('/', 1)[1])
                return run_metadata(attempt, 'failure')
            if '/artifacts/' in endpoint:
                return bundles[int(endpoint.split('/')[-2]) - 900]
            self.fail(endpoint)
        client = history.GitHub(REPO)
        with patch.object(client, 'get', side_effect=get):
            report = client.collect('e2e.yml', 'main', 'push', 20, [])
        self.assertEqual([1, 2, 3], [r['attempt'] for r in report['attempts']])
        self.assertEqual(0, report['first_attempt']['verified_full_pass'])
        self.assertTrue(report['attempts'][2]['full_pass'])
        self.assertEqual('verified', report['attempts'][0]['evidence_status'])
        self.assertFalse(report['attempts'][0]['application_passed'])
        self.assertEqual(1, report['attempts'][0]['qase_cases']['failed'])
        self.assertEqual('verified', report['attempts'][1]['evidence_status'])
        middle = next(s for s in report['attempts'][1]['suites'] if s['kind'] == 'maestro')
        self.assertEqual('device', middle['category'])
        self.assertEqual(1, middle['missing'])
        self.assertFalse(any('evil.invalid' in c for c in calls))

    def test_jobs_request_failure_keeps_latest_and_all_known_attempts(self):
        for fail_all in (False, True):
            def get(endpoint, binary=False):
                if '/workflows/' in endpoint:
                    return {'workflow_runs': [run_metadata(3)]}
                if '/artifacts?' in endpoint:
                    return {'artifacts': []}
                if '/jobs?' in endpoint:
                    if fail_all or '/attempts/3/' in endpoint:
                        raise history.Invalid('api_deadline')
                    return {'jobs': [], 'total_count': 0}
                return run_metadata(int(endpoint.rsplit('/', 1)[1]), 'failure')
            client = history.GitHub(REPO)
            with patch.object(client, 'get', side_effect=get):
                report = client.collect('e2e.yml', 'main', 'push', 20, [])
            self.assertEqual([1, 2, 3], [r['attempt'] for r in report['attempts']])
            self.assertEqual(1, report['selected_runs'])
            latest = report['attempts'][-1]
            self.assertEqual('api_deadline', latest['error'])
            self.assertEqual('success', latest['workflow_conclusion'], 'retain independent snapshot despite jobs error')
            self.assertFalse(latest['full_pass'])
            if fail_all:
                self.assertTrue(all(r.get('error') == 'api_deadline' for r in report['attempts']))
        report = history.history(REPO, [run_metadata()], {}, [], errors={(42, 3): 'github_request_failed'})
        self.assertEqual([1, 2, 3], [r['attempt'] for r in report['attempts']])
        self.assertEqual('github_request_failed', report['attempts'][-1]['error'])

    def test_api_errors_deadline_child_cleanup_output_limits_and_secret_sanitization(self):
        executable = self.root / 'bin/gh'
        write(executable, '#!/usr/bin/env python3\nimport sys\nsys.stderr.write("TOKEN_TOP_SECRET signed-url")\nsys.exit(1)\n')
        executable.chmod(0o755)
        client = history.GitHub(REPO, seconds=5, call_seconds=1)
        with patch.dict(os.environ, {'PATH': str(executable.parent) + os.pathsep + os.environ['PATH']}):
            with self.assertRaisesRegex(history.Invalid, '^github_request_failed$'):
                client.get(f'repos/{REPO}/actions/runs/42')
            late = self.root / 'escaped-child'
            ready = self.root / 'child-ready'
            for resistant in (False, True):
                ready.unlink(missing_ok=True)
                child = (f'import signal,time; '
                         f'signal.signal(signal.SIGTERM,signal.SIG_IGN if {resistant} else signal.SIG_DFL); '
                         f'open({str(ready)!r},"w").write("ready"); time.sleep(.8); '
                         f'open({str(late)!r},"w").write("escaped")')
                write(executable, '#!/usr/bin/env python3\nimport subprocess,time\n'
                      'subprocess.Popen(["python3","-c",' + repr(child) + '])\n' +
                      ('' if resistant else 'time.sleep(60)\n'))
                process = subprocess.Popen(['gh'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                           start_new_session=True)
                try:
                    startup_deadline = time.monotonic() + 5
                    while not ready.exists() and time.monotonic() < startup_deadline:
                        time.sleep(.01)
                    self.assertTrue(ready.exists(), 'the child must actually be ready before deadline testing')
                    if resistant:
                        self.assertEqual(0, process.wait(timeout=5), 'exercise an already reaped parent')
                    client = history.GitHub(REPO, seconds=5, call_seconds=.2)
                    started = time.monotonic()
                    # Return the real, already-ready owned process; no clock mocking or startup race.
                    with patch.object(history.subprocess, 'Popen', return_value=process):
                        with self.assertRaisesRegex(history.Invalid, '^api_deadline$'):
                            client.get(f'repos/{REPO}/actions/runs/42')
                    self.assertLess(time.monotonic() - started, 2)
                    time.sleep(.9)
                    self.assertFalse(late.exists(), 'deadline must kill descendants even after parent exit')
                finally:
                    history.stop_process(process)
            write(executable, '#!/usr/bin/env python3\nprint("x"*100)\n')
            with patch.object(history, 'MAX_FILE', 20):
                with self.assertRaisesRegex(history.Invalid, '^api_response_too_large$'):
                    history.GitHub(REPO).get(f'repos/{REPO}/actions/runs/42')
        client = history.GitHub(REPO)
        with patch.object(client, 'get', side_effect=history.Invalid('github_request_failed')):
            report = client.collect('e2e.yml', 'main', 'push', 20, [42])
        self.assertEqual(1, report['selected_runs'])
        self.assertEqual('github_request_failed', report['attempts'][0]['error'])
        stdout, stderr = io.StringIO(), io.StringIO()
        with patch.object(history.GitHub, 'collect', side_effect=history.Invalid('github_request_failed')):
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                self.assertEqual(2, history.main([]))
        self.assertNotIn('SECRET', stderr.getvalue())
        self.assertNotIn('signed-url', stderr.getvalue())


if __name__ == '__main__':
    unittest.main()
