"""Android FIN-184 policy must follow the reviewed baseline, not copied constants."""
import json
from pathlib import Path
import unittest
from renovate_policy import compose_stack_rule
from renovate_policy_data import COMPOSE_PACKAGES, validate_policy

class ComposeBaselineTests(unittest.TestCase):
    def test_generated_stack_covers_the_reviewed_baseline_matchers(self):
        baseline=json.loads((Path(__file__).resolve().parents[1]/'renovate-base.json').read_text())
        groups=[rule for rule in baseline['packageRules'] if rule.get('groupName')=='androidx compose stack']
        self.assertEqual(len(groups),1)
        self.assertEqual(groups[0]['matchPackageNames'],COMPOSE_PACKAGES)
        rule=compose_stack_rule(123)
        self.assertEqual(rule['matchPackageNames'],groups[0]['matchPackageNames'])
        self.assertEqual(rule['separateMajorMinor'],groups[0]['separateMajorMinor'])
        validate_policy({'baseBranchPatterns':['main'],'packageRules':[rule]})

if __name__ == '__main__':
    unittest.main()
