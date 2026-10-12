import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("check_tracer_off", pathlib.Path(__file__).with_name("check-tracer-off.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

HEAD = '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>'
TAIL = "</application></manifest>"


def manifest(body: str) -> str:
    return HEAD + body + TAIL


class EnabledTracerComponentsTest(unittest.TestCase):
    def test_disabled_provider_passes(self):
        xml = manifest('<provider android:name="ru.ok.tracer.startup.InitializationProvider" android:enabled="false"/>')
        self.assertEqual([], module.enabled_tracer_components(xml))

    def test_provider_without_enabled_attribute_is_enabled(self):
        xml = manifest('<provider android:name="ru.ok.tracer.startup.InitializationProvider"/>')
        self.assertEqual(["<provider> ru.ok.tracer.startup.InitializationProvider"], module.enabled_tracer_components(xml))

    def test_any_component_kind_counts(self):
        xml = manifest(
            '<service android:name="ru.ok.tracer.upload.UploadService"/>'
            '<receiver android:name="ru.ok.tracer.Boot" android:enabled="true"/>'
        )
        self.assertEqual(2, len(module.enabled_tracer_components(xml)))

    def test_other_libraries_and_meta_data_are_ignored(self):
        xml = manifest(
            '<provider android:name="androidx.startup.InitializationProvider"/>'
            '<meta-data android:name="ru.ok.tracer.startup.Initializer@x" android:value="ru.ok.tracer.TracerInitializer"/>'
        )
        self.assertEqual([], module.enabled_tracer_components(xml))

    def test_manifest_without_application(self):
        self.assertEqual([], module.enabled_tracer_components('<manifest/>'))


if __name__ == "__main__":
    unittest.main()
