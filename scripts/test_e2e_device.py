import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('e2e_device', Path(__file__).with_name('e2e-device.py'))
device = importlib.util.module_from_spec(spec)
spec.loader.exec_module(device)


class PreflightDiagnosticTest(unittest.TestCase):
    package = 'ru.finassist.pf.mock.e2e'

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.out = Path(directory.name)

    def test_anr_dialog_blocks_ready_phone_screen_and_other_packages_cannot_supply_its_tag(self):
        xml = self.out / 'sample.xml'
        xml.write_text(f"""<hierarchy>
            <node package="{self.package}" resource-id="auth.phone"/>
            <node package="android" text="Pixel Launcher isn't responding"/>
            </hierarchy>""", encoding='utf-8')
        self.assertEqual('anr_dialog', device.hierarchy_status(xml, self.package))
        xml.write_text('<hierarchy><node package="other.app" resource-id="auth.phone"/></hierarchy>', encoding='utf-8')
        self.assertEqual('app_not_foreground', device.hierarchy_status(xml, self.package))
        xml.write_text('', encoding='utf-8')
        self.assertEqual('invalid_xml', device.hierarchy_status(xml, self.package))

    def test_timeout_preserves_bounded_diagnostics_and_never_passes_preflight(self):
        for failed in ('dump', 'pull'):
            with self.subTest(command=failed):
                def adb(args, *, stdout, timeout, **kwargs):
                    if failed in args:
                        stdout.write(b'partial transport output\n' + b'x' * device.LOG_LIMIT)
                        raise subprocess.TimeoutExpired(args, timeout)
                    return subprocess.CompletedProcess(args, 0)

                with patch.object(device.subprocess, 'run', side_effect=adb):
                    self.assertEqual(1, device.preflight(self.out, self.package, 1))
                prefix = self.out / 'preflight/attempt-1'
                result = json.loads(prefix.with_suffix('.json').read_text())
                self.assertEqual(f'{failed}_failed', result['result'])
                self.assertIsNone(result[failed]['exit_code'])
                self.assertTrue(result[failed]['timed_out'])
                self.assertTrue(result[failed]['truncated'])
                log = prefix.with_suffix(f'.{failed}.log').read_bytes()
                self.assertEqual(device.LOG_LIMIT, len(log))
                self.assertTrue(log.startswith(b'partial transport output'))
