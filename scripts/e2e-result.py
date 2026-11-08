#!/usr/bin/env python3
"""Package the existing gather result and its provenance without recomputing its verdict (FIN-474)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
CANONICAL = ('maestro-report.xml', 'native-report.xml', 'jvm-report.xml',
             'execution-status.json', 'expected-tests.json')
METADATA_ENV = {
    'repository': 'GITHUB_REPOSITORY', 'server_url': 'GITHUB_SERVER_URL', 'source_sha': 'GITHUB_SHA',
    'run_id': 'GITHUB_RUN_ID', 'run_attempt': 'GITHUB_RUN_ATTEMPT', 'event_name': 'GITHUB_EVENT_NAME',
    'ref': 'GITHUB_REF', 'ref_name': 'GITHUB_REF_NAME', 'workflow': 'GITHUB_WORKFLOW',
    'workflow_ref': 'GITHUB_WORKFLOW_REF', 'workflow_sha': 'GITHUB_WORKFLOW_SHA',
}
JOB_ENV = {'shard': 'E2E_SHARD_RESULT', 'native': 'E2E_NATIVE_RESULT', 'components': 'E2E_COMPONENT_RESULT'}
EVIDENCE = ('execution.json', 'preparation.json', 'sdk-cache.json')


def metadata(environment, supplied=None):
    """Only allowlisted CI identity; missing optional facts remain unknown, never inferred from a checkout."""
    data = {key: environment.get(name) for key, name in METADATA_ENV.items()} if supplied is None else {
        key: supplied.get(key) for key in METADATA_ENV}
    jobs = {key: environment.get(name) for key, name in JOB_ENV.items()} if supplied is None else supplied.get('job_results', {})
    data['job_results'] = {key: jobs.get(key) for key in JOB_ENV}
    if any(value is not None and not isinstance(value, str) for key, value in data.items() if key != 'job_results'):
        raise ValueError('metadata values must be strings or null')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', data['repository'] or ''):
        raise ValueError('repository identity is required')
    server = urlsplit(data['server_url'] or '')
    if (server.scheme != 'https' or not server.netloc or server.username or server.password
            or server.path not in ('', '/') or server.query or server.fragment):
        raise ValueError('server_url must identify an HTTPS GitHub server')
    data['server_url'] = data['server_url'].rstrip('/')
    if not re.fullmatch(r'[0-9a-f]{40}', data['source_sha'] or ''):
        raise ValueError('full source SHA is required')
    for key in ('run_id', 'run_attempt'):
        if not re.fullmatch(r'[1-9][0-9]*', data[key] or ''):
            raise ValueError(key + ' must be a positive decimal string')
    if any(value not in (None, 'success', 'failure', 'cancelled', 'skipped') for value in data['job_results'].values()):
        raise ValueError('invalid upstream job result')
    return data


def clear_output(out, sources):
    if any(source.resolve().is_relative_to(out.resolve()) for source in sources):
        raise ValueError('output must not contain an input directory')
    if out.is_symlink() or any(source.is_symlink() for source in sources):
        raise ValueError('input and output directories must not be symlinks')
    # A typo in --out must not recursively delete an unrelated directory. Only replace our own complete bundle.
    if out.is_dir():
        existing = list(out.rglob('*'))
        if existing:
            index = out / 'result.json'
            try:
                old = json.loads(index.read_text(encoding='utf-8'))
                names = set(old['files'])
                allowed = lambda name: name in (*CANONICAL, 'qase-cases.json', 'qase-delivery.json') or re.fullmatch(
                    r'evidence/(?:e2e-shard-[1-9][0-9]*|e2e-native|e2e-components)/(?:execution|preparation|sdk-cache)\.json', name)
                owned = (type(old.get('schema')) is int and old['schema'] == 1
                         and set((*CANONICAL, 'qase-cases.json')) <= names and all(allowed(name) for name in names)
                         and not any(path.is_symlink() for path in existing)
                         and {path.relative_to(out).as_posix() for path in existing if path.is_file()} == names | {'result.json'})
            except (OSError, ValueError, KeyError, TypeError, AttributeError):
                owned = False
            if not owned:
                raise ValueError('refusing to replace an unrelated or unrecognized output directory')
        # An unsuccessful new attempt must never expose an earlier PASS bundle to upload-artifact.
        shutil.rmtree(out)
    elif out.exists():
        raise ValueError('output must be an empty directory or a previous compact result')
    out.parent.mkdir(parents=True, exist_ok=True)


def package(root, canonical, shards_dir, native_dir, jvm_dir, out, context, budgets):
    """Copy fixed files and index them. Missing canonical inputs fail closed, while failed gathers are preserved."""
    clear_output(out, [canonical, shards_dir, native_dir, jvm_dir, root / 'scripts'])
    context = metadata({}, context)
    status = json.loads((canonical / 'execution-status.json').read_text(encoding='utf-8'))
    if (type(status.get('schema')) is not int or status['schema'] != 1
            or type(status.get('passed')) is not bool or not isinstance(status.get('suites'), list)):
        raise ValueError('a schema1 gather status is required')
    files = {}
    run = f"{context['server_url']}/{context['repository']}/actions/runs/{context['run_id']}"
    artifact_url = f"{run}/attempts/{context['run_attempt']}#artifacts"
    with tempfile.TemporaryDirectory(prefix='e2e-result-', dir=out.parent) as temp:
        staging = Path(temp) / 'result'
        staging.mkdir()

        def copy(source, relative):
            if source.is_symlink() or not source.is_file():
                raise ValueError('missing regular input: ' + str(source))
            destination = staging / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, destination)
            with destination.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            files[relative] = {'sha256': digest, 'bytes': destination.stat().st_size}

        for name in CANONICAL:
            copy(canonical / name, name)
        copy(root / 'scripts/qase-cases.json', 'qase-cases.json')
        suites = []
        for outcome in status['suites']:
            kind, index = outcome['kind'], outcome['index']
            if type(index) is not int or kind not in ('maestro', 'native', 'jvm') or (index < 1 if kind == 'maestro' else index != 0):
                raise ValueError('invalid gather suite identity')
            name = f'e2e-shard-{index}' if kind == 'maestro' else 'e2e-native' if kind == 'native' else 'e2e-components'
            directory = shards_dir / name if kind == 'maestro' else native_dir if kind == 'native' else jvm_dir
            if directory.is_symlink():
                raise ValueError('suite evidence directory must not be a symlink')
            evidence = {}
            for filename, key in zip(EVIDENCE, ('execution', 'preparation', 'sdk_cache')):
                relative = f'evidence/{name}/{filename}'
                source = directory / filename
                evidence[key] = relative if source.exists() else None
                if source.exists():
                    copy(source, relative)
            suites.append({'kind': kind, 'index': index, 'budget_seconds': budgets.get(kind),
                           'maestro_archive_cache': None, 'evidence': evidence,
                           'artifact': {'name': name, 'url': artifact_url}})
        delivery = canonical / 'qase-delivery.json'
        if delivery.exists():
            copy(delivery, 'qase-delivery.json')
        reason = 'recorded' if delivery.exists() else 'manual_not_published' if context['event_name'] == 'workflow_dispatch' else 'not_recorded'
        result = {'schema': 1, 'metadata': context, 'files': files, 'suites': suites,
                  'delivery': {'path': 'qase-delivery.json' if delivery.exists() else None, 'reason': reason},
                  'raw_reports': {'name': 'e2e-reports', 'url': artifact_url}}
        (staging / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
        staging.replace(out)
    return result


def positive_seconds(value):
    try:
        seconds = int(value)
    except ValueError:
        raise argparse.ArgumentTypeError('process budget must be positive seconds') from None
    if seconds <= 0:
        raise argparse.ArgumentTypeError('process budget must be positive seconds')
    return seconds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--canonical', type=Path, default=Path('build/e2e'))
    parser.add_argument('--shards-dir', type=Path, default=Path('build/e2e-shards'))
    parser.add_argument('--native-dir', type=Path, default=Path('build/e2e-native'))
    parser.add_argument('--jvm-dir', type=Path, default=Path('build/e2e-components'))
    parser.add_argument('--out', type=Path, default=Path('build/e2e-result'))
    parser.add_argument('--metadata', type=Path, help='explicit schema1 metadata object for offline packaging')
    for kind in ('maestro', 'native', 'jvm'):
        parser.add_argument(f'--{kind}-budget-seconds')
    args = parser.parse_args()
    # Clear stale output before parsing metadata too: a failed invocation must not upload an earlier PASS.
    clear_output(args.out, [args.canonical, args.shards_dir, args.native_dir, args.jvm_dir, args.root / 'scripts'])
    budgets = {}
    for kind in ('maestro', 'native', 'jvm'):
        value = getattr(args, kind + '_budget_seconds')
        try:
            budgets[kind] = positive_seconds(value) if value is not None else None
        except argparse.ArgumentTypeError as error:
            parser.error(str(error))
    supplied = json.loads(args.metadata.read_text(encoding='utf-8')) if args.metadata else None
    package(args.root, args.canonical, args.shards_dir, args.native_dir, args.jvm_dir, args.out,
            metadata(os.environ, supplied), budgets)


if __name__ == '__main__':
    main()
