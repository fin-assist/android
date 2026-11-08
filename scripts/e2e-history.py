#!/usr/bin/env python3
"""Read-only, bounded history of UI workflow attempts; see docs/ui-test-history.md."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import importlib.util
import io
import json
import lzma
import math
import os
from pathlib import Path
import re
import selectors
import signal
import stat
import subprocess
import sys
import time
from urllib.parse import urlencode
import xml.etree.ElementTree as ET
import zipfile
import zlib

SCHEMA = 1
MAX_FILE = 8 * 1024 * 1024
MAX_BUNDLE = 64 * 1024 * 1024
MAX_ZIP = 32 * 1024 * 1024
MAX_ENTRIES = 256
KINDS = ('maestro', 'native', 'jvm')
PHASES = {'setup', 'device', 'maestro', 'native', 'components', 'reporting', 'complete'}
CATEGORIES = {'setup', 'device', 'test', 'budget', 'reporting', None}
RESULTS = {'running', 'passed', 'failed', 'not_run'}
AVD_CACHE_POLICY = {'disabled': 'disabled', 'hit': 'snapshot', 'miss-or-invalid': 'snapshot'}
CANONICAL = {'maestro-report.xml', 'native-report.xml', 'jvm-report.xml',
             'execution-status.json', 'expected-tests.json', 'qase-cases.json'}
PAYLOAD = CANONICAL | {'qase-delivery.json'}
EVIDENCE = re.compile(r'evidence/(e2e-shard-[1-9][0-9]*|e2e-native|e2e-components)/(execution|preparation|sdk-cache)\.json\Z')
HEX40 = re.compile(r'[a-f0-9]{40}\Z')
HEX64 = re.compile(r'[a-f0-9]{64}\Z')
REPO = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*\Z')


class Invalid(ValueError):
    """Only fixed, non-sensitive codes from this module may cross the CLI boundary."""


def require(condition, code='malformed'):
    if not condition:
        raise Invalid(code)


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def decimal(value):
    return isinstance(value, str) and re.fullmatch(r'[1-9][0-9]{0,19}', value) is not None


def number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def pairs(items):
    result = {}
    for key, value in items:
        require(key not in result, 'duplicate_json_member')
        result[key] = value
    return result


def load_json(data):
    try:
        require(len(data) <= MAX_FILE, 'file_too_large')
        return json.loads(data, object_pairs_hook=pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(Invalid('invalid_number')))
    except (UnicodeError, ValueError, RecursionError) as exc:
        if isinstance(exc, Invalid):
            raise
        raise Invalid('invalid_json') from None


def digest(data):
    return hashlib.sha256(data).hexdigest()


def fingerprint(value):
    return digest(json.dumps(value, sort_keys=True, separators=(',', ':')).encode())


def allowed_path(name):
    return name in PAYLOAD | {'result.json'} or EVIDENCE.fullmatch(name) is not None


def bundle_files(source):
    """Never extract ZIPs. Read only fixed names, regular files and bounded payloads."""
    result = {}
    if isinstance(source, bytes):
        require(len(source) <= MAX_ZIP, 'zip_too_large')
        try:
            with zipfile.ZipFile(io.BytesIO(source)) as archive:
                infos = archive.infolist()
                require(len(infos) <= MAX_ENTRIES, 'too_many_files')
                require(sum(i.file_size for i in infos) <= MAX_BUNDLE, 'bundle_too_large')
                for entry in infos:
                    mode = entry.external_attr >> 16
                    require(allowed_path(entry.filename), 'unsafe_zip_path')
                    require(entry.filename not in result, 'duplicate_zip_entry')
                    require(not entry.flag_bits & 1 and not entry.is_dir() and
                            (not stat.S_IFMT(mode) or stat.S_ISREG(mode)), 'unsafe_zip_entry')
                    require(entry.file_size <= MAX_FILE, 'file_too_large')
                    data = archive.read(entry)
                    require(len(data) == entry.file_size, 'zip_size_mismatch')
                    result[entry.filename] = data
        except (zipfile.BadZipFile, zlib.error, lzma.LZMAError, UnicodeError, RuntimeError, OSError, EOFError, NotImplementedError):
            raise Invalid('invalid_zip') from None
    else:
        root = Path(source)
        try:
            require(not root.is_symlink(), 'unsafe_bundle_path')
            if root.is_file():
                require(root.stat().st_size <= MAX_ZIP, 'zip_too_large')
                return bundle_files(root.read_bytes())
            require(root.is_dir(), 'bundle_unavailable')
            total = 0
            folders = 0
            for folder, dirs, files in os.walk(root, followlinks=False):
                folders += 1
                require(folders <= MAX_ENTRIES, 'too_many_files')
                for name in dirs:
                    require(not (Path(folder) / name).is_symlink(), 'unsafe_bundle_path')
                for name in files:
                    path = Path(folder) / name
                    relative = path.relative_to(root).as_posix()
                    require(allowed_path(relative), 'unsafe_bundle_path')
                    require(not path.is_symlink() and path.is_file(), 'unsafe_bundle_path')
                    require(path.stat().st_size <= MAX_FILE, 'file_too_large')
                    data = path.read_bytes()
                    total += len(data)
                    require(len(data) <= MAX_FILE and total <= MAX_BUNDLE, 'bundle_too_large')
                    result[relative] = data
                    require(len(result) <= MAX_ENTRIES, 'too_many_files')
        except OSError:
            raise Invalid('bundle_unavailable') from None
    require('result.json' in result, 'missing_index')
    return result


def validate_files(files):
    index = load_json(files['result.json'])
    require(isinstance(index, dict) and type(index.get('schema')) is int and index['schema'] == SCHEMA,
            'unknown_schema')
    manifest = index.get('files')
    require(isinstance(manifest, dict) and set(manifest) == set(files) - {'result.json'}, 'file_inventory_mismatch')
    require(CANONICAL <= set(manifest), 'missing_canonical_file')
    for name, meta in manifest.items():
        require(isinstance(meta, dict) and integer(meta.get('bytes')) and
                isinstance(meta.get('sha256'), str) and HEX64.fullmatch(meta['sha256']), 'invalid_hash_record')
        require(len(files[name]) == meta['bytes'] and digest(files[name]) == meta['sha256'], 'hash_mismatch')
    return index


def validate_run(run, repo):
    require(isinstance(run, dict) and integer(run.get('id'), 1) and
            integer(run.get('run_attempt'), 1), 'invalid_run_metadata')
    require(isinstance(run.get('head_sha'), str) and HEX40.fullmatch(run['head_sha']), 'invalid_run_metadata')
    require(run.get('repository', {}).get('full_name') == repo, 'repository_mismatch')
    require(run.get('status') in {'queued', 'requested', 'waiting', 'pending', 'in_progress', 'completed'},
            'invalid_run_state')
    require(run.get('conclusion') in {None, 'success', 'failure', 'cancelled', 'timed_out', 'action_required',
                                      'neutral', 'skipped', 'stale', 'startup_failure'}, 'invalid_run_state')
    jobs = run.get('jobs', [])
    require(isinstance(jobs, list) and len(jobs) <= 200, 'invalid_jobs')
    for job in jobs:
        require(isinstance(job, dict) and job.get('status') in {'queued', 'in_progress', 'completed', 'waiting', 'pending'}
                and job.get('conclusion') in {None, 'success', 'failure', 'cancelled', 'timed_out', 'skipped',
                                              'neutral', 'action_required', 'stale', 'startup_failure'}, 'invalid_jobs')
    return (run['id'], run['run_attempt'])


def validate_identity(identity, meta):
    require(isinstance(identity, dict), 'invalid_receipt_identity')
    require(identity.get('GITHUB_SHA') == meta['source_sha'] and
            identity.get('GITHUB_RUN_ID') == meta['run_id'], 'receipt_source_mismatch')
    attempt = identity.get('GITHUB_RUN_ATTEMPT')
    require(decimal(attempt) and int(attempt) <= int(meta['run_attempt']), 'receipt_attempt_mismatch')


def validate_receipt(receipt, meta, kind):
    require(isinstance(receipt, dict) and type(receipt.get('schema')) is int and receipt['schema'] == 1,
            'unknown_receipt_schema')
    require(receipt.get('phase') in PHASES and receipt.get('category') in CATEGORIES and
            receipt.get('result') in RESULTS and
            (receipt.get('exit_code') is None or type(receipt['exit_code']) is int), 'invalid_receipt_state')
    require(type(receipt.get('budget_expired', False)) is bool, 'invalid_receipt_deadline')
    phase, result, code, category = (receipt.get(k) for k in ('phase', 'result', 'exit_code', 'category'))
    require(not (result == 'passed' and (phase != 'complete' or code != 0 or category is not None)
                 or result == 'failed' and (code is None or code == 0)
                 or result == 'running' and code is not None
                 or receipt.get('budget_expired', False) and result != 'failed'), 'invalid_receipt_state')
    validate_identity(receipt.get('identity'), meta)
    apks = receipt.get('apks', {})
    require(isinstance(apks, dict) and all(isinstance(v, str) and HEX64.fullmatch(v) for v in apks.values()),
            'invalid_apk_identity')
    # Missing APKs before a failed setup are diagnostic, never proof of successful test execution.
    if kind != 'jvm' and receipt['result'] == 'passed':
        require('app-mock-e2e.apk' in apks and
                'app-mock-e2e-androidTest.apk' in apks, 'missing_apk_identity')


def qase_module():
    name = '_e2e_history_qase'
    if name not in sys.modules:
        spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name('qase-report.py'))
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        spec.loader.exec_module(module)
    return sys.modules[name]


def canonical_results(files, expected, cases):
    require(type(expected.get('schema')) is int and expected['schema'] == 1, 'unknown_inventory_schema')
    maestro = expected.get('maestro')
    require(isinstance(maestro, dict) and maestro, 'invalid_inventory')
    groups = {}
    for shard, keys in maestro.items():
        require(decimal(shard), 'invalid_inventory')
        groups[('maestro', int(shard))] = keys
    groups[('native', 0)] = expected.get('native')
    groups[('jvm', 0)] = expected.get('jvm')
    all_keys = []
    for (kind, _), keys in groups.items():
        require(isinstance(keys, list) and len(keys) <= 10000 and
                all(isinstance(k, str) and 0 < len(k) <= 1000 for k in keys), 'invalid_inventory')
        require(all(k.startswith('jvm:') == (kind == 'jvm') for k in keys), 'invalid_inventory')
        all_keys.extend(keys)
    require(len(all_keys) == len(set(all_keys)) and len(all_keys) <= 10000, 'duplicate_inventory')
    require(isinstance(cases, dict) and set(cases) == set(all_keys) and
            all(integer(v, 1) for v in cases.values()), 'mapping_inventory_mismatch')
    module = qase_module()
    results = []
    for kind in KINDS:
        data = files[f'{kind}-report.xml']
        require(b'\x00' not in data and b'<!DOCTYPE' not in data.upper() and b'<!ENTITY' not in data.upper(), 'unsafe_xml')
        try:
            parsed = module.parse_report(data.decode('utf-8'), 'jvm:' if kind == 'jvm' else '')
        except (ET.ParseError, UnicodeError, RecursionError):
            raise Invalid('invalid_xml') from None
        require(len(parsed) <= 10000, 'too_many_tests')
        require({r.key for r in parsed} == {k for (g, _), keys in groups.items() if g == kind for k in keys}
                and len(parsed) == len({r.key for r in parsed}), 'canonical_inventory_mismatch')
        results.extend(parsed)
    mapped, unmapped = module.map_results(results, cases)
    require(not unmapped, 'unmapped_canonical')
    return groups, results, Counter(item['status'] for item in mapped)


def cohort_config(meta, suites):
    """Unknown configuration stays incomparable; APK signing identities are intentionally excluded."""
    required_meta = ('workflow_sha', 'workflow_ref', 'ref', 'event_name')
    if any(not meta.get(k) for k in required_meta) or not HEX40.fullmatch(meta.get('workflow_sha', '')):
        return None
    config = {k: meta[k] for k in required_meta}
    config['suites'] = []
    for suite, receipt, prep, sdk in suites:
        identity = receipt.get('identity', {}) if receipt else {}
        names = ['RUNNER_OS', 'RUNNER_ARCH', 'ImageOS', 'ImageVersion']
        if suite['kind'] == 'maestro':
            names += ['MAESTRO_VERSION', 'MAESTRO_SHA256']
        if (any(not isinstance(identity.get(k), str) or not identity[k] for k in names)
                or not isinstance(receipt.get('java_launcher'), str)
                or receipt['java_launcher'].strip().casefold() in {'', 'unavailable', 'unknown'}
                or not HEX64.fullmatch(receipt.get('gradle_daemon_configuration_sha256', ''))
                or not suite.get('budget_seconds')
                or suite['kind'] == 'maestro' and not HEX64.fullmatch(identity['MAESTRO_SHA256'])):
            return None
        item = {'kind': suite['kind'], 'index': suite['index'], 'identity': {k: identity[k] for k in names},
                'java': receipt['java_launcher'], 'gradle': receipt['gradle_daemon_configuration_sha256'],
                'budget': suite['budget_seconds']}
        if suite['kind'] != 'jvm':
            packages = sdk.get('packages', {}) if sdk else {}
            if (not prep or not prep.get('boot_mode') or not isinstance(prep.get('cache'), str)
                    or prep['cache'] not in AVD_CACHE_POLICY
                    or not isinstance(receipt.get('sdk_packages'), dict)
                    or any(not isinstance(receipt['sdk_packages'].get(k), dict)
                           or not receipt['sdk_packages'][k].get('Pkg.Revision') for k in ('emulator', 'image'))
                    or any(
                    not isinstance(packages.get(k, {}).get('actual'), dict) or
                    not packages[k]['actual'].get('revision') or
                    not HEX64.fullmatch(packages[k]['actual'].get('source_properties_sha256', ''))
                    for k in ('emulator', 'image'))):
                return None
            item.update(boot_mode=prep['boot_mode'], avd_cache=AVD_CACHE_POLICY[prep['cache']],
                        sdk={k: packages[k]['actual'] for k in ('emulator', 'image')},
                        receipt_sdk=receipt.get('sdk_packages'))
        config['suites'].append(item)
    return config


def control_plane(run, workflow, repo):
    """Independent API routing qualifies UI acceptance; artifact claims cannot choose this policy."""
    require(isinstance(workflow, str) and re.fullmatch(r'[A-Za-z0-9_.-]+\.ya?ml', workflow), 'invalid_workflow')
    expected = f'.github/workflows/{workflow}'
    event, path = run.get('event'), run.get('path')
    head_repository = run.get('head_repository')
    head_repo = head_repository.get('full_name') if isinstance(head_repository, dict) else None
    event = event if isinstance(event, str) and len(event) <= 200 else None
    path = path if isinstance(path, str) and len(path) <= 500 else None
    reasons = []
    if head_repo is None:
        reasons.append('unknown_head_repository')
    elif head_repo != repo:
        reasons.append('unexpected_head_repository')
    if event is None:
        reasons.append('unknown_event')
    elif event not in {'push', 'workflow_dispatch'}:
        reasons.append('unsupported_event')
    if path is None:
        reasons.append('unknown_workflow_path')
    elif path != expected:
        reasons.append('unexpected_workflow_path')
    status = 'ineligible' if any(r.startswith(('unsupported_', 'unexpected_')) for r in reasons) else 'unknown' if reasons else 'eligible'
    return {'status': status, 'event': event, 'workflow_path': path,
            'expected_workflow_path': expected, 'head_repository': head_repo if isinstance(head_repo, str) else None,
            'reasons': reasons}


def provider_zip(source, artifact):
    require(isinstance(artifact, dict), 'artifact_metadata_missing')
    provider_digest = artifact.get('digest')
    require(isinstance(provider_digest, str) and re.fullmatch(r'sha256:[a-f0-9]{64}', provider_digest),
            'artifact_digest_unavailable')
    if not isinstance(source, bytes):
        path = Path(source)
        require(path.is_file() and not path.is_symlink(), 'provider_requires_zip')
        require(path.stat().st_size <= MAX_ZIP, 'zip_too_large')
        source = path.read_bytes()
    require(len(source) <= MAX_ZIP, 'zip_too_large')
    require(digest(source) == provider_digest[7:], 'artifact_digest_mismatch')
    return source


def inspect_bundle(source, run, repo, artifact, reconstructed=False, workflow='e2e.yml'):
    if not reconstructed:
        source = provider_zip(source, artifact)
    files = bundle_files(source)
    index = validate_files(files)
    meta = index.get('metadata')
    require(isinstance(meta, dict), 'invalid_metadata')
    for name, value in meta.items():
        if name != 'job_results':
            require(value is None or isinstance(value, str), 'invalid_metadata')
    require(meta.get('repository') == repo and meta.get('server_url') == 'https://github.com', 'repository_mismatch')
    require(meta.get('source_sha') == run['head_sha'], 'source_mismatch')
    require(meta.get('run_id') == str(run['id']) and meta.get('run_attempt') == str(run['run_attempt']), 'attempt_mismatch')
    for canonical, api in [('event_name', 'event'), ('ref_name', 'head_branch'), ('workflow', 'name')]:
        if meta.get(canonical) is not None and run.get(api) is not None:
            require(meta[canonical] == run[api], 'run_metadata_mismatch')
    if not reconstructed:
        require(isinstance(artifact, dict) and artifact.get('name') == f"e2e-result-attempt-{run['run_attempt']}", 'artifact_metadata_missing')
        provenance = artifact.get('workflow_run', {})
        require(integer(provenance.get('id'), 1) and provenance.get('id') == run['id'] and provenance.get('head_sha') == run['head_sha'], 'artifact_source_mismatch')
        require(artifact.get('expired') is False, 'artifact_expired')
    job_results = meta.get('job_results')
    require(isinstance(job_results, dict) and set(job_results) == {'shard', 'native', 'components'} and
            all(v in {'success', 'failure', 'cancelled', 'skipped', None} for v in job_results.values()), 'invalid_job_results')
    expected = load_json(files['expected-tests.json'])
    case_config = load_json(files['qase-cases.json'])
    require(isinstance(case_config, dict) and isinstance(case_config.get('cases'), dict), 'invalid_mapping')
    project = case_config.get('project')
    require(isinstance(project, str) and re.fullmatch(r'[A-Z][A-Z0-9_]{0,30}', project), 'invalid_mapping')
    cases = case_config['cases']
    groups, results, mapped = canonical_results(files, expected, cases)
    status = load_json(files['execution-status.json'])
    require(isinstance(status, dict) and type(status.get('schema')) is int and status['schema'] == 1
            and type(status.get('passed')) is bool and isinstance(status.get('problems'), list) and
            len(status['problems']) <= 1000 and all(isinstance(p, str) and len(p) <= 2000 for p in status['problems']), 'invalid_canonical_status')
    outcomes = status.get('suites')
    require(isinstance(outcomes, list) and len(outcomes) == len(groups), 'invalid_suite_inventory')
    by_suite = {}
    for outcome in outcomes:
        require(isinstance(outcome, dict), 'invalid_suite_status')
        key = (outcome.get('kind'), outcome.get('index'))
        require(key in groups and key not in by_suite and integer(key[1]), 'invalid_suite_inventory')
        counts = [outcome.get(k) for k in ('passed', 'failed', 'missing', 'skipped')]
        require(all(integer(n) for n in counts) and integer(outcome.get('planned')) and outcome.get('planned') == len(groups[key]) and
                sum(counts) == outcome['planned'] and isinstance(outcome.get('invalid'), list), 'invalid_suite_counts')
        require(integer(outcome.get('unverified', 0)) and outcome.get('unverified', 0) <= outcome['skipped'], 'invalid_suite_counts')
        require(outcome.get('phase') in PHASES | {None} and outcome.get('category') in CATEGORIES and
                outcome.get('result') in RESULTS | {None} and type(outcome.get('budget_expired', False)) is bool,
                'invalid_suite_status')
        observed = Counter(r.status for r in results if r.key in groups[key])
        require(observed['passed'] == outcome['passed'] and observed['failed'] == outcome['failed'] and
                observed['skipped'] == outcome['skipped'] + outcome['missing'], 'canonical_count_mismatch')
        by_suite[key] = outcome
    # The strict gather boolean is authoritative; verify no counter/XML contradiction can invent a pass.
    if status['passed']:
        require(not status['problems'] and all(r.status == 'passed' for r in results) and
                all(s['passed'] == s['planned'] and not s['invalid'] and s['result'] == 'passed' and
                    not s.get('budget_expired') for s in outcomes), 'contradictory_canonical_status')
    suite_index = index.get('suites')
    require(isinstance(suite_index, list) and len(suite_index) == len(groups), 'invalid_suite_inventory')
    seen, projections, details = set(), [], []
    for suite in suite_index:
        require(isinstance(suite, dict), 'invalid_suite_inventory')
        key = (suite.get('kind'), suite.get('index'))
        require(key in groups and key not in seen and integer(key[1]), 'invalid_suite_inventory')
        seen.add(key)
        budget = suite.get('budget_seconds')
        require(budget is None or integer(budget, 1), 'invalid_budget')
        folder = f'e2e-shard-{key[1]}' if key[0] == 'maestro' else 'e2e-native' if key[0] == 'native' else 'e2e-components'
        evidence = suite.get('evidence')
        require(isinstance(evidence, dict) and set(evidence) == {'execution', 'preparation', 'sdk_cache'}, 'invalid_evidence')
        originals = {}
        for kind, filename in [('execution', 'execution.json'), ('preparation', 'preparation.json'), ('sdk_cache', 'sdk-cache.json')]:
            path = evidence[kind]
            require(path is None or (path == f'evidence/{folder}/{filename}' and path in files), 'invalid_evidence_path')
            originals[kind] = load_json(files[path]) if path else None
        receipt, prep, sdk = (originals[k] for k in ('execution', 'preparation', 'sdk_cache'))
        outcome = by_suite[key]
        if receipt is not None:
            validate_receipt(receipt, meta, key[0])
        elif status['passed'] or outcome['passed'] or outcome['failed']:
            raise Invalid('missing_receipt')
        if receipt is not None:
            for field in ('phase', 'result', 'exit_code'):
                require(receipt.get(field) == outcome.get(field), 'receipt_status_mismatch')
            require(receipt.get('budget_expired', False) == outcome.get('budget_expired', False), 'receipt_status_mismatch')
        process = None
        if prep is not None:
            require(isinstance(prep, dict) and isinstance(prep.get('marks'), dict), 'invalid_preparation')
            start, finish = (prep['marks'].get(k) for k in ('tests-started', 'tests-finished'))
            if start is not None and finish is not None:
                require(number(start) and number(finish) and finish >= start, 'invalid_process_duration')
                process = round(finish - start, 3)
        cache = {'maestro_archive': 'unknown', 'emulator': 'unknown', 'image': 'unknown',
                 'avd': prep.get('cache') if prep and isinstance(prep.get('cache'), str) and len(prep['cache']) <= 100 else 'unknown',
                 'avd_policy': AVD_CACHE_POLICY.get(prep.get('cache'), 'unknown') if prep and isinstance(prep.get('cache'), str) else 'unknown'}
        if sdk is not None:
            require(isinstance(sdk, dict) and isinstance(sdk.get('packages'), dict), 'invalid_sdk_cache')
            for package in ('emulator', 'image'):
                classification = sdk['packages'].get(package, {}).get('classification')
                if classification in {'hit-current', 'hit-updated', 'miss', 'preinstalled'}:
                    cache[package] = classification
        details.append({k: outcome.get(k) for k in ('kind', 'index', 'planned', 'passed', 'failed', 'missing', 'skipped', 'unverified', 'phase', 'category', 'result', 'budget_expired', 'invalid')} |
                       {'budget_seconds': budget, 'process_seconds': process,
                        'remaining_budget_seconds': round(budget - process, 3) if budget is not None and process is not None else None,
                        'cache': cache, 'apks': receipt.get('apks', {}) if receipt else {}, 'evidence': evidence})
        projections.append((suite, receipt or {}, prep, sdk))
    delivery = index.get('delivery')
    require(isinstance(delivery, dict) and delivery.get('reason') in {'recorded', 'manual_not_published', 'not_recorded'}, 'invalid_delivery')
    require(delivery['reason'] != 'manual_not_published' or meta.get('event_name') == 'workflow_dispatch', 'invalid_delivery')
    qase_delivery = delivery['reason']
    qase_run_id = None
    if delivery.get('path') is not None:
        require(delivery['path'] == 'qase-delivery.json' and 'qase-delivery.json' in files and
                delivery['reason'] == 'recorded', 'invalid_delivery')
        published = load_json(files['qase-delivery.json'])
        require(type(published.get('schema')) is int and published['schema'] == 1 and published.get('result') in {'pending', 'empty', 'dry_run', 'not_configured', 'failed', 'published'}, 'invalid_delivery')
        require(integer(published.get('case_count')) and published['case_count'] == len(set(cases.values())) and
                integer(published.get('unmapped_count')) and published['unmapped_count'] == 0, 'invalid_delivery_counts')
        if published['result'] == 'published':
            require(integer(published.get('run_id'), 1), 'invalid_delivery_run')
            qase_run_id = published['run_id']
        qase_delivery = published['result']
    else:
        require(delivery['reason'] != 'recorded' and 'qase-delivery.json' not in files, 'invalid_delivery')
    inventory = fingerprint({'expected': expected, 'project': project, 'cases': cases})
    config = cohort_config(meta, sorted(projections, key=lambda s: (s[0]['kind'], s[0]['index'])))
    plane = control_plane(run, workflow, repo)
    cohort = fingerprint({'source_sha': meta['source_sha'], 'inventory': inventory, 'config': config}) if config and plane['status'] == 'eligible' and not reconstructed else None
    jobs_ok = all(v == 'success' for v in job_results.values()) and all(
        j.get('conclusion') in {'success', 'skipped', 'neutral'} for j in run.get('jobs', []))
    workflow_ok = run['status'] == 'completed' and run['conclusion'] == 'success' and jobs_ok
    return {'artifact_provenance': 'local_reconstruction' if reconstructed else 'github_artifact',
            'evidence_status': 'reconstructed' if reconstructed else 'verified', 'problems': status['problems'], 'application_passed': status['passed'],
            'full_pass': status['passed'] and workflow_ok and plane['status'] == 'eligible' and not reconstructed,
            'control_plane': plane, 'inventory': inventory,
            'components': dict(Counter(r.status for r in results)) | {'expected': len(results)},
            'qase_cases': dict(mapped) | {'expected': len(set(cases.values()))}, 'qase_delivery': qase_delivery, 'qase_run_id': qase_run_id,
            'qase_run_url': f'https://app.qase.io/run/{project}/dashboard/{qase_run_id}' if qase_run_id else None,
            'cohort': cohort, 'cohort_status': 'reconstructed' if reconstructed else plane['status'] + '_control_plane' if plane['status'] != 'eligible' else 'comparable' if cohort else 'incomplete_configuration',
            'event': meta.get('event_name'), 'ref': meta.get('ref'), 'schema': index['schema'],
            'job_results': job_results,
            'completeness': {key: sum(s.get(key, 0) for s in outcomes) for key in ('planned', 'passed', 'failed', 'missing', 'skipped', 'unverified')} |
                            {'integrity_errors': sum(len(s['invalid']) for s in outcomes)},
            'suites': sorted(details, key=lambda s: (s['kind'], s['index']))}


def attempt_row(run_id, attempt, run, sources, artifacts, repo, error=None, reconstructed=False, workflow='e2e.yml'):
    row = {'run_id': run_id, 'attempt': attempt, 'source_sha': run.get('head_sha') if run else None,
           'workflow_status': run.get('status') if run else 'unavailable',
           'workflow_conclusion': run.get('conclusion') if run else None,
           'url': f'https://github.com/{repo}/actions/runs/{run_id}/attempts/{attempt}',
           'evidence_status': 'legacy_or_missing', 'full_pass': False, 'cohort': None,
           'cohort_status': 'incomparable', 'qase_delivery': 'unknown',
           'control_plane': control_plane(run or {}, workflow, repo)}
    try:
        require(error is None, error or 'unavailable')
        require(run is not None, 'missing_attempt_metadata')
        if not sources:
            return row
        require(len({digest(json.dumps(validate_files(bundle_files(s)), sort_keys=True).encode()) for s in sources}) == 1,
                'conflicting_attempt_input')
        use_reconstruction = reconstructed and not artifacts
        require(use_reconstruction or len(artifacts) == 1, 'ambiguous_artifact_metadata')
        if not use_reconstruction:
            sources = [provider_zip(source, artifacts[0]) for source in sources]
        row.update(inspect_bundle(sources[0], run, repo, artifacts[0] if artifacts else None, use_reconstruction, workflow))
    except (Invalid, TypeError, KeyError, AttributeError, OverflowError) as exc:
        row.update(evidence_status='unverified' if isinstance(exc, Invalid) and str(exc) == 'artifact_digest_unavailable' else 'invalid',
                   error=str(exc) if isinstance(exc, Invalid) else 'malformed', full_pass=False,
                   cohort=None, cohort_status='incomparable')
    return row


def history(repo, runs, bundles, artifacts, selected=None, errors=None, reconstructed=False, workflow='e2e.yml'):
    require(REPO.fullmatch(repo), 'invalid_repository')
    by_key, conflicts = {}, set()
    for run in runs:
        key = validate_run(run, repo)
        if key in by_key and by_key[key] != run:
            conflicts.add(key)
        by_key[key] = run
    known_keys = set(by_key) | set(bundles) | set(errors or {})
    selected = sorted(set(selected if selected is not None else (k[0] for k in known_keys)))
    require(len(selected) <= 100, 'too_many_runs')
    rows = []
    for run_id in selected:
        known = [attempt for rid, attempt in known_keys if rid == run_id]
        latest = max(known, default=1)
        require(latest <= 100, 'too_many_attempts')
        attempts = sorted(set(range(1, latest + 1)) | {a for rid, a in bundles if rid == run_id})
        require(len(attempts) <= 100, 'too_many_attempts')
        for attempt in attempts:
            key = (run_id, attempt)
            matching = [a for a in artifacts if a.get('name') == f'e2e-result-attempt-{attempt}'
                        and a.get('workflow_run', {}).get('id') == run_id]
            rows.append(attempt_row(run_id, attempt, by_key.get(key), bundles.get(key, []), matching, repo,
                                    'conflicting_run_metadata' if key in conflicts else (errors or {}).get(key), reconstructed, workflow))
    first = [r for r in rows if r['attempt'] == 1]
    states = Counter(r['workflow_conclusion'] or r['workflow_status'] for r in first)
    cohorts = {}
    for row in rows:
        if row.get('cohort'):
            cohorts.setdefault(row['cohort'], []).append({'run_id': row['run_id'], 'attempt': row['attempt'], 'full_pass': row['full_pass']})
    return {'schema': SCHEMA, 'repository': repo, 'selected_runs': len(selected),
            'first_attempt': {'workflow_states': dict(sorted(states.items())),
                              'verified_full_pass': sum(r['full_pass'] and r['evidence_status'] == 'verified' for r in first),
                              'reconstructed_application_pass': sum(r.get('application_passed', False) and r['evidence_status'] == 'reconstructed' for r in first),
                              'evidence_states': dict(sorted(Counter(r['evidence_status'] for r in first).items()))},
            'attempts': rows, 'cohorts': dict(sorted(cohorts.items()))}


def stop_process(process):
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=0.1)
    except subprocess.TimeoutExpired:
        pass
    finally:
        # A reaped parent says nothing about surviving descendants in its process group.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()


class GitHub:
    """GET-only gh boundary. Never print subprocess stderr, bodies, URLs or credentials on error."""
    def __init__(self, repo, seconds=180, call_seconds=20):
        require(REPO.fullmatch(repo), 'invalid_repository')
        self.repo = repo
        self.deadline = time.monotonic() + seconds
        self.call_seconds = call_seconds
        self.requests = 0

    def get(self, endpoint, binary=False):
        self.requests += 1
        require(self.requests <= 450, 'api_request_limit')
        remaining = min(self.call_seconds, self.deadline - time.monotonic())
        require(remaining > 0, 'api_deadline')
        require(endpoint.startswith(f'repos/{self.repo}/actions/') and '\n' not in endpoint, 'invalid_endpoint')
        try:
            process = subprocess.Popen(['gh', 'api', '--hostname', 'github.com', '--method', 'GET', endpoint],
                                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)
        except OSError:
            raise Invalid('github_unavailable') from None
        output = bytearray()
        deadline = time.monotonic() + remaining
        selector = selectors.DefaultSelector()
        selector.register(process.stdout, selectors.EVENT_READ)
        try:
            while selector.get_map():
                require(time.monotonic() < deadline, 'api_deadline')
                for key, _ in selector.select(min(0.1, max(0, deadline - time.monotonic()))):
                    data = os.read(key.fileobj.fileno(), 65536)
                    if not data:
                        selector.unregister(key.fileobj)
                    else:
                        output.extend(data)
                        require(len(output) <= (MAX_ZIP if binary else MAX_FILE), 'api_response_too_large')
            try:
                code = process.wait(timeout=max(0.001, deadline - time.monotonic()))
            except subprocess.TimeoutExpired:
                raise Invalid('api_deadline') from None
            require(code == 0, 'github_request_failed')
        finally:
            selector.close()
            process.stdout.close()
            # Children may keep a pipe alive even after gh exits. The group is ours in all cases.
            stop_process(process)
        return bytes(output) if binary else load_json(bytes(output))

    def collect(self, workflow, branch, event, limit, selected):
        require(re.fullmatch(r'[A-Za-z0-9_.-]+\.ya?ml', workflow), 'invalid_workflow')
        runs, artifacts, bundles, errors = [], [], {}, {}
        if not selected:
            query = urlencode({'per_page': limit, 'branch': branch, 'event': event})
            answer = self.get(f'repos/{self.repo}/actions/workflows/{workflow}/runs?{query}')
            require(isinstance(answer, dict) and isinstance(answer.get('workflow_runs'), list), 'invalid_api_runs')
            initial = answer['workflow_runs'][:limit]
            for run in initial:
                validate_run(run, self.repo)
            selected = sorted({r['id'] for r in initial})
        else:
            initial = []
            for run_id in sorted(set(selected)):
                try:
                    initial.append(self.get(f'repos/{self.repo}/actions/runs/{run_id}'))
                except Invalid as exc:
                    errors[(run_id, 1)] = str(exc)
        for latest in initial:
            validate_run(latest, self.repo)
            run_id = latest['id']
            require(latest['run_attempt'] <= 100, 'too_many_attempts')
            attempts = range(1, latest['run_attempt'] + 1)
            try:
                # Cap artifact pages rather than silently dropping a red run on API truncation.
                all_artifacts = []
                for page in range(1, 5):
                    answer = self.get(f'repos/{self.repo}/actions/runs/{run_id}/artifacts?per_page=100&page={page}')
                    require(isinstance(answer, dict) and isinstance(answer.get('artifacts'), list), 'invalid_api_artifacts')
                    all_artifacts.extend(answer['artifacts'])
                    if len(answer['artifacts']) < 100:
                        break
                else:
                    raise Invalid('artifact_page_limit')
                artifacts.extend(all_artifacts)
            except Invalid as exc:
                all_artifacts = []
                for attempt in attempts:
                    errors[(run_id, attempt)] = str(exc)
            for attempt in attempts:
                key = (run_id, attempt)
                try:
                    run = latest if attempt == latest['run_attempt'] else self.get(f'repos/{self.repo}/actions/runs/{run_id}/attempts/{attempt}')
                    require(validate_run(run, self.repo) == key, 'attempt_metadata_mismatch')
                    runs.append(run)  # Keep the API attempt even when its subsequent jobs request fails.
                    # Independent job conclusions veto a canonical pass; never infer tests from API success.
                    jobs = self.get(f'repos/{self.repo}/actions/runs/{run_id}/attempts/{attempt}/jobs?per_page=100')
                    require(isinstance(jobs, dict) and isinstance(jobs.get('jobs'), list) and
                            integer(jobs.get('total_count')) and jobs['total_count'] <= 100, 'invalid_api_jobs')
                    run = dict(run, jobs=jobs['jobs'])
                    runs[-1] = run
                    matching = [a for a in all_artifacts if a.get('name') == f'e2e-result-attempt-{attempt}']
                    if matching:
                        require(len(matching) == 1, 'ambiguous_artifact_metadata')
                        artifact = matching[0]
                        require(integer(artifact.get('id'), 1) and artifact.get('expired') is False, 'artifact_expired')
                        require(artifact.get('workflow_run', {}).get('id') == run_id and
                                artifact.get('workflow_run', {}).get('head_sha') == run['head_sha'], 'artifact_source_mismatch')
                        bundles[key] = [self.get(f"repos/{self.repo}/actions/artifacts/{artifact['id']}/zip", binary=True)]
                except Invalid as exc:
                    errors[key] = str(exc)
        return history(self.repo, runs, bundles, artifacts, selected, errors, workflow=workflow)


def escape(value):
    return str(value).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;').replace('|', '&#124;').replace('[', '&#91;').replace(']', '&#93;').replace('`', '&#96;').replace('\n', ' ').replace('\r', ' ')


def markdown(report):
    first = report['first_attempt']
    lines = [f"UI history · {escape(report['repository'])}", '',
             f"Selected runs: {report['selected_runs']}. Provider-verified complete first-attempt passes: {first['verified_full_pass']}/{report['selected_runs']}.",
             f"Locally reconstructed first attempts with passing canonical observations: {first['reconstructed_application_pass']} (not provider-verified acceptance).",
             'First-attempt workflow states: ' + escape(json.dumps(first['workflow_states'], sort_keys=True)) + '.',
             'Operational denominator includes unavailable and cancelled runs. This is not a flake-rate estimate.', '',
             '| Run / attempt | Workflow | Evidence / reported application | Components / Qase cases | Delivery | Cohort |',
             '|---|---|---|---|---|---|']
    details = []
    for row in report['attempts']:
        counts = row.get('components', {})
        cases = row.get('qase_cases', {})
        application = 'PASS' if row.get('application_passed') else 'NOT PASS' if 'application_passed' in row else 'unknown'
        lines.append(f"| [{row['run_id']} / {row['attempt']}]({row['url']}) | {escape(row['workflow_conclusion'] or row['workflow_status'])} | {escape(row['evidence_status'])}: {application}; control={escape(row['control_plane']['status'])}; complete={row['full_pass']} {escape(row.get('error', ''))} | {counts.get('passed', 0)}/{counts.get('expected', '?')} / {cases.get('passed', 0)}/{cases.get('expected', '?')} | {escape(row['qase_delivery'])} | {escape(row['cohort_status'])} {row.get('cohort', '')[:12] if row.get('cohort') else ''} |")
        if row.get('qase_run_url'):
            details.append(f"\nQase: [{row['qase_run_id']}]({row['qase_run_url']}).")
        details.append(f"\n{row['run_id']}/{row['attempt']}: Qase run={row.get('qase_run_id')}; control={escape(json.dumps(row['control_plane'], sort_keys=True))}; problems={escape(json.dumps(row.get('problems', [])))}; source={row.get('source_sha')}; artifact={row.get('artifact_provenance', 'unknown')}; inventory={row.get('inventory', 'unknown')}.")
        for suite in row.get('suites', []):
            details.append(f"\n- {row['run_id']}/{row['attempt']} {suite['kind']} {suite['index']}: cause={escape(suite['category'])}, phase={escape(suite['phase'])}, deadline={suite.get('budget_expired', False)}; failed={suite['failed']}, missing={suite['missing']}, skipped={suite['skipped']}; process={suite['process_seconds']}s, budget={suite['budget_seconds']}s, remaining={suite['remaining_budget_seconds']}s; cache={escape(json.dumps(suite['cache'], sort_keys=True))}; diagnostics={escape(json.dumps(suite['invalid']))}.")
    lines += details
    lines += ['', f"Comparable cohorts: {len(report['cohorts'])}. Exact source, inventory and known configuration only; unknown configuration is incomparable.",
              'Process duration uses recorded start/finish marks, never summed test durations. Unknown values remain unknown. No p95 or SLO inference.']
    for cohort, members in report['cohorts'].items():
        lines.append(f"- {cohort[:12]}: " + ', '.join(f"{m['run_id']}/{m['attempt']} complete={m['full_pass']}" for m in members))
    return '\n'.join(lines) + '\n'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', default='fin-assist/android')
    parser.add_argument('--metadata', type=Path, help='offline independent GitHub API envelope (see docs)')
    parser.add_argument('--local-reconstruction', action='store_true', help='offline-only: explicitly label a locally reconstructed bundle, not a published artifact')
    parser.add_argument('--bundle', action='append', default=[], metavar='RUN:ATTEMPT=PATH')
    parser.add_argument('--run', action='append', type=int, default=[])
    parser.add_argument('--workflow', default='e2e.yml')
    parser.add_argument('--branch', default='main')
    parser.add_argument('--event', default='push')
    parser.add_argument('--limit', type=int, default=20)
    parser.add_argument('--api-budget', type=int, default=180)
    parser.add_argument('--format', choices=['markdown', 'json'], default='markdown')
    args = parser.parse_args(argv)
    try:
        require(REPO.fullmatch(args.repo), 'invalid_repository')
        require(1 <= args.limit <= 100 and 1 <= args.api_budget <= 300 and len(set(args.run)) <= 100 and
                all(integer(n, 1) for n in args.run) and len(args.bundle) <= 300, 'invalid_selection')
        if args.metadata:
            require(args.metadata.stat().st_size <= MAX_FILE, 'file_too_large')
            metadata = load_json(args.metadata.read_bytes())
            require(isinstance(metadata, dict) and metadata.get('repository') == args.repo and
                    isinstance(metadata.get('runs'), list) and isinstance(metadata.get('artifacts'), list), 'invalid_metadata_envelope')
            require(len(metadata['runs']) <= 1000 and len(metadata['artifacts']) <= 10000, 'metadata_too_large')
            bundles = {}
            for value in args.bundle:
                match = re.fullmatch(r'([1-9][0-9]{0,19}):([1-9][0-9]{0,5})=(.+)', value)
                require(match is not None, 'invalid_bundle_argument')
                key = (int(match[1]), int(match[2]))
                bundles.setdefault(key, []).append(Path(match[3]))
            report = history(args.repo, metadata['runs'], bundles, metadata['artifacts'], args.run or None, reconstructed=args.local_reconstruction, workflow=args.workflow)
        else:
            require(not args.bundle and not args.local_reconstruction, 'offline_metadata_required')
            report = GitHub(args.repo, args.api_budget).collect(args.workflow, args.branch, args.event, args.limit, args.run)
        print(json.dumps(report, indent=2, sort_keys=True) if args.format == 'json' else markdown(report), end='\n' if args.format == 'json' else '')
        return 0
    except (Invalid, OSError, TypeError, KeyError, AttributeError, RecursionError, OverflowError) as exc:
        print('UI history unavailable: ' + (str(exc) if isinstance(exc, Invalid) else 'invalid_input') + '.', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
