import contextlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("check_app_version", Path(__file__).with_name("check-app-version.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def manifest(version):
    return f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" android:versionName="{version}"/>'


def config(version, client):
    return (f'public static final String VERSION_NAME = "{version}";\n'
            f'public static final String CLIENT_VERSION_NAME = "{client}";')


class AppVersionTest(unittest.TestCase):
    def test_source_requires_one_literal_semver(self):
        self.assertEqual("0.2.14", module.app_version('// release/0.2.8\nval appVersionName = "0.2.14"\n'))
        for source in ('', 'val appVersionName = other', 'val appVersionName = "0.02.14"',
                       'val appVersionName = "0.2.14"\nval appVersionName = "0.2.14"'):
            with self.subTest(source=source), self.assertRaises(ValueError):
                module.app_version(source)

    def test_previous_stale_version_fails_release(self):
        with self.assertRaisesRegex(ValueError, "requires appVersionName 0.2.14, found 0.2.8"):
            module.check_branches("0.2.8", ["release/0.2.14"])

    def test_release_matches_and_ordinary_branches_do_not_set_version(self):
        module.check_branches("0.2.14", ["main", "fix/fin-377-app-version", "", "220/merge"])
        module.check_branches("0.2.14", ["release/0.2.14"])

    def test_malformed_or_conflicting_release_context_fails(self):
        for branches in (["release/next"], ["release/0.2.14", "release/0.2.15"]):
            with self.subTest(branches=branches), self.assertRaises(ValueError):
                module.check_branches("0.2.14", branches)

    def test_generated_versions_keep_flavor_suffix_out_of_client_header(self):
        for suffix in ("", "-mock", "-local"):
            with self.subTest(suffix=suffix):
                module.check_artifacts("0.2.14", suffix, manifest("0.2.14" + suffix),
                                       config("0.2.14" + suffix, "0.2.14"))

    def test_stale_manifest_buildconfig_or_suffixed_client_fails(self):
        for xml, java in ((manifest("0.2.8"), config("0.2.14", "0.2.14")),
                          (manifest("0.2.14"), config("0.2.8", "0.2.14")),
                          (manifest("0.2.14"), config("0.2.14", "0.2.14-mock")),
                          (manifest("0.2.14"), ''),
                          ('<manifest/>', config("0.2.14", "0.2.14"))):
            with self.subTest(xml=xml, java=java), self.assertRaises(ValueError):
                module.check_artifacts("0.2.14", "", xml, java)

    def test_cli_checks_each_github_release_context(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "build.gradle.kts"
            source.write_text('val appVersionName = "0.2.8"\n')
            for key in ("GITHUB_BASE_REF", "GITHUB_HEAD_REF", "GITHUB_REF_NAME"):
                with self.subTest(key=key), patch.dict(os.environ, {key: "release/0.2.14"}, clear=True):
                    with contextlib.redirect_stderr(io.StringIO()):
                        self.assertEqual(1, module.main(["--source", str(source)]))

    def test_cli_success_and_missing_or_invalid_artifacts(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {}, clear=True):
            source = Path(directory) / "build.gradle.kts"
            source.write_text('val appVersionName = "0.2.14"\n')
            xml, java = Path(directory) / "manifest.xml", Path(directory) / "BuildConfig.java"
            xml.write_text(manifest("0.2.14-mock"))
            java.write_text(config("0.2.14-mock", "0.2.14"))
            args = ["--source", str(source), "--manifest", str(xml), "--build-config", str(java), "--suffix=-mock"]
            with contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, module.main(args))
            xml.write_text("<invalid")
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, module.main(args))
                xml.unlink()
                self.assertEqual(1, module.main(args))
                with self.assertRaises(SystemExit) as error:
                    module.main(["--manifest", str(xml)])
                self.assertEqual(2, error.exception.code)


if __name__ == "__main__":
    unittest.main()
