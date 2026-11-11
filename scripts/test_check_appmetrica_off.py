import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location("check_appmetrica_off", pathlib.Path(__file__).with_name("check-appmetrica-off.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

HEAD = '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>'
TAIL = "</application></manifest>"


def manifest(body: str) -> str:
    return HEAD + body + TAIL


class EnabledAppMetricaComponentsTest(unittest.TestCase):
    def test_disabled_provider_passes(self):
        xml = manifest('<provider android:name="io.appmetrica.analytics.internal.PreloadInfoContentProvider" android:enabled="false"/>')
        self.assertEqual([], module.appmetrica_components(xml))
        self.assertEqual(1, len(module.appmetrica_components(xml, include_disabled=True)))

    def test_provider_without_enabled_attribute_is_enabled(self):
        xml = manifest('<provider android:name="io.appmetrica.analytics.internal.PreloadInfoContentProvider"/>')
        self.assertEqual(["<provider> io.appmetrica.analytics.internal.PreloadInfoContentProvider"], module.appmetrica_components(xml))

    def test_any_component_kind_counts(self):
        xml = manifest(
            '<service android:name="io.appmetrica.analytics.upload.UploadService"/>'
            '<receiver android:name="io.appmetrica.analytics.Boot" android:enabled="true"/>'
        )
        self.assertEqual(2, len(module.appmetrica_components(xml)))

    def test_other_libraries_and_meta_data_are_ignored(self):
        xml = manifest(
            '<provider android:name="androidx.startup.InitializationProvider"/>'
            '<meta-data android:name="io.appmetrica.analytics.startup.Initializer@x" android:value="io.appmetrica.analytics.AppMetricaInitializer"/>'
        )
        self.assertEqual([], module.appmetrica_components(xml))

    def test_manifest_without_application(self):
        self.assertEqual([], module.appmetrica_components('<manifest/>'))


if __name__ == "__main__":
    unittest.main()
