"""Tests for the parsers of scripts/agent-docs.py: python3 -m unittest discover -s scripts -p 'test_*.py'"""

import importlib.util
import subprocess
import sys
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("agent-docs.py")
_spec = importlib.util.spec_from_file_location("agent_docs", SCRIPT)
docs = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(docs)


class ParseSettingsTest(unittest.TestCase):
    def test_loop_and_single_includes(self):
        text = '''
            include(":app")
            include(":core:common")
            listOf("auth", "profile").forEach { feature ->
                include(":feature:$feature:api")
                include(":feature:${feature}:impl")
            }
        '''
        self.assertEqual(docs.parse_settings(text), [
            ":app", ":core:common",
            ":feature:auth:api", ":feature:auth:impl", ":feature:profile:api", ":feature:profile:impl",
        ])

    def test_include_with_several_paths(self):
        self.assertEqual(docs.parse_settings('include(":core:foo", ":core:bar")'), [":core:bar", ":core:foo"])

    def test_commented_include_is_ignored_and_urls_survive(self):
        text = '''
            maven("https://example.org/repo") // include(":old")
            /* include(":older") */
            include(":app")
        '''
        self.assertEqual(docs.parse_settings(text), [":app"])

    def test_unresolved_include_fails(self):
        with self.assertRaises(docs.ParseError):
            docs.parse_settings('val names = listOf("a")\nnames.forEach { include(":feature:$it:api") }')


class ParseFlagsTest(unittest.TestCase):
    def wrap(self, entries: str) -> str:
        return f'enum class Flag(val key: String, val defaultValue: Boolean = true) {{\n{entries}\n;\n' \
               '    companion object { fun byKey(key: String): Flag? = null }\n}'

    def test_single_line_entries(self):
        flags = docs.parse_flags(self.wrap('    /** Doc. */\n    A("a"),\n    B("b", false),\n    C("c", defaultValue = false),'))
        self.assertEqual(flags, [("A", "a", True), ("B", "b", False), ("C", "c", False)])

    def test_multiline_entry_with_trailing_comma(self):
        flags = docs.parse_flags(self.wrap('    A("a"),\n    B(\n        "b",\n        defaultValue = false,\n    ),'))
        self.assertEqual(flags, [("A", "a", True), ("B", "b", False)])

    def test_unparsable_entry_fails_instead_of_disappearing(self):
        with self.assertRaises(docs.ParseError) as e:
            docs.parse_flags(self.wrap('    A("a"),\n    B(key = "b"),'))
        self.assertIn("B", str(e.exception))


class ParseAppFeaturesTest(unittest.TestCase):
    def test_feature_loop_is_counted_other_loops_are_not(self):
        text = '''
            listOf("auth", "profile").forEach { feature ->
                implementation(project(":feature:$feature:api"))
                implementation(project(":feature:$feature:impl"))
            }
            listOf("ru", "en").forEach { locale -> println(locale) }
        '''
        self.assertEqual(docs.parse_app_features(text), {"auth", "profile"})

    def test_loop_without_impl_does_not_wire(self):
        text = 'listOf("auth").forEach { f -> implementation(project(":feature:$f:api")) }'
        self.assertEqual(docs.parse_app_features(text), set())

    def test_explicit_impl_dependencies(self):
        text = 'implementation(project(":feature:budget:impl"))\nimplementation(projects.feature.search.impl)'
        self.assertEqual(docs.parse_app_features(text), {"budget", "search"})


class RegistryParsersTest(unittest.TestCase):
    def test_flag_docs_defaults(self):
        text = '| Flag | Default | What |\n|---|---|---|\n| `a.b` | `true` | x |\n| `c` | `false` | y |\n| `d` | maybe | z |'
        self.assertEqual(docs.parse_flag_docs(text), {"a.b": True, "c": False, "d": None})

    def test_listed_features_come_from_the_features_line_only(self):
        text = "Uses `search` in prose.\n\nFeatures: `auth`, `profile`.\n"
        self.assertEqual(docs.parse_listed_features(text), {"auth", "profile"})
        self.assertIsNone(docs.parse_listed_features("no such line"))

    def test_path_match_is_whole_path(self):
        self.assertTrue(docs.mentions_path("core/api   contract", "core/api"))
        self.assertTrue(docs.mentions_path("providers/a, providers/b", "providers/a"))
        self.assertFalse(docs.mentions_path("core/api-foo", "core/api"))
        self.assertFalse(docs.mentions_path("x/core/api", "core/api"))


class RepositoryTest(unittest.TestCase):
    def test_repository_is_in_sync(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--check"], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
