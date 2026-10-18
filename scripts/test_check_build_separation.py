import base64
import datetime
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


HTTP_URL = "http://10.0.2.2:8080/"


def local_debug(manifest_xml: str, nsc_xml: str, base_url: str = HTTP_URL) -> list[str]:
    return module.local_debug_problems(manifest_xml, nsc_xml, base_url)


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
        self.assertEqual([], local_debug(LOCAL_MANIFEST, nsc(ONE_HOST)))

    def test_http_backend_without_exception_fails(self):
        self.assertEqual(
            ["no cleartext exception for the http backend host 10.0.2.2: the build cannot reach it"],
            local_debug(LOCAL_MANIFEST, nsc("")),
        )

    def test_http_backend_with_exception_that_is_not_cleartext_fails(self):
        body = '<domain-config cleartextTrafficPermitted="false"><domain>10.0.2.2</domain></domain-config>'
        self.assertEqual(1, len(local_debug(LOCAL_MANIFEST, nsc(body))))

    def test_manifest_without_config_fails(self):
        self.assertEqual(1, len(local_debug(manifest(), nsc(ONE_HOST))))

    def test_test_hook_fails(self):
        xml = manifest(
            'android:networkSecurityConfig="@xml/network_security_config"',
            '<activity android:name="ru.finassist.pf.e2e.E2eDocumentPickerActivity"/>',
        )
        self.assertEqual(1, len(local_debug(xml, nsc(ONE_HOST))))

    def test_base_config_allowing_cleartext_fails(self):
        problems = local_debug(LOCAL_MANIFEST, nsc(ONE_HOST, '<base-config cleartextTrafficPermitted="true" />'))
        self.assertEqual(["base-config does not forbid cleartext"], problems)
        self.assertEqual(1, len(local_debug(LOCAL_MANIFEST, nsc(ONE_HOST, ""))))

    def test_subdomains_fail(self):
        body = '<domain-config cleartextTrafficPermitted="true"><domain includeSubdomains="true">10.0.2.2</domain></domain-config>'
        self.assertEqual(["cleartext to subdomains of 10.0.2.2"], local_debug(LOCAL_MANIFEST, nsc(body)))

    def test_second_host_fails(self):
        body = (
            '<domain-config cleartextTrafficPermitted="true"><domain>10.0.2.2</domain><domain>localhost</domain>'
            "</domain-config>"
        )
        problems = local_debug(LOCAL_MANIFEST, nsc(body))
        self.assertIn("cleartext to more than one host: 10.0.2.2, localhost", problems)
        self.assertIn("cleartext to localhost, not the backend host 10.0.2.2", problems)

    def test_debug_overrides_and_trust_anchors_fail(self):
        body = "<debug-overrides><trust-anchors><certificates src=\"user\"/></trust-anchors></debug-overrides>"
        self.assertEqual(1, len(local_debug(LOCAL_MANIFEST, nsc(ONE_HOST + body))))


    def test_other_host_than_the_backend_fails(self):
        body = '<domain-config cleartextTrafficPermitted="true"><domain>unrelated.example</domain></domain-config>'
        self.assertEqual(
            ["cleartext to unrelated.example, not the backend host 10.0.2.2"], local_debug(LOCAL_MANIFEST, nsc(body))
        )

    def test_backend_host_is_compared_case_insensitively(self):
        body = '<domain-config cleartextTrafficPermitted="true"><domain>Dev.Local</domain></domain-config>'
        self.assertEqual([], local_debug(LOCAL_MANIFEST, nsc(body), "http://dev.local:8080/"))

    def test_lan_backend_passes_with_its_host(self):
        body = '<domain-config cleartextTrafficPermitted="true"><domain>192.168.1.20</domain></domain-config>'
        self.assertEqual([], local_debug(LOCAL_MANIFEST, nsc(body), "http://192.168.1.20:9000/"))
        self.assertEqual(1, len(local_debug(LOCAL_MANIFEST, nsc(ONE_HOST), "http://192.168.1.20:9000/")))

    def test_https_backend_with_cleartext_exception_fails(self):
        problems = local_debug(LOCAL_MANIFEST, nsc(ONE_HOST), "https://dev.example.ru/")
        self.assertEqual(["cleartext to 10.0.2.2 although the backend https://dev.example.ru/ is https"], problems)

    def test_https_backend_without_exception_passes(self):
        self.assertEqual([], local_debug(LOCAL_MANIFEST, nsc(""), "https://dev.example.ru/"))

    def test_base_url_that_is_not_http_fails(self):
        self.assertEqual(1, len(local_debug(LOCAL_MANIFEST, nsc(""), "")))


# Test values only: base64 of 32 fixed bytes, not keys of any certificate.
PIN_A = base64.b64encode(bytes([1] * 32)).decode()
PIN_B = base64.b64encode(bytes([2] * 32)).decode()
PROD_URL = "https://api.example.ru/"
TODAY = datetime.date(2026, 10, 18)


def pinned_nsc(
    pins=(PIN_A, PIN_B),
    expiration=' expiration="2027-04-01"',
    domain='<domain includeSubdomains="false">api.example.ru</domain>',
    domain_attrs=' cleartextTrafficPermitted="false"',
    extra="",
    base='<base-config cleartextTrafficPermitted="false" />',
) -> str:
    pin_xml = "".join(f'<pin digest="SHA-256">{p}</pin>' for p in pins)
    return nsc(f"<domain-config{domain_attrs}>{domain}<pin-set{expiration}>{pin_xml}</pin-set></domain-config>{extra}", base)


def pinned(nsc_xml: str, manifest_xml: str = LOCAL_MANIFEST, base_url: str = PROD_URL) -> list[str]:
    return module.pinned_release_problems(manifest_xml, nsc_xml, base_url, TODAY)


class PinnedReleaseTest(unittest.TestCase):
    def test_generated_shape_passes(self):
        self.assertEqual([], pinned(pinned_nsc()))

    def test_host_is_compared_case_insensitively(self):
        self.assertEqual([], pinned(pinned_nsc(domain="<domain>API.Example.ru</domain>")))

    def test_manifest_without_config_fails(self):
        self.assertEqual(["the manifest does not use @xml/network_security_config"], pinned(pinned_nsc(), manifest()))

    def test_release_rules_still_apply(self):
        xml = manifest(
            'android:usesCleartextTraffic="false" android:networkSecurityConfig="@xml/network_security_config" '
            'android:debuggable="true"',
            '<activity android:name="ru.finassist.pf.e2e.E2eControlActivity"/>',
        )
        problems = pinned(pinned_nsc(), xml)
        self.assertIn("android:debuggable is true", problems)
        self.assertTrue(any(p.startswith("test hook") for p in problems))
        self.assertEqual(1, len(pinned(pinned_nsc(), LOCAL_MANIFEST.replace('usesCleartextTraffic="false"', "x=\"y\""))))

    def test_one_pin_fails(self):
        self.assertEqual(
            ["1 distinct pin(s), at least 2 required (the current key and a backup)"], pinned(pinned_nsc(pins=(PIN_A,)))
        )

    def test_repeated_pin_is_not_a_backup(self):
        self.assertEqual(1, len(pinned(pinned_nsc(pins=(PIN_A, PIN_A)))))

    def test_two_spellings_of_one_digest_are_not_a_backup(self):
        canonical, same_bytes = "A" * 43 + "=", "A" * 42 + "B="
        self.assertEqual(base64.b64decode(canonical), base64.b64decode(same_bytes))
        self.assertEqual(
            ["1 distinct pin(s), at least 2 required (the current key and a backup)"],
            pinned(pinned_nsc(pins=(canonical, same_bytes))),
        )

    def test_malformed_pin_fails(self):
        for bad in (PIN_A[:-2], "not-base64!" * 4, base64.b64encode(bytes(33)).decode(), "A" * 44):
            self.assertEqual([f"pin {bad!r} is not a base64 SHA-256 digest"], pinned(pinned_nsc(pins=(bad, PIN_A, PIN_B))))

    def test_other_digest_fails(self):
        body = (
            '<domain-config><domain>api.example.ru</domain><pin-set expiration="2027-04-01">'
            f'<pin digest="SHA-1">{PIN_A}</pin><pin digest="SHA-256">{PIN_B}</pin></pin-set></domain-config>'
        )
        problems = pinned(nsc(body))
        self.assertIn(f"pin {PIN_A!r} is not SHA-256", problems)

    def test_missing_expiration_fails(self):
        self.assertEqual(
            ["pin-set has no expiration: a stale pin would lock the app out for good"], pinned(pinned_nsc(expiration=""))
        )

    def test_expired_or_today_fails(self):
        self.assertEqual(1, len(pinned(pinned_nsc(expiration=' expiration="2026-10-18"'))))
        self.assertEqual(1, len(pinned(pinned_nsc(expiration=' expiration="2025-01-01"'))))
        self.assertEqual([], pinned(pinned_nsc(expiration=' expiration="2026-10-19"')))

    def test_expiration_that_is_not_a_date_fails(self):
        self.assertEqual(1, len(pinned(pinned_nsc(expiration=' expiration="soon"'))))

    def test_no_pin_set_fails(self):
        body = '<domain-config><domain>api.example.ru</domain></domain-config>'
        self.assertEqual(["no pin-set"], pinned(nsc(body)))

    def test_cleartext_fails(self):
        self.assertEqual(
            ["cleartext allowed in the pinned domain-config"],
            pinned(pinned_nsc(domain_attrs=' cleartextTrafficPermitted="true"')),
        )
        self.assertEqual(
            ["base-config does not forbid cleartext"],
            pinned(pinned_nsc(base='<base-config cleartextTrafficPermitted="true" />')),
        )

    def test_user_cas_and_debug_overrides_fail(self):
        extra = '<debug-overrides><trust-anchors><certificates src="user"/></trust-anchors></debug-overrides>'
        self.assertEqual(["custom trust anchors or debug overrides"], pinned(pinned_nsc(extra=extra)))
        anchors = '<base-config cleartextTrafficPermitted="false"><trust-anchors><certificates src="user"/></trust-anchors></base-config>'
        self.assertEqual(["custom trust anchors or debug overrides"], pinned(pinned_nsc(base=anchors)))

    def test_other_host_or_subdomains_fail(self):
        self.assertEqual(
            ["pinned hosts other.example, expected only the backend host api.example.ru"],
            pinned(pinned_nsc(domain="<domain>other.example</domain>")),
        )
        self.assertEqual(
            ["pins cover subdomains"],
            pinned(pinned_nsc(domain='<domain includeSubdomains="true">api.example.ru</domain>')),
        )
        two = "<domain>api.example.ru</domain><domain>other.example</domain>"
        self.assertEqual(1, len(pinned(pinned_nsc(domain=two))))

    def test_second_domain_config_fails(self):
        extra = '<domain-config cleartextTrafficPermitted="true"><domain>10.0.2.2</domain></domain-config>'
        self.assertEqual(["expected one domain-config for api.example.ru, found 2"], pinned(pinned_nsc(extra=extra)))

    def test_http_backend_fails(self):
        self.assertEqual(1, len(pinned(pinned_nsc(), base_url="http://api.example.ru/")))


class ApiBaseUrlTest(unittest.TestCase):
    def test_reads_the_field(self):
        java = 'public final class BuildConfig {\n  public static final String API_BASE_URL = "http://10.0.2.2:8080/";\n}'
        self.assertEqual("http://10.0.2.2:8080/", module.api_base_url(java))

    def test_missing_field_fails(self):
        with self.assertRaises(ValueError):
            module.api_base_url("public final class BuildConfig {}")


class MainTest(unittest.TestCase):
    def test_usage(self):
        self.assertEqual(2, module.main([]))
        self.assertEqual(2, module.main(["local-debug", "only-one"]))
        self.assertEqual(2, module.main(["local-debug", "manifest", "config"]))
        self.assertEqual(2, module.main(["pinned-release", "manifest", "config"]))


if __name__ == "__main__":
    unittest.main()
