"""FIN-469: exercise pinned archive setup with an isolated provider, no network and no UI."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).with_name('install-maestro.sh')


class InstallMaestroTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.run_temp = self.root / 'runner temp'
        self.run_temp.mkdir()
        self.bin = self.root / 'provider'
        self.bin.mkdir()
        self.path_file = self.root / 'github-path'
        self.path_file.write_text('')
        self.archive = self.root / 'release.zip'
        self.env = dict(os.environ, MAESTRO_VERSION='2.11.0', RUNNER_TEMP=str(self.run_temp),
                        GITHUB_PATH=str(self.path_file), FAKE_ARCHIVE=str(self.archive), FAKE_MODE='success',
                        FAKE_VERSION_CALL=str(self.root / 'version-call'), FAKE_URL=str(self.root / 'url'))
        self.env['PATH'] = str(self.bin) + os.pathsep + self.env['PATH']
        # Model a clean runner, independent of any analytics settings on the developer's machine.
        self.env.pop('MAESTRO_CLI_NO_ANALYTICS', None)
        self.env.pop('MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED', None)
        self.tool('curl', """import os, pathlib, shutil, sys
pathlib.Path(os.environ['FAKE_URL']).write_text(sys.argv[-1])
if os.environ['FAKE_MODE'] == 'http-error':
    sys.exit(22)
destination = pathlib.Path(sys.argv[sys.argv.index('--output') + 1])
if os.environ['FAKE_MODE'] == 'empty':
    destination.write_bytes(b'')
else:
    shutil.copyfile(os.environ['FAKE_ARCHIVE'], destination)
""")
        self.tool('timeout', """import os, pathlib, sys
args = sys.argv[3:]
kind = 'extract' if pathlib.Path(args[0]).name == 'unzip' else 'version'
if os.environ['FAKE_MODE'] == kind + '-timeout':
    sys.exit(124)
os.execvp(args[0], args)
""")

    def tool(self, name, code):
        p = self.bin / name
        p.write_text('#!' + sys.executable + '\n' + code)
        p.chmod(0o755)

    def run_setup(self, mode='success', version='2.11.0'):
        self.env['FAKE_MODE'] = mode
        if mode == 'invalid-archive':
            self.archive.write_text('upstream error page')
        else:
            with zipfile.ZipFile(self.archive, 'w') as archive:
                if mode == 'no-cli':
                    archive.writestr('maestro/README', 'No CLI in this otherwise valid archive')
                else:
                    entry = zipfile.ZipInfo('maestro/bin/maestro')
                    entry.create_system = 3
                    entry.external_attr = 0o100755 << 16
                    script = '#!/bin/sh\nprintf "%s\\n" "$*" > "$FAKE_VERSION_CALL"\n'
                    script += ('if [ -z "${MAESTRO_CLI_NO_ANALYTICS:-}" ] || '
                               '[ "${MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED:-}" != true ]; then\n'
                               '  printf "Anonymous analytics enabled.\\n\\n"\nfi\n')
                    script += 'printf "%s\\n" "' + version + '"\n'
                    archive.writestr(entry, script)
        if mode == 'no-checksum':
            self.env.pop('MAESTRO_SHA256', None)
        else:
            self.env['MAESTRO_SHA256'] = ('0' * 64 if mode == 'wrong-checksum'
                                          else hashlib.sha256(self.archive.read_bytes()).hexdigest())
        return subprocess.run(['bash', str(SCRIPT)], env=self.env, capture_output=True, text=True, timeout=5)

    def assert_rejected(self, result):
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('', self.path_file.read_text(), 'failed setup must not publish a usable CLI path')

    def test_first_run_notice_is_disabled_before_exact_version_check_and_path_publication(self):
        result = self.run_setup()
        self.assertEqual(0, result.returncode, result.stderr)
        cli_dir = Path(self.path_file.read_text().strip())
        self.assertEqual(self.run_temp, cli_dir.parents[2])
        self.assertTrue(os.access(cli_dir / 'maestro', os.X_OK))
        self.assertEqual('--version\n', (self.root / 'version-call').read_text())
        self.assertTrue((self.root / 'url').read_text().endswith('/cli-2.11.0/maestro.zip'))
        self.assertEqual([], list(self.run_temp.glob('maestro-download.*')))

    def test_http_error_is_rejected(self):
        self.assert_rejected(self.run_setup('http-error'))

    def test_empty_successful_download_is_rejected(self):
        self.assert_rejected(self.run_setup('empty'))

    def test_successful_archive_without_cli_is_rejected(self):
        self.assert_rejected(self.run_setup('no-cli'))

    def test_wrong_version_is_rejected(self):
        self.assert_rejected(self.run_setup(version='2.12.1'))

    def test_missing_checksum_is_rejected(self):
        self.assert_rejected(self.run_setup('no-checksum'))

    def test_checksum_mismatch_is_rejected_before_execution(self):
        self.assert_rejected(self.run_setup('wrong-checksum'))
        self.assertFalse((self.root / 'version-call').exists())

    def test_invalid_archive_is_not_a_successful_install(self):
        self.assert_rejected(self.run_setup('invalid-archive'))

    def test_extraction_timeout_is_propagated(self):
        result = self.run_setup('extract-timeout')
        self.assert_rejected(result)
        self.assertEqual(124, result.returncode)

    def test_version_check_timeout_is_propagated(self):
        result = self.run_setup('version-timeout')
        self.assert_rejected(result)
        self.assertEqual(124, result.returncode)

    def test_verified_cache_is_rehashed_and_used_without_a_second_download(self):
        first = self.run_setup()
        self.assertEqual(0, first.returncode, first.stderr)
        (self.root / 'url').unlink()
        self.path_file.write_text('')
        # The same pinned archive remains usable even when the upstream download is unavailable.
        self.env['FAKE_MODE'] = 'http-error'
        second = subprocess.run(['bash', str(SCRIPT)], env=self.env, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertFalse((self.root / 'url').exists())
        self.assertTrue(Path(self.path_file.read_text().strip(), 'maestro').is_file())

    def test_corrupt_restored_cache_falls_back_to_download_and_is_replaced_only_after_validation(self):
        first = self.run_setup()
        self.assertEqual(0, first.returncode, first.stderr)
        cached = next((self.run_temp / 'maestro-package').glob('*.zip'))
        cached.write_text('corrupt restored bytes')
        (self.root / 'url').unlink()
        self.path_file.write_text('')
        second = subprocess.run(['bash', str(SCRIPT)], env=self.env, capture_output=True, text=True, timeout=5)
        self.assertEqual(0, second.returncode, second.stderr)
        self.assertTrue((self.root / 'url').exists())
        self.assertEqual(self.env['MAESTRO_SHA256'], hashlib.sha256(cached.read_bytes()).hexdigest())

    def test_bad_restored_cache_and_failed_download_do_not_publish_a_cli(self):
        self.assertEqual(0, self.run_setup().returncode)
        cached = next((self.run_temp / 'maestro-package').glob('*.zip'))
        cached.write_text('corrupt restored bytes')
        self.path_file.write_text('')
        self.env['FAKE_MODE'] = 'http-error'
        result = subprocess.run(['bash', str(SCRIPT)], env=self.env, capture_output=True, text=True, timeout=5)
        self.assert_rejected(result)
        self.assertFalse(cached.exists())


if __name__ == '__main__':
    unittest.main()
