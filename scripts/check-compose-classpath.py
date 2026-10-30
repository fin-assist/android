#!/usr/bin/env python3
"""Reject runtime drift from the isolated Compose BOM/navigation/lifecycle baseline."""
import argparse
import json
from pathlib import Path
import sys

CONFIGURATIONS = ('mockE2eRuntimeClasspath', 'stageDebugRuntimeClasspath', 'stageReleaseRuntimeClasspath', 'prodReleaseRuntimeClasspath')
MODULES = ('androidx.compose.ui:ui', 'androidx.compose.foundation:foundation',
           'androidx.lifecycle:lifecycle-runtime')


def check(report):
    errors = []
    for configuration in CONFIGURATIONS:
        entry = report.get(configuration, {})
        for module in MODULES:
            expected = entry.get('baseline', {}).get(module)
            actual = entry.get('resolved', {}).get(module)
            if not isinstance(expected, str) or not expected:
                errors.append(f'{configuration}: missing baseline for {module}')
                continue
            if not isinstance(actual, str) or not actual:
                errors.append(f'{configuration}: missing resolved {module} (expected {expected})')
                continue
            if actual != expected:
                errors.append(f'{configuration}: {module} resolved {actual}, expected {expected}')
            # AndroidX multiplatform wrapper and the Android implementation must both stay aligned.
            android = module + '-android'
            android_version = entry.get('resolved', {}).get(android)
            if android_version is not None and android_version != expected:
                errors.append(f'{configuration}: {android} resolved {android_version}, expected {expected}')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report', type=Path)
    args = parser.parse_args()
    try:
        report = json.loads(args.report.read_text())
        errors = check(report)
    except (OSError, ValueError, TypeError, AttributeError) as error:
        print(f'Invalid Compose classpath report: {error}', file=sys.stderr)
        return 1
    if errors:
        print('Compose runtime classpath drift (FIN-185):', file=sys.stderr)
        for error in errors:
            print(f'  {error}', file=sys.stderr)
        print('Inspect with :app:dependencyInsight --configuration <configuration> --dependency <module>.\n'
              'Update the Compose/AndroidX stack together; do not force a transitive upgrade past its baseline.',
              file=sys.stderr)
        return 1
    print('Compose UI, foundation and lifecycle match the isolated baseline in every checked runtime classpath.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
