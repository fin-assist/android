#!/usr/bin/env python3
"""Clean AVD cache guards and preparation measurements for e2e.yml (FIN-378)."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import time
import uuid

STATE = Path('build/e2e/preparation.json')
AVD_ROOT = Path.home() / '.android/avd'
NAME = os.environ.get('AVD_NAME', 'pf-e2e')
AVD = AVD_ROOT / f'{NAME}.avd'
META = AVD / 'finassist-clean.json'
KEY = AVD_ROOT.parent / 'adbkey'
KEY_PUBLIC = AVD_ROOT.parent / 'adbkey.pub'
KEY_RECEIPT = AVD_ROOT.parent / 'finassist-ci-adb.json'
SNAPSHOT_PROCEDURE = 'clean-snapshot-v2'


def output(name, value):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
        stream.write(f'{name}={value}\n')


def state():
    return json.loads(STATE.read_text()) if STATE.exists() else {'marks': {}}


def write(data):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(data, indent=2) + '\n')


def mark(name):
    data = state()
    data['marks'][name] = time.time()
    write(data)


def fingerprint():
    sdk = Path(os.environ['ANDROID_HOME'])
    image = sdk / 'system-images' / f'android-{os.environ["AVD_API"]}' / os.environ['AVD_TARGET'] / os.environ['AVD_ARCH']
    # These are the installed revisions, not a guessed latest build number. Fail closed on missing metadata.
    packages = {}
    for name, path in [('emulator', sdk / 'emulator'), ('image', image)]:
        properties = (path / 'source.properties').read_text()
        if not any(line.startswith('Pkg.Revision=') or line.startswith('Pkg.Revision =') for line in properties.splitlines()):
            raise ValueError(f'Missing {name} revision')
        packages[name] = properties
    cpu = Path('/proc/cpuinfo').read_text()
    cpu = '\n'.join(line for line in cpu.splitlines() if line.startswith(('vendor_id', 'model name', 'flags')))
    return {
        'packages': packages,
        'runner': {key: os.environ[key] for key in ('RUNNER_OS', 'RUNNER_ARCH', 'ImageOS', 'ImageVersion')},
        'cpu': sorted(set(cpu.splitlines())),
        'config': {key: value for key, value in os.environ.items() if key.startswith('AVD_')},
        # Defaults come from the audited action; procedure changes must bump this explicit version.
        'action': action_revision(Path('.github/workflows/e2e.yml').read_text()),
        'procedure': SNAPSHOT_PROCEDURE,
    }


def cache_key():
    return 'clean-avd-v2-' + hashlib.sha256(json.dumps(fingerprint(), sort_keys=True).encode()).hexdigest()


def action_revision(workflow):
    revisions = re.findall(r'uses: reactivecircus/android-emulator-runner@(\S+)', workflow)
    if not revisions or len(set(revisions)) != 1 or not re.fullmatch(r'[0-9a-f]{40}', revisions[0]):
        raise ValueError('All emulator actions must use the same audited SHA')
    return revisions[0]


def require_hosted_ci():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted':
        raise RuntimeError('Disposable adb key operations require a GitHub-hosted CI runner')


def adb_executable():
    # android-emulator-runner adds SDK tools to PATH only after the pre-action cache/key steps.
    return str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')


def key_receipt():
    return {'purpose': 'finassist-clean-avd-ci', 'private_sha256': hashlib.sha256(KEY.read_bytes()).hexdigest(),
            'public_sha256': hashlib.sha256(KEY_PUBLIC.read_bytes()).hexdigest()}


def assert_ci_key():
    require_hosted_ci()
    if not KEY.is_file() or not KEY_PUBLIC.is_file() or KEY.stat().st_size == 0 or KEY_PUBLIC.stat().st_size == 0:
        raise RuntimeError('Missing disposable CI adb key pair')
    if json.loads(KEY_RECEIPT.read_text()) != key_receipt():
        raise RuntimeError('adb key pair was not generated for this CI cache')


def initialize_ci_key():
    require_hosted_ci()  # Never read, delete, or cache a developer's existing adb keys.
    subprocess.run([adb_executable(), 'kill-server'], check=True, timeout=30)
    KEY.parent.mkdir(parents=True, exist_ok=True)
    for path in (KEY, KEY_PUBLIC, KEY_RECEIPT):
        path.unlink(missing_ok=True)
    subprocess.run([adb_executable(), 'keygen', str(KEY)], check=True, timeout=30, capture_output=True)
    KEY.chmod(0o600)
    KEY_RECEIPT.write_text(json.dumps(key_receipt()))


def reload_ci_key():
    assert_ci_key()
    KEY.chmod(0o600)
    # A server started before cache restore may still hold the new runner's previous key in memory.
    subprocess.run([adb_executable(), 'kill-server'], check=True, timeout=30)
    subprocess.run([adb_executable(), 'start-server'], check=True, timeout=30)


def snapshot_exists(avd=AVD):
    return all((avd / 'snapshots/clean' / name).is_file() and (avd / 'snapshots/clean' / name).stat().st_size > 0
               for name in ('snapshot.pb', 'ram.img'))


def usable(avd, key):
    try:
        metadata = json.loads((avd / 'finassist-clean.json').read_text())
        uuid.UUID(metadata['boot_id'])
        return metadata['key'] == key and snapshot_exists(avd)
    except (OSError, ValueError, KeyError, TypeError):
        return False


def adb(*args):
    result = subprocess.run([adb_executable(), '-s', os.environ.get('ANDROID_SERIAL', 'emulator-5554'), *args],
                            check=True, text=True, capture_output=True, timeout=120)
    if any(line.startswith('KO:') for line in result.stdout.splitlines()):
        raise RuntimeError(result.stdout)
    return result.stdout


def boot_id():
    return str(uuid.UUID(adb('shell', 'cat', '/proc/sys/kernel/random/boot_id').strip()))


def assert_snapshot_loaded(metadata, actual_boot_id):
    if metadata['boot_id'] != actual_boot_id:
        raise RuntimeError('Snapshot was not loaded (Android cold-booted); use a fresh device')


def assert_clean():
    packages = adb('shell', 'pm', 'list', 'packages')
    if 'package:android' not in packages.splitlines():
        raise RuntimeError('Android package manager is not ready; refusing to cache/use the device')
    if any(line.strip().startswith('package:ru.finassist.pf') for line in packages.splitlines()):
        raise RuntimeError('The pristine AVD contains a FinAssist APK; refusing to cache/use it')


def wait_stopped():
    # The action sends `adb emu kill`; the process may still be flushing the snapshot when it returns.
    deadline = time.monotonic() + 120
    while True:
        processes = subprocess.check_output(['ps', '-eo', 'args'], text=True, timeout=10).splitlines()
        if not any('qemu-system' in line and f'-avd {NAME}' in line for line in processes):
            return
        if time.monotonic() >= deadline:
            raise TimeoutError('Emulator did not stop; refusing to cache a live AVD')
        time.sleep(1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['adb-init', 'key', 'validate', 'snapshot', 'seal', 'test', 'fallback', 'mark', 'report'])
    parser.add_argument('name', nargs='?')
    parser.add_argument('--shard')
    parser.add_argument('--cold', action='store_true')
    args = parser.parse_args()
    if args.command == 'adb-init':
        initialize_ci_key()
    elif args.command == 'mark':
        mark(args.name)
    elif args.command == 'key':
        data = state()
        data['key'] = cache_key()
        write(data)
        mark('sdk-ready')
        output('key', data['key'])
    elif args.command == 'validate':
        reload_ci_key()
        data = state()
        hit = usable(AVD, data['key'])
        data['cache'] = 'hit' if hit else 'miss-or-invalid'
        write(data)
        if not hit:
            shutil.rmtree(AVD, ignore_errors=True)
            (AVD_ROOT / f'{NAME}.ini').unlink(missing_ok=True)
        mark('restore-ready')
        output('usable', str(hit).lower())
    elif args.command == 'snapshot':
        assert_ci_key()
        mark('create-boot-ready')
        assert_clean()
        if cache_key() != state()['key']:
            raise RuntimeError('SDK changed after cache key resolution; do not save under an outdated key')
        data = state()
        data['boot_id'] = boot_id()
        write(data)
        adb('emu', 'avd', 'snapshot', 'save', 'clean')
        mark('snapshot-saved')
    elif args.command == 'seal':
        wait_stopped()
        assert_ci_key()
        if not snapshot_exists(AVD):
            raise RuntimeError('Clean snapshot is missing or incomplete')
        data = state()
        META.write_text(json.dumps({'key': data['key'], 'boot_id': data['boot_id']}))
        mark('save-start')
    elif args.command == 'test':
        mark('fallback-boot-ready' if args.cold else 'test-boot-ready')
        assert_clean()
        if not args.cold and cache_key() != state()['key']:
            raise RuntimeError('SDK changed after cache restore; use a fresh device')
        if not args.cold:
            assert_snapshot_loaded(json.loads(META.read_text()), boot_id())
        data = state()
        data['boot_mode'] = 'fresh-cold-fallback' if args.cold else 'snapshot-verified'
        write(data)
        # The coordinator uses this durable marker to distinguish preparation failure from a real test failure.
        mark('tests-started')
        try:
            result = subprocess.run(['scripts/e2e.sh', '--no-build', '--shard', args.shard], timeout=2400)
        finally:
            mark('tests-finished')
        raise SystemExit(result.returncode)
    elif args.command == 'fallback':
        wait_stopped()
        data = state()
        needed = 'tests-started' not in data['marks']
        if needed and (data.get('cache') == 'hit' or 'save-start' in data['marks']):
            warning = (f"Clean AVD snapshot {data['key']} failed preparation; using one fresh cold fallback. "
                       'Delete this entry in Actions > Caches or bump clean-avd-v2 if it repeats; '
                       'this is not a successful snapshot hit or demonstrated speedup.')
            print('::warning::' + warning)
            data['cache_warning'] = warning
            write(data)
        output('needed', str(needed).lower())
    elif args.command == 'report':
        data = state()
        marks = data['marks']
        rows = [('SDK preparation', 'sdk-start', 'sdk-ready'), ('Cache restore/validation', 'restore-start', 'restore-ready'),
                ('Cold Android boot', 'create-boot-start', 'create-boot-ready'),
                ('Save snapshot / shutdown', 'create-boot-ready', 'save-start'),
                ('Cache save', 'save-start', 'cache-ready'), ('Test boot', 'test-boot-start', 'test-boot-ready'),
                ('Fallback boot', 'fallback-boot-start', 'fallback-boot-ready'),
                ('UI scenarios (e2e.sh)', 'tests-started', 'tests-finished')]
        lines = ['### Emulator preparation', f'Cache: `{data.get("cache", "unavailable")}`; test boot: `{data.get("boot_mode", "unverified")}`', '', '| Phase | Seconds |', '|---|---:|']
        for label, start, end in rows:
            if start in marks and end in marks:
                lines.append(f'| {label} | {marks[end] - marks[start]:.2f} |')
        if data.get('cache_warning'):
            lines += ['', data['cache_warning']]
        lines += ['', 'Action step durations also include repeated SDK checks/AVD setup and teardown; see job timings.', '']
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
            stream.write('\n'.join(lines))


if __name__ == '__main__':
    main()
