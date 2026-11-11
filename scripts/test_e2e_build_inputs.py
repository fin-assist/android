import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('e2e_build_inputs', Path(__file__).with_name('e2e-build-inputs.py'))
inputs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inputs)


class SharedBuildInputsTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.identity = dict(zip(inputs.IDENTITY, ('a' * 40, '42', '2')))
        self.native_class = inputs.NATIVE_CLASSES / 'dirs/example/NativeTest.class'
        for relative in [*inputs.APK_FILES.values(), *inputs.CLASS_FILES.values(), inputs.NATIVE_R, self.native_class]:
            self.write(relative, str(relative))
        for directory, apk in inputs.APK_DIRS.values():
            self.write(directory / 'output-metadata.json', json.dumps({'elements': [{'outputFile': apk}]}))
        inputs.publish(self.root, self.identity)

    def write(self, path, content):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
        return target

    def test_restores_inputs_from_a_successful_earlier_attempt_on_same_run(self):
        later = self.identity | {'GITHUB_RUN_ATTEMPT': '3'}
        for path in [*inputs.APK_FILES.values(), *inputs.CLASS_FILES.values(), inputs.NATIVE_R, self.native_class]:
            (self.root / path).unlink()
        inputs.consume(self.root, later, 'apks')
        inputs.consume(self.root, later, 'classes')
        inputs.consume(self.root, later, 'apks', check_only=True)
        self.assertEqual((self.root / self.native_class).read_text(), str(self.native_class))
        self.assertTrue((self.root / inputs.CLASS_FILES['e2e-classes.zip']).is_file())

    def test_refuses_wrong_source_run_future_attempt_and_corrupt_download(self):
        for key, value in zip(inputs.IDENTITY, ('b' * 40, '43', '1')):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'source, workflow run, or attempt mismatch'):
                inputs.consume(self.root, self.identity | {key: value}, 'apks')
        self.write(inputs.INPUTS / 'apks/native/dirs/example/NativeTest.class', 'tampered')
        with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
            inputs.consume(self.root, self.identity, 'apks')

    def test_refuses_repackaged_apk_and_paths_outside_the_expected_payload(self):
        apk = inputs.APK_FILES['app/app-mock-e2e.apk']
        self.write(apk, 'rebuilt')
        with self.assertRaisesRegex(ValueError, 'installed build input changed'):
            inputs.consume(self.root, self.identity, 'apks', check_only=True)
        manifest = self.root / inputs.INPUTS / 'apks/manifest.json'
        data = json.loads(manifest.read_text())
        data['files']['native/dirs/../../outside.class'] = '0' * 64
        manifest.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, 'manifest inventory'):
            inputs.consume(self.root, self.identity, 'apks')
