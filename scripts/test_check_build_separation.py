import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location(
    "check_build_separation", pathlib.Path(__file__).with_name("check-build-separation.py")
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'


def manifest(application_attrs: str = 'android:usesCleartextTraffic="false"', body: str = "") -> str:
    return f'<manifest {NS} package="ru.finassist.pf"><application {application_attrs}>{body}</application></manifest>'


LOCAL_MANIFEST = manifest(
    'android:usesCleartextTraffic="false" android:networkSecurityConfig="@xml/network_security_config"'
)


def nsc(body: str, base: str = '<base-config cleartextTrafficPermitted="false" />') -> str:
    return f"<network-security-config>{base}{body}</network-security-config>"


ONE_HOST = '<domain-config cleartextTrafficPermitted="true"><domain includeSubdomains="false">10.0.2.2</domain></domain-config>'


class ReleaseTest(unittest.TestCase):
    def test_clean_release_passes(self):
        self.assertEqual([], module.release_problems(manifest()))

    def test_e2e_activity_and_control_link_fail(self):
        xml = manifest(body=(
            '<activity android:name="ru.finassist.pf.e2e.E2eControlActivity" android:exported="true">'
            '<intent-filter><data android:scheme="pfe2e" android:host="config"/></intent-filter></activity>'
        ))
        problems = module.release_problems(xml)
        self.assertEqual(2, len(problems))
        self.assertTrue(all(p.startswith("test hook") for p in problems))

    def test_document_picker_override_fails(self):
        xml = manifest(body='<meta-data android:name="ru.finassist.pf.DOCUMENT_PICKER_OVERRIDE" android:value="x"/>')
        self.assertEqual(1, len(module.release_problems(xml)))

    def test_mock_package_fails(self):
        xml = f'<manifest {NS} package="ru.finassist.pf.mock"><application android:usesCleartextTraffic="false"/></manifest>'
        self.assertEqual(1, len(module.release_problems(xml)))

    def test_cleartext_not_explicitly_off_fails(self):
        self.assertEqual(1, len(module.release_problems(manifest(""))))
        self.assertEqual(1, len(module.release_problems(manifest('android:usesCleartextTraffic="true"'))))

    def test_network_security_config_fails(self):
        self.assertEqual(1, len(module.release_problems(LOCAL_MANIFEST)))

    def test_debuggable_fails(self):
        self.assertEqual(
            ["android:debuggable is true"],
            module.release_problems(manifest('android:usesCleartextTraffic="false" android:debuggable="true"')),
        )

    def test_no_application(self):
        self.assertEqual(["no <application>"], module.release_problems(f"<manifest {NS}/>"))


class LocalDebugTest(unittest.TestCase):
    def test_one_cleartext_host_passes(self):
        self.assertEqual([], module.local_debug_problems(LOCAL_MANIFEST, nsc(ONE_HOST)))

    def test_https_config_without_exception_passes(self):
        self.assertEqual([], module.local_debug_problems(LOCAL_MANIFEST, nsc("")))

    def test_manifest_without_config_fails(self):
        self.assertEqual(1, len(module.local_debug_problems(manifest(), nsc(ONE_HOST))))

    def test_test_hook_fails(self):
        xml = manifest(
            'android:networkSecurityConfig="@xml/network_security_config"',
            '<activity android:name="ru.finassist.pf.e2e.E2eDocumentPickerActivity"/>',
        )
        self.assertEqual(1, len(module.local_debug_problems(xml, nsc(ONE_HOST))))

    def test_base_config_allowing_cleartext_fails(self):
        problems = module.local_debug_problems(LOCAL_MANIFEST, nsc("", '<base-config cleartextTrafficPermitted="true" />'))
        self.assertEqual(["base-config does not forbid cleartext"], problems)
        self.assertEqual(1, len(module.local_debug_problems(LOCAL_MANIFEST, nsc("", ""))))

    def test_subdomains_fail(self):
        body = '<domain-config cleartextTrafficPermitted="true"><domain includeSubdomains="true">example.ru</domain></domain-config>'
        self.assertEqual(1, len(module.local_debug_problems(LOCAL_MANIFEST, nsc(body))))

    def test_second_host_fails(self):
        body = (
            '<domain-config cleartextTrafficPermitted="true"><domain>10.0.2.2</domain><domain>localhost</domain>'
            "</domain-config>"
        )
        self.assertEqual(1, len(module.local_debug_problems(LOCAL_MANIFEST, nsc(body))))

    def test_debug_overrides_and_trust_anchors_fail(self):
        body = "<debug-overrides><trust-anchors><certificates src=\"user\"/></trust-anchors></debug-overrides>"
        self.assertEqual(1, len(module.local_debug_problems(LOCAL_MANIFEST, nsc(body))))


class MainTest(unittest.TestCase):
    def test_usage(self):
        self.assertEqual(2, module.main([]))
        self.assertEqual(2, module.main(["local-debug", "only-one"]))


if __name__ == "__main__":
    unittest.main()
