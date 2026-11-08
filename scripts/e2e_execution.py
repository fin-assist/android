#!/usr/bin/env python3
"""Small, allowlisted execution receipt, including setup failures before any UI report exists (FIN-473)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

STATE = Path('build/e2e/execution.json')
PHASES = ('setup', 'device', 'maestro', 'native', 'components', 'reporting', 'complete')
IDENTITY_ENV = ('GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'RUNNER_OS', 'RUNNER_ARCH',
                'ImageOS', 'ImageVersion', 'MAESTRO_VERSION', 'MAESTRO_SHA256')


def read():
    return json.loads(STATE.read_text()) if STATE.exists() else {
        'schema': 1, 'phase': 'setup', 'category': None, 'result': 'running', 'exit_code': None, 'events': [],
    }


def write(data):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    temporary = STATE.with_suffix('.tmp')
    temporary.write_text(json.dumps(data, indent=2) + '\n')
    temporary.replace(STATE)


def category(phase):
    return 'test' if phase in ('maestro', 'native', 'components') else phase if phase != 'complete' else 'reporting'


def update(phase=None, result=None, exit_code=None, failure_category=None, initialize=False):
    data = read() if not initialize else {
        'schema': 1, 'phase': 'setup', 'category': None, 'result': 'running', 'exit_code': None, 'events': [],
        'identity': {name: os.environ[name] for name in IDENTITY_ENV if name in os.environ},
    }
    # A later successful cleanup must not replace the first test failure.
    if data.get('result') != 'failed':
        if phase:
            data['phase'] = phase
        if result:
            data['result'] = result
            data['exit_code'] = exit_code
            if result == 'failed':
                data['category'] = failure_category or category(data['phase'])
    data['events'].append({'phase': phase or data['phase'], 'result': result or 'running', 'at': time.time()})
    write(data)
    return data


def identity():
    data = read()
    hashes = {}
    for pattern in ('app/build/outputs/apk/mock/e2e/*.apk', 'app/build/outputs/apk/androidTest/mock/e2e/*.apk'):
        for path in sorted(Path('.').glob(pattern)):
            with path.open('rb') as stream:
                hashes[path.name] = hashlib.file_digest(stream, 'sha256').hexdigest()
    data['apks'] = hashes
    # Existing SDK preparation telemetry owns exact package revisions; retain safe package properties here too.
    sdk = Path(os.environ.get('ANDROID_HOME', '/nonexistent'))
    packages = {}
    for name, path in (
        ('emulator', sdk / 'emulator/source.properties'),
        ('image', sdk / 'system-images' / ('android-' + os.environ.get('AVD_API', '34')) /
         os.environ.get('AVD_TARGET', 'google_apis') / os.environ.get('AVD_ARCH', 'x86_64') / 'source.properties'),
    ):
        if path.is_file():
            values = {key.strip(): value for key, value in
                      (line.split('=', 1) for line in path.read_text().splitlines() if '=' in line)}
            packages[name] = {key: values[key].strip() for key in ('Pkg.Revision', 'AndroidVersion.ApiLevel') if key in values}
    data['sdk_packages'] = packages
    try:
        result = subprocess.run(['java', '-version'], capture_output=True, text=True, timeout=5)
        data['java_launcher'] = (result.stderr or result.stdout).splitlines()[0][:200]
    except (OSError, subprocess.TimeoutExpired, IndexError):
        data['java_launcher'] = 'unavailable'
    daemon = Path('gradle/gradle-daemon-jvm.properties')
    if daemon.is_file():
        data['gradle_daemon_configuration_sha256'] = hashlib.sha256(daemon.read_bytes()).hexdigest()
    write(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('init', 'phase', 'identity', 'finish', 'budget'))
    parser.add_argument('value', nargs='?')
    args = parser.parse_args()
    if args.command == 'init':
        update(initialize=True)
    elif args.command == 'phase':
        if args.value not in PHASES:
            parser.error('invalid execution phase')
        update(phase=args.value)
    elif args.command == 'identity':
        identity()
    elif args.command == 'budget':
        update(result='failed', exit_code=124, failure_category='budget')
    else:
        # Shell exit codes and Actions job statuses have the same fail-closed meaning here.
        if args.value in ('0', 'success'):
            update(phase='complete', result='passed', exit_code=0)
        else:
            code = int(args.value) if args.value and args.value.isdigit() else 1
            update(result='failed', exit_code=code, failure_category='budget' if code == 124 else None)


if __name__ == '__main__':
    main()
