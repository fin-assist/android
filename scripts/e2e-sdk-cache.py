#!/usr/bin/env python3
"""Select SDK package caches, recover failed restores, and record actual online-installed revisions."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

SCHEMA = 'e2e-sdk-v1'
WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/e2e.yml'
STATE = Path('build/e2e/sdk-cache.json')
INSTALL_SECONDS = 600
LICENSE_SECONDS = 120
LOG_BYTES = 64 * 1024
PROCESS_SPEC = importlib.util.spec_from_file_location('sdk_process', Path(__file__).with_name('e2e-cache.py'))
PROCESS = importlib.util.module_from_spec(PROCESS_SPEC)
PROCESS_SPEC.loader.exec_module(PROCESS)


def license_hash(text):
    """Match SDK TrimStringAdapter.unmarshal followed by License.getLicenseHash (UTF-8 SHA1)."""
    text = re.sub(r'(?<=\s)[ \t]*', '', text, flags=re.ASCII)
    text = re.sub(r'(?<!\n)\n(?!\n)', ' ', text)
    text = re.sub(r' +', ' ', text).strip(''.join(chr(code) for code in range(33)))
    return hashlib.sha1(text.encode('utf-8')).hexdigest()


def validate_installed(root, packages):
    """Check selected local metadata, binaries and actual SDK license acceptance, never write license hashes."""
    selected = {'platform-tools': root / 'platform-tools', **packages}
    for name, path in selected.items():
        if not path.resolve().is_relative_to(root.resolve()):
            raise ValueError('SDK package escapes its SDK root')
        metadata(path)
        document = ET.parse(path / 'package.xml').getroot()
        local = document.find('localPackage')
        expected = str(path.relative_to(root)).replace('/', ';')
        if local is None or local.get('path') != expected:
            raise ValueError('Installed SDK package identity does not match its requested path')
        uses = local.find('uses-license')
        license_id = uses.get('ref', '') if uses is not None else ''
        if not re.fullmatch(r'[A-Za-z0-9_-][A-Za-z0-9_.-]*', license_id):
            raise ValueError('Missing or invalid SDK package license reference')
        licenses = [item for item in document.findall('license') if item.get('id') == license_id]
        if len(licenses) != 1 or not licenses[0].text:
            raise ValueError('Missing or ambiguous SDK package license text')
        accepted = root / 'licenses' / license_id
        if not accepted.resolve().is_relative_to(root.resolve()):
            raise ValueError('SDK license file escapes its SDK root')
        # License.checkAccepted compares the normalized text hash to a complete line, never a substring.
        digest = license_hash(licenses[0].text)
        if digest not in accepted.read_text().splitlines():
            raise ValueError('Required SDK package license has not been accepted')
        binary = path / ('system.img' if name == 'image' else 'adb' if name == 'platform-tools' else 'emulator')
        if not binary.is_file() or (name != 'image' and not os.access(binary, os.X_OK)):
            raise ValueError('Required SDK package binary is missing or not executable')


def prepare():
    """Skip redundant global licensing only after local validation; always retain the online install check."""
    from e2e_execution import update
    root, packages = package_paths()
    data = json.loads(STATE.read_text())
    detail = {'phase': 'inspect', 'result': 'running', 'started': time.time(), 'steps': [], 'exit_code': None}
    data['preparation'] = detail
    write(data)
    code = 1
    def command(phase, arguments, seconds):
        detail['phase'] = phase
        step = {'phase': phase, 'started': time.time(), 'timeout_seconds': seconds,
                'deadline_expired': False, 'exit_code': None, 'log': 'sdk-' + phase + '.log'}
        detail['steps'].append(step)
        write(data)
        result = 1
        try:
            # A finite stream accepts SDK prompts without a separate yes process/Broken pipe diagnostic.
            with tempfile.TemporaryFile() as answers, tempfile.TemporaryFile() as log:
                answers.write(b'y\n' * 100)
                answers.seek(0)
                try:
                    result = PROCESS.run_command([
                        str(root / 'cmdline-tools/latest/bin/sdkmanager'), '--sdk_root=' + str(root),
                        *arguments, '--verbose',
                    ], timeout=seconds, stdin=answers, stdout=log, stderr=subprocess.STDOUT,
                        record_deadline=False)
                except subprocess.TimeoutExpired:
                    step['deadline_expired'] = True
                    result = 124
                finally:
                    size = log.seek(0, os.SEEK_END)
                    log.seek(max(0, size - LOG_BYTES))
                    STATE.with_name(step['log']).write_bytes(log.read(LOG_BYTES))
                    step.update(log_bytes=size, log_truncated=size > LOG_BYTES)
            return result
        finally:
            step.update(exit_code=result, finished=time.time())
            write(data)

    try:
        try:
            validate_installed(root, packages)
            detail['licenses'] = 'already_accepted'
        except (OSError, ValueError, ET.ParseError):
            detail['licenses'] = 'preparation_required'
            code = command('licenses', ['--licenses'], LICENSE_SECONDS)
            if code:
                return code
        image = ';'.join(('system-images', 'android-' + os.environ['AVD_API'],
                          os.environ['AVD_TARGET'], os.environ['AVD_ARCH']))
        code = command('install', ['--install', 'platform-tools', 'emulator', image, '--channel=0'], INSTALL_SECONDS)
        if code:
            return code
        detail['phase'] = 'validate'
        write(data)
        validate_installed(root, packages)
        finalize()
        detail['phase'] = 'complete'
        detail['result'] = 'passed'
        detail['exit_code'] = 0
        return 0
    except (OSError, ValueError, ET.ParseError) as error:
        # Our validation messages are fixed; OS/parser errors expose only their class in receipts.
        detail['error'] = str(error)[:200] if isinstance(error, ValueError) else type(error).__name__
        code = 1
        return code
    finally:
        # finalize owns the cache keys; preserve them instead of replacing its updated state.
        latest = json.loads(STATE.read_text())
        detail['finished'] = time.time()
        if detail['result'] != 'passed':
            code = code or 1
            detail.update(result='failed', exit_code=code)
            update(phase='setup', result='failed', exit_code=code, failure_category='setup')
        latest['preparation'] = detail
        write(latest)
        print('SDK preparation: ' + json.dumps(detail, sort_keys=True))


def write(data):
    STATE.parent.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(data, indent=2) + '\n')


def output(name, value):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as stream:
        stream.write(f'{name}={value}\n')


def package_paths():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted':
        raise ValueError('SDK cache requires a disposable GitHub-hosted runner')
    root = Path(os.environ['ANDROID_HOME'])
    if not root.is_absolute() or root == Path('/') or not (root / 'cmdline-tools/latest/bin/sdkmanager').is_file():
        raise ValueError('Expected an absolute SDK root containing SDK manager')
    api, target, arch = [os.environ[name] for name in ('AVD_API', 'AVD_TARGET', 'AVD_ARCH')]
    if not api.isdecimal() or any(not re.fullmatch(r'[A-Za-z0-9_-]+', value) for value in (target, arch)):
        raise ValueError('Invalid requested system-image package')
    paths = {'emulator': root / 'emulator', 'image': root / f'system-images/android-{api}/{target}/{arch}'}
    for path in paths.values():
        if path.is_symlink() or not path.resolve().is_relative_to(root.resolve()):
            raise ValueError('SDK package path escapes its SDK root')
    return root, paths


def action_revision():
    revisions = re.findall(r'uses: reactivecircus/android-emulator-runner@(\S+)', WORKFLOW.read_text())
    if not revisions or len(set(revisions)) != 1 or not re.fullmatch(r'[0-9a-f]{40}', revisions[0]):
        raise ValueError('All emulator actions must use the same audited SHA')
    return revisions[0]


def runner_identity():
    runner = {key: os.environ[key] for key in ('RUNNER_OS', 'RUNNER_ARCH', 'ImageOS', 'ImageVersion')}
    if not all(value.strip() for value in runner.values()):
        raise ValueError('Missing runner image identity')
    return runner


def prefix(name, root, path):
    # Package archives can span patch revisions of one ImageOS; AVD snapshots keep their stricter guard.
    runner = {key: value for key, value in runner_identity().items() if key != 'ImageVersion'}
    payload = {'runner': runner, 'package': str(path.relative_to(root)), 'action': action_revision()}
    digest = hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()
    return f'{SCHEMA}-{name}-{digest}-'


def metadata(path):
    data = (path / 'source.properties').read_bytes()
    revision = re.findall(rb'^Pkg.Revision\s*=\s*([^\r\n]+)', data, re.MULTILINE)
    if len(revision) != 1 or not re.fullmatch(rb'[0-9]+(?:\.[0-9]+)*(?:\s+(?:rc|beta|alpha)[0-9]+)?', revision[0].strip()):
        raise ValueError('Invalid SDK package revision')
    return {'revision': revision[0].decode().strip(), 'source_properties_sha256': hashlib.sha256(data).hexdigest()}


def final_key(package_prefix, actual):
    return package_prefix + hashlib.sha256(json.dumps(actual, sort_keys=True).encode()).hexdigest()


def paths():
    root, packages = package_paths()
    data = {'started': time.time(), 'runner': runner_identity(), 'packages': {}}
    for name, path in packages.items():
        entry = {'prefix': prefix(name, root, path), 'restore_attempted': not path.exists()}
        data['packages'][name] = entry
        output(f'{name}-path', str(path))
        output(f'{name}-prefix', entry['prefix'])
        # Never merge a restored archive over SDK files already supplied by the hosted image.
        output(f'{name}-restore', str(entry['restore_attempted']).lower())
    write(data)


def recover():
    root, packages = package_paths()
    data = json.loads(STATE.read_text())
    for name, path in packages.items():
        entry = data['packages'][name]
        if entry['prefix'] != prefix(name, root, path):
            raise ValueError('SDK cache configuration changed before restore recovery')
        entry['matched_key'] = os.environ.get(f'{name.upper()}_MATCHED_KEY', '')
        if not entry['restore_attempted']:
            continue
        try:
            if os.environ.get(f'{name.upper()}_OUTCOME') != 'success' or not entry['matched_key']:
                raise ValueError('Cache absent or restore failed')
            if final_key(entry['prefix'], metadata(path)) != entry['matched_key']:
                raise ValueError('Restored SDK metadata does not match its immutable key')
            entry['restore_valid'] = True
        except (ValueError, OSError):
            # Only these two initially absent package directories may be removed, never AVDs/adb keys.
            if path.is_dir():
                shutil.rmtree(path)
            elif path.exists():
                path.unlink()
            entry['restore_valid'] = False
            entry['restore_error'] = 'missing-failed-or-incompatible'
            print(f'SDK {name}: install normally after missing/failed/incompatible cache restore')
    data['restore_finished'] = time.time()
    write(data)


def finalize():
    root, packages = package_paths()
    data = json.loads(STATE.read_text())
    for name, path in packages.items():
        entry = data['packages'][name]
        if entry['prefix'] != prefix(name, root, path):
            raise ValueError('SDK cache configuration changed during online installation')
        entry['actual'] = metadata(path)
        entry['final_key'] = final_key(entry['prefix'], entry['actual'])
        if not entry['restore_attempted']:
            entry['classification'] = 'preinstalled'
        elif not entry.get('restore_valid'):
            entry['classification'] = 'miss'
        else:
            entry['classification'] = 'hit-current' if entry['matched_key'] == entry['final_key'] else 'hit-updated'
        output(f'{name}-key', entry['final_key'])
        save = entry['restore_attempted'] and (not entry.get('restore_valid') or entry['matched_key'] != entry['final_key'])
        output(f'{name}-save', str(save).lower())
    data['install_finished'] = time.time()
    write(data)


def complete():
    data = json.loads(STATE.read_text())
    data['save_finished'] = time.time()
    data['seconds'] = {'restore': data['restore_finished'] - data['started'],
                       'online_install': data['install_finished'] - data['restore_finished'],
                       'save_and_steps': data['save_finished'] - data['install_finished'],
                       'total': data['save_finished'] - data['started']}
    write(data)
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as stream:
        stream.write('### SDK package preparation\n\n| Package | Cache | Installed revision |\n|---|---|---|\n')
        for name, entry in data['packages'].items():
            stream.write(f"| {name} | {entry['classification']} | {entry['actual']['revision']} |\n")
        stream.write('\nOnline SDK checks always run. Phase seconds: `' + json.dumps(data['seconds']) + '`.\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['paths', 'recover', 'prepare', 'finalize', 'complete'])
    raise SystemExit(globals()[parser.parse_args().command]())
