#!/usr/bin/env python3
"""Select SDK package caches, recover failed restores, and record actual online-installed revisions."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import time

SCHEMA = 'e2e-sdk-v1'
WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/e2e.yml'
STATE = Path('build/e2e/sdk-cache.json')


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
    parser.add_argument('command', choices=['paths', 'recover', 'finalize', 'complete'])
    globals()[parser.parse_args().command]()
