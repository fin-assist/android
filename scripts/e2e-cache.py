#!/usr/bin/env python3
"""AVD preparation guards and shared process-group deadlines for e2e.yml (FIN-378/473)."""
import argparse
import hashlib
import json
import math
import os
import re
import signal
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
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
SNAPSHOT_PROCEDURE = 'clean-snapshot-v4'


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


def archive_maestro_output(out=Path('build/e2e')):
    """Finish e2e.sh's upload-safe archive after an interrupted shard, once its writers have stopped."""
    raw = out / 'maestro'
    if raw.is_dir():
        archive = out / 'maestro-output.tgz'
        temporary = archive.with_suffix('.tmp')
        with tarfile.open(temporary, 'w:gz') as stream:
            stream.add(raw, arcname='maestro')
        temporary.replace(archive)
        shutil.rmtree(raw)


def wait_for_process_group(process, timeout=5):
    """Require every group member to exit (zombies cannot write) before archiving."""
    deadline = time.monotonic() + timeout
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError('Command process group did not stop after SIGKILL; raw evidence retained')
        process.poll()  # Reap the leader without mistaking its exit for the descendants' exit.
        try:
            result = subprocess.run(['ps', '-eo', 'pgid=,stat='], check=True, capture_output=True,
                                    text=True, timeout=min(1, remaining))
        except subprocess.TimeoutExpired as error:
            # Inspection failure cannot prove that descendants stopped, and is not the command's deadline.
            raise RuntimeError('Command process group did not stop: process inspection timed out; raw evidence retained') from error
        members = [line.split() for line in result.stdout.splitlines() if line.strip()]
        # A zombie leader can still have a live JVM thread (procps marks this with lowercase l).
        if not any(int(group) == process.pid and not (status.startswith('Z') and 'l' not in status)
                   for group, status in members):
            # The leader may have become a zombie after poll(); reap it before handing the evidence to tar.
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                continue
            try:
                process.wait(timeout=min(1, remaining))
            except subprocess.TimeoutExpired as error:
                raise RuntimeError('Command process group did not stop: leader reap timed out; raw evidence retained') from error
            return
        time.sleep(min(0.05, max(0, deadline - time.monotonic())))


def record_budget_expiry(receipt=Path('build/e2e/execution.json')):
    """A collection timeout is additional evidence; it must not replace an earlier assertion failure."""
    if not receipt.is_file():
        return
    try:
        data = json.loads(receipt.read_text())
        if data.get('result') != 'failed':
            data.update(category='budget', result='failed', exit_code=124)
        data['budget_expired'] = True
        data['budget_expired_at'] = time.time()
        temporary = receipt.with_suffix('.tmp')
        temporary.write_text(json.dumps(data, indent=2) + '\n')
        temporary.replace(receipt)
    except (OSError, ValueError, TypeError, AttributeError):
        # Gather rejects the invalid receipt. Preserve the original bytes and still archive the raw trace.
        print('::warning::Could not annotate the process deadline; execution receipt retained for integrity checks')


def mark_budget_expiry(receipt=Path('build/e2e/execution.json')):
    """Tell a terminating shell's EXIT trap why it is stopping, without racing its JSON writer."""
    marker = receipt.with_suffix('.deadline')
    try:
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.touch()
    except OSError:
        # Cleanup and raw evidence retention remain mandatory even if storage cannot record the marker.
        print('::warning::Could not mark the process deadline before termination')


def run_command(command, timeout=2400, grace=10, archive_output=False, *,
                stdin=None, stdout=None, stderr=None, record_deadline=True):
    """Stop the whole command group on a real deadline, preserving the first failure and optional UI traces."""
    # SDK preparation owns a separate setup deadline receipt; it must not set a UI budget marker.
    def interrupted(signum, _frame):
        raise SystemExit(128 + signum)

    previous = signal.signal(signal.SIGTERM, interrupted)
    archive_ready = True
    budget_expired = False
    try:
        process = subprocess.Popen(command, start_new_session=True, stdin=stdin, stdout=stdout, stderr=stderr)
        try:
            return process.wait(timeout=timeout)
        except BaseException as error:
            budget_expired = isinstance(error, subprocess.TimeoutExpired)
            archive_ready = False
            if budget_expired and record_deadline:
                # A real Bash EXIT trap can run on TERM and otherwise record 143 as a test failure (or 0
                # as success on some shells). Never write execution.json until all child writers stop.
                mark_budget_expiry()
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            deadline = time.monotonic() + grace
            try:
                while time.monotonic() < deadline:
                    process.poll()  # Reap the leader; descendants may still be flushing their reports.
                    try:
                        os.killpg(process.pid, 0)
                    except ProcessLookupError:
                        break
                    time.sleep(min(0.05, max(0, deadline - time.monotonic())))
            finally:
                # A launcher can exit before a resistant JVM descendant. Kill the group even then.
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                wait_for_process_group(process)
                archive_ready = True
            raise
    except BaseException as error:
        # Cleanup failure may replace TimeoutExpired; preserve the observed deadline for setup callers too.
        if budget_expired:
            error.command_deadline_expired = True
        raise
    finally:
        signal.signal(signal.SIGTERM, previous)
        if archive_ready:
            if budget_expired and record_deadline:
                record_budget_expiry()
            if archive_output:
                archive_maestro_output()


def run_tests(shard, timeout=2400, grace=10, native_only=False):
    """Apply the same process-group coordinator to an emulator suite."""
    selection = ['--native-only'] if native_only else ['--shard', shard]
    return run_command(['scripts/e2e.sh', '--no-build', *selection], timeout, grace, archive_output=True)


def command_main(arguments):
    parser = argparse.ArgumentParser(description='Run one command with a real process-group deadline and execution evidence')
    parser.add_argument('--timeout', type=float, required=True)
    parser.add_argument('arguments', nargs=argparse.REMAINDER)
    args = parser.parse_args(arguments)
    command = args.arguments[1:] if args.arguments[:1] == ['--'] else args.arguments
    if not math.isfinite(args.timeout) or args.timeout <= 0 or not command:
        parser.error('a positive finite timeout and a command are required')
    # CLI execution already places this script's directory on sys.path; no external runtime is installed.
    from e2e_execution import category, read, update
    try:
        result = run_command(command, timeout=args.timeout)
    except subprocess.TimeoutExpired:
        update(result='failed', exit_code=124, failure_category='budget')
        raise SystemExit(124)
    if result:
        # 137 (and even a command's own 124) is not proof of a deadline. Only TimeoutExpired above is.
        update(result='failed', exit_code=result, failure_category=category(read()['phase']))
    raise SystemExit(result)


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


def hardware_summary(text):
    """Keep equality evidence and safe previews, never raw paths or identity/secret values."""
    fields = {}
    paths = set()
    for line in text.splitlines():
        key, separator, value = line.partition('=')
        key, value = key.strip(), value.strip()
        if not separator or not re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', key):
            continue
        if key in {'android.sdk.root', 'android.avd.home', 'hw.gpu.mode', 'avd.id'}:
            continue
        if key.endswith(('.path', '.initPath')):
            if key != 'disk.dataPartition.initPath':
                paths.add(key)
            continue
        if re.search(r'secret|token|password|serial|phone|email|account|card|uuid|(?:^|[._])(?:key|keys|pubkey|privatekey)(?:$|[._])', key, re.IGNORECASE):
            fields[key] = {'value': '<redacted>', 'not_compared': True}
        elif re.fullmatch(r'[+-]?[0-9]{1,12}(?:\.[0-9]{1,6})?(?:[KMGTkmgt])?', value) or value in {
                '', 'yes', 'no', 'true', 'false', 'on', 'off', 'auto', 'none', 'portrait', 'landscape',
                'x86', 'x86_64', 'arm', 'arm64', 'arm64-v8a', 'ranchu', 'ranchu64', 'pipe', 'guest', 'host'}:
            fields[key] = {'value': value}
        else:
            fields[key] = {'value': '<redacted>', 'digest': hashlib.sha256(value.encode()).hexdigest()}
    return {'fields': fields, 'path_keys': sorted(paths)}


def capture_snapshot_hardware():
    data = state()
    try:
        data['snapshot_hardware'] = hardware_summary((AVD / 'snapshots/clean/hardware.ini').read_text())
    except OSError:
        data['snapshot_hardware'] = {'unavailable': True}
    write(data)


def record_hardware_difference():
    data = state()
    expected = data.get('snapshot_hardware', {'unavailable': True})
    try:
        current = hardware_summary((AVD / 'hardware-qemu.ini').read_text())
    except OSError:
        current = {'unavailable': True}
    diagnostic = {'note': 'Raw INI telemetry before default stripping; AEMU alone decides snapshot compatibility.'}
    if expected.get('unavailable') or current.get('unavailable'):
        diagnostic['unavailable'] = True
    else:
        changes = []
        for key in sorted(expected['fields'].keys() | current['fields'].keys()):
            before = expected['fields'].get(key)
            after = current['fields'].get(key)
            if before != after:
                changes.append({'key': key, 'saved': before['value'] if before else '<absent>',
                                'current': after['value'] if after else '<absent>'})
        diagnostic['changed_fields'] = changes
        diagnostic['path_counts'] = {'saved': len(expected['path_keys']), 'current': len(current['path_keys'])}
    data['hardware_diagnostics'] = diagnostic
    write(data)
    print('Snapshot hardware diagnostics: ' + json.dumps(diagnostic, sort_keys=True))


def snapshot_exists(avd=AVD):
    # AEMU snapshot/common.h: named saves use kRamFileName (ram.bin); ram.img is optional mapped RAM.
    return all((avd / 'snapshots/clean' / name).is_file() and (avd / 'snapshots/clean' / name).stat().st_size > 0
               for name in ('snapshot.pb', 'ram.bin'))


def snapshot_disk_size(avd=AVD):
    values = []
    for line in (avd / 'snapshots/clean/hardware.ini').read_text().splitlines():
        key, separator, value = line.partition('=')
        if separator and key.strip() == 'disk.dataPartition.size':
            values.append(value.strip())
    if len(values) != 1:
        raise ValueError('Saved snapshot must have exactly one disk.dataPartition.size')
    match = re.fullmatch(r'([1-9][0-9]{0,18})([KMGTkmgt]?)', values[0])
    if not match:
        raise ValueError('Saved snapshot disk size must be a positive integer with an optional K/M/G/T unit')
    multiplier = {'': 1, 'k': 1024, 'm': 1024 ** 2, 'g': 1024 ** 3, 't': 1024 ** 4}[match[2].lower()]
    size = int(match[1]) * multiplier
    if size > 2 ** 63 - 1:
        raise ValueError('Saved snapshot disk size exceeds the supported integer range')
    return size


def usable(avd, key):
    try:
        metadata = json.loads((avd / 'finassist-clean.json').read_text())
        uuid.UUID(metadata['boot_id'])
        return (metadata['key'] == key and snapshot_exists(avd)
                and type(metadata['disk_size_bytes']) is int
                and metadata['disk_size_bytes'] == snapshot_disk_size(avd))
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
    if sys.argv[1:2] == ['run-command']:
        command_main(sys.argv[2:])
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['adb-init', 'key', 'validate', 'snapshot', 'seal', 'test', 'fallback', 'mark', 'report'])
    parser.add_argument('name', nargs='?')
    parser.add_argument('--shard')
    parser.add_argument('--native-only', action='store_true')
    parser.add_argument('--timeout', type=float, help='process deadline in seconds (default Maestro2400/native600)')
    cold = parser.add_mutually_exclusive_group()
    cold.add_argument('--cold', action='store_true')
    cold.add_argument('--direct-cold', action='store_true')
    args = parser.parse_args()
    if args.timeout is not None and (not math.isfinite(args.timeout) or args.timeout <= 0):
        parser.error('timeout must be positive finite seconds')
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
        if hit:
            capture_snapshot_hardware()
            output('disk-size', snapshot_disk_size(AVD))
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
            snapshot_dir = AVD / 'snapshots/clean'
            found = {path.name: path.stat().st_size for path in snapshot_dir.iterdir() if path.is_file()} if snapshot_dir.is_dir() else {}
            raise RuntimeError(f'Clean snapshot is missing or incomplete: expected nonempty snapshot.pb and ram.bin; files={found}')
        data = state()
        disk_size = snapshot_disk_size(AVD)
        META.write_text(json.dumps({'key': data['key'], 'boot_id': data['boot_id'], 'disk_size_bytes': disk_size}))
        capture_snapshot_hardware()
        output('disk-size', disk_size)
        mark('save-start')
    elif args.command == 'test':
        is_cold = args.cold or args.direct_cold
        mark('direct-boot-ready' if args.direct_cold else 'fallback-boot-ready' if args.cold else 'test-boot-ready')
        assert_clean()
        if not is_cold and cache_key() != state()['key']:
            raise RuntimeError('SDK changed after cache restore; use a fresh device')
        if not is_cold:
            try:
                assert_snapshot_loaded(json.loads(META.read_text()), boot_id())
            except RuntimeError:
                record_hardware_difference()
                raise
        data = state()
        data['boot_mode'] = 'fresh-cold-direct' if args.direct_cold else 'fresh-cold-fallback' if args.cold else 'snapshot-verified'
        if args.direct_cold:
            data['cache'] = 'disabled'
        write(data)
        # The coordinator uses this durable marker to distinguish preparation failure from a real test failure.
        mark('tests-started')
        try:
            budget = args.timeout if args.timeout is not None else 600 if args.native_only else 2400
            result = run_tests(None, timeout=budget, native_only=True) if args.native_only else run_tests(args.shard, timeout=budget)
        finally:
            mark('tests-finished')
        raise SystemExit(result)
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
                ('Direct cold boot', 'direct-boot-start', 'direct-boot-ready'),
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
