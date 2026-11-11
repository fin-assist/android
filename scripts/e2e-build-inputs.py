#!/usr/bin/env python3
"""Publish and check exact mockE2e inputs from this workflow's build job (FIN-489)."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil

INPUTS = Path('build/e2e-input')
APK_DIRS = {
    'app': (Path('app/build/outputs/apk/mock/e2e'), 'app-mock-e2e.apk'),
    'test': (Path('app/build/outputs/apk/androidTest/mock/e2e'), 'app-mock-e2e-androidTest.apk'),
}
APK_FILES = {f'{label}/{file}': directory / file
             for label, (directory, apk) in APK_DIRS.items()
             for file in (apk, 'output-metadata.json')}
# AGP 8.9 uses these instrumented-test classes for discovery, besides the two APK listings.
INTERMEDIATES = Path('app/build/intermediates')
NATIVE_CLASSES = INTERMEDIATES / 'classes/mockE2eAndroidTest/transformMockE2eAndroidTestClassesWithAsm'
NATIVE_R = INTERMEDIATES / ('compile_and_runtime_not_namespaced_r_class_jar/mockE2eAndroidTest/'
                            'processMockE2eAndroidTestResources/R.jar')
NATIVE_FILES = {'native/R.jar': NATIVE_R}
NATIVE_CLASS_PATH = re.compile(r'native/(dirs/(?:[A-Za-z0-9_$.-]+/)*[A-Za-z0-9_$.-]+\.class|jars/[0-9]+\.jar)')
CLASS_FILES = {'e2e-classes.zip': INPUTS / 'e2e-classes.zip'}
IDENTITY = ('GITHUB_SHA', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT')


def digest(path):
    if path.is_symlink() or not path.is_file() or not path.stat().st_size:
        raise ValueError(f'missing regular nonempty build input: {path}')
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def metadata(directory):
    for label, (_, apk) in APK_DIRS.items():
        elements = json.loads((directory / label / 'output-metadata.json').read_text())['elements']
        if len(elements) != 1 or elements[0].get('outputFile') != apk:
            raise ValueError(f'expected exactly one {label} APK in AGP metadata')


def native_inputs(names):
    files = dict(NATIVE_FILES)
    for name in names:
        match = NATIVE_CLASS_PATH.fullmatch(name)
        if match and not {'.', '..'}.intersection(Path(name).parts):
            files[name] = NATIVE_CLASSES / match[1]
    if not any(name.startswith('native/dirs/') for name in files):
        raise ValueError('no instrumented-test classes in AGP inputs')
    return files


def publish(root, identity):
    names = ['native/' + path.relative_to(root / NATIVE_CLASSES).as_posix()
             for path in (root / NATIVE_CLASSES).rglob('*') if path.is_file()]
    for kind, paths in (('apks', APK_FILES | native_inputs(names)), ('classes', CLASS_FILES)):
        directory = root / INPUTS / kind
        files = {}
        for name, source in paths.items():
            files[name] = digest(root / source)
            target = directory / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(root / source, target)
        if kind == 'apks':
            metadata(directory)
        (directory / 'manifest.json').write_text(json.dumps({'identity': identity, 'files': files}) + '\n')


def consume(root, identity, kind, check_only=False):
    directory = root / INPUTS / kind
    manifest = directory / 'manifest.json'
    digest(manifest)
    data = json.loads(manifest.read_text())
    producer = data['identity']
    if (any(producer[key] != identity[key] for key in IDENTITY[:2])
            or not 1 <= int(producer[IDENTITY[2]]) <= int(identity[IDENTITY[2]])):
        raise ValueError('build input source, workflow run, or attempt mismatch')
    files = data['files']
    paths = APK_FILES | native_inputs(files) if kind == 'apks' else CLASS_FILES
    if set(files) != set(paths):
        raise ValueError('unexpected build input manifest inventory')
    for name, target in paths.items():
        if digest(directory / name) != files[name]:
            raise ValueError(f'build input checksum mismatch: {name}')
        if check_only and digest(root / target) != files[name]:
            raise ValueError(f'installed build input changed: {name}')
    if kind == 'apks':
        metadata(directory)
    if not check_only:
        if kind == 'apks':
            (root / NATIVE_CLASSES / 'jars').mkdir(parents=True, exist_ok=True)
        for name, target in paths.items():
            destination = root / target
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(directory / name, destination)
    print(f'Verified {kind} for {producer[IDENTITY[0]]}, build attempt {producer[IDENTITY[2]]}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('publish', 'apks', 'classes', 'check-apks'))
    args = parser.parse_args()
    try:
        identity = {name: os.environ[name] for name in IDENTITY}
        if args.command == 'publish':
            publish(Path.cwd(), identity)
        else:
            consume(Path.cwd(), identity,
                    args.command.removeprefix('check-'), check_only=args.command == 'check-apks')
    except (OSError, ValueError, KeyError, TypeError, AttributeError) as error:
        parser.exit(1, f'e2e build inputs: {error}\n')


if __name__ == '__main__':
    main()
