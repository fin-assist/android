"""Run the immutable shared policy tests for this consumer (read-only)."""
from pathlib import Path
import json
import os
import re
import subprocess
import sys
import unittest

from common_ci import read_config, source_dir, unique_object

ROOT = Path(__file__).resolve().parent.parent


class SharedRenovate(unittest.TestCase):
    @unittest.skipIf(
        os.environ.get('PF_RENOVATE_POLICY_VIA_ACTION') == 'true',
        'Verified by the required Renovate policy tests workflow through the pinned public action',
    )
    def test_pinned_policy_and_consumer_contract(self):
        if (ROOT / '.finassist-ci.json').is_file():
            config = read_config(ROOT)
        else:
            manifest = ROOT / '.github/renovate-source.json'
            if manifest.stat().st_size > 4096:
                raise ValueError('Renovate source manifest is too large')
            config = json.loads(manifest.read_text(), object_pairs_hook=unique_object)
            if not isinstance(config, dict) or set(config) != {'revision'}:
                raise ValueError('Renovate source manifest must contain only revision')
        if not isinstance(config.get('revision'), str) or not re.fullmatch('[0-9a-f]{40}', config['revision']):
            raise ValueError('Renovate source revision must be a full lowercase SHA')
        common = source_dir(ROOT, config)
        subprocess.run([sys.executable, '-B', str(common / 'renovate/test.py'),
                        '--root', str(ROOT), '--mode', 'policy',
                        '--source-revision', config['revision']],
                       check=True, timeout=120)


if __name__ == '__main__':
    unittest.main()
