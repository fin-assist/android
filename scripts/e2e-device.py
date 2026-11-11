#!/usr/bin/env python3
"""Observe UI readiness and native runner phases without changing test verdicts."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET

from e2e_execution import native_mark

LOG_LIMIT = 8192
APP_NOT_FOREGROUND = 3
CONNECTED_TASK = re.compile(r'^> Task :app:connectedMockE2eAndroidTest(?:\s|$)')
DEVICE_PROGRESS = re.compile(r'^\S+ - \d+ Tests (\d+)/(\d+) completed\. \((\d+) skipped\) \((\d+) failed\)$')


def adb_diagnostic(args, log, timeout):
    """Keep bounded stdout/stderr even when the adb client exceeds its command deadline."""
    start = time.monotonic()
    with tempfile.TemporaryFile() as output:
        try:
            code = subprocess.run(['adb', *args], stdout=output, stderr=subprocess.STDOUT,
                                  timeout=timeout, check=False).returncode
        except subprocess.TimeoutExpired:
            code = None
        output.seek(0)
        data = output.read(LOG_LIMIT + 1)
    log.write_bytes(data[:LOG_LIMIT])
    return {'exit_code': code, 'timed_out': code is None,
            'seconds': round(time.monotonic() - start, 3), 'truncated': len(data) > LOG_LIMIT}


def hierarchy_status(path, package):
    if not path.is_file():
        return 'missing_xml'
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return 'invalid_xml'
    if root.tag != 'hierarchy':
        return 'invalid_xml'
    nodes = list(root.iter('node'))
    if any("isn't responding" in node.get('text', '') for node in nodes):
        return 'anr_dialog'
    packages = {node.get('package') for node in nodes if node.get('package')}
    if packages and package not in packages:
        return 'app_not_foreground'
    if any(node.get('package') == package and node.get('resource-id') == 'auth.phone' for node in nodes):
        return 'ready'
    return 'phone_root_missing'


def preflight(out, package, attempt):
    """Pull a fresh hierarchy; a successful dump exit code alone is insufficient."""
    directory = out / 'preflight'
    directory.mkdir(parents=True, exist_ok=True)
    prefix = directory / f'attempt-{attempt}'
    remote = f'/sdcard/pf-ui-{uuid.uuid4().hex}.xml'
    latest = out / 'preflight-ui.xml'
    latest.unlink(missing_ok=True)
    result = {'dump': adb_diagnostic(['shell', 'uiautomator', 'dump', remote], prefix.with_suffix('.dump.log'), 20)}
    try:
        if result['dump']['exit_code'] != 0:
            result['result'] = 'dump_failed'
        else:
            hierarchy = prefix.with_suffix('.xml')
            result['pull'] = adb_diagnostic(['pull', remote, str(hierarchy)], prefix.with_suffix('.pull.log'), 10)
            if result['pull']['exit_code'] == 0:
                result['result'] = hierarchy_status(hierarchy, package)
                if hierarchy.is_file():
                    latest.write_bytes(hierarchy.read_bytes())
            else:
                result['result'] = 'pull_failed'
    finally:
        # Each attempt uses a unique remote path so even failed cleanup cannot leak a stale XML into the next check.
        adb_diagnostic(['shell', 'rm', '-f', remote], prefix.with_suffix('.cleanup.log'), 5)
    prefix.with_suffix('.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(f"[e2e] preflight {attempt}: {result['result']}", flush=True)
    if result['result'] == 'app_not_foreground':
        return APP_NOT_FOREGROUND
    return 0 if result['result'] == 'ready' else 1


def watch_native(command):
    """Stream Gradle output while recording observed AGP boundaries and counters."""
    native_mark('gradle_started')
    connected_seen = False
    progress_seen = None
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT) as process:
        assert process.stdout is not None
        for line in process.stdout:
            message = line.decode('utf-8', errors='replace').strip()
            if not connected_seen and CONNECTED_TASK.match(message):
                native_mark('connected_task_observed')
                connected_seen = True
            match = DEVICE_PROGRESS.fullmatch(message)
            if match and match.groups() != progress_seen:
                progress_seen = match.groups()
                completed, total, skipped, failed = map(int, progress_seen)
                native_mark('device_progress', {'completed': completed, 'total': total,
                                                'skipped': skipped, 'failed': failed})
            sys.stdout.buffer.write(line)
            sys.stdout.buffer.flush()
        code = process.wait()
    native_mark('gradle_finished', {'exit_code': code})
    return code if code >= 0 else 128 - code


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    probe = sub.add_parser('preflight')
    probe.add_argument('out', type=Path)
    probe.add_argument('package')
    probe.add_argument('attempt', type=int, choices=(1, 2, 3))
    native = sub.add_parser('native')
    native.add_argument('gradle', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command == 'preflight':
        return preflight(args.out, args.package, args.attempt)
    command = args.gradle[1:] if args.gradle[:1] == ['--'] else args.gradle
    if not command:
        parser.error('native requires a Gradle command')
    return watch_native(command)


if __name__ == '__main__':
    sys.exit(main())
