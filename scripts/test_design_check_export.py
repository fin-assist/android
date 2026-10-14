"""Tests for design-check/export.py: python3 -m unittest discover -s scripts -p 'test_*.py'"""

import contextlib
import importlib.util
import io
import json
import os
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

SCRIPT = Path(__file__).resolve().parent.parent / "design-check" / "export.py"
_spec = importlib.util.spec_from_file_location("design_check_export", SCRIPT)
export = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(export)


def frame(node, name, w=390, h=844, children=()):
    return {"id": node, "name": name, "type": "FRAME", "children": list(children),
            "absoluteBoundingBox": {"x": 0, "y": 0, "width": w, "height": h}}


def section(node, name, children):
    return {"id": node, "name": name, "type": "SECTION", "children": children}


def page(node, name, children):
    return {"id": node, "name": name, "type": "CANVAS", "children": children}


class ArtboardsTest(unittest.TestCase):
    def test_frames_in_sections_and_on_the_page(self):
        document = {"children": [
            page("4:87", "MVP", [
                section("4:89", "Основные экраны", [frame("5:2", "Main"), frame("5:657", "Analytics", h=2848.0)]),
                frame("9:1", "Phone"),
            ]),
        ]}
        self.assertEqual(export.artboards(document), [
            {"name": "Main", "page": "MVP", "section": "Основные экраны", "node": "5:2", "w": 390, "h": 844},
            {"name": "Analytics", "page": "MVP", "section": "Основные экраны", "node": "5:657", "w": 390, "h": 2848},
            {"name": "Phone", "page": "MVP", "section": None, "node": "9:1", "w": 390, "h": 844},
        ])

    def test_nested_sections_keep_the_inner_name(self):
        document = {"children": [page("1:1", "MVP", [
            section("1:2", "Outer", [section("1:3", "Inner", [frame("1:4", "Chat")])]),
        ])]}
        self.assertEqual([(b["name"], b["section"]) for b in export.artboards(document)], [("Chat", "Inner")])

    def test_only_snapshot_names_are_artboards(self):
        document = {"children": [page("0:1", "Дизайн-система", [
            frame("4:92", "Цвет · светлая", w=688),
            frame("10:333", "Код · позвонить ещё раз"),
            frame("1:1", "tabhead"),
            frame("1:2", "Main Dark"),
            {"id": "72:153", "name": "TabBar", "type": "COMPONENT", "children": []},
            frame("1:3", "SearchPeriod2"),
        ])]}
        self.assertEqual([b["name"] for b in export.artboards(document)], ["SearchPeriod2"])

    def test_frames_inside_a_screen_are_not_artboards(self):
        document = {"children": [page("1:1", "MVP", [frame("1:2", "Main", children=[frame("1:3", "Header")])])]}
        self.assertEqual([b["name"] for b in export.artboards(document)], ["Main"])

    def test_frame_without_bounds_has_zero_size(self):
        document = {"children": [page("1:1", "MVP", [{"id": "1:2", "name": "Main", "type": "FRAME"}])]}
        self.assertEqual((export.artboards(document)[0]["w"], export.artboards(document)[0]["h"]), (0, 0))


class DuplicatesTest(unittest.TestCase):
    def test_reports_every_place_of_a_repeated_name(self):
        boards = [
            {"name": "Main", "page": "MVP", "section": "Основные экраны", "node": "5:2"},
            {"name": "Main", "page": "Архив", "section": None, "node": "10:1"},
            {"name": "Chat", "page": "MVP", "section": None, "node": "6:1"},
        ]
        self.assertEqual(export.duplicates(boards), {
            "Main": ["MVP / Основные экраны (5:2)", "Архив / — (10:1)"],
        })

    def test_unique_names(self):
        self.assertEqual(export.duplicates([{"name": "Main", "page": "MVP", "section": None, "node": "1:1"}]), {})


class CapturedNamesTest(unittest.TestCase):
    def test_reads_capture_calls_of_design_check_tests_only(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            test_dir = root / "feature/x/impl/src/test"
            test_dir.mkdir(parents=True)
            (test_dir / "MainDesignCheckTest.kt").write_text(
                'DesignCheck.capture("Main", dark = false)\ncapture(name = "MainDark")\ncapture( "MainEmpty" )\n')
            (test_dir / "OtherTest.kt").write_text('capture("Other")')
            build = root / "feature/x/impl/build"
            build.mkdir(parents=True)
            (build / "StaleDesignCheckTest.kt").write_text('capture("Stale")')
            self.assertEqual(export.captured_names(root), {"Main", "MainDark", "MainEmpty"})


class CoverageTest(unittest.TestCase):
    def test_marks_covered_and_rendered(self):
        boards = [{"name": "Main"}, {"name": "Deposits"}, {"name": "Chat"}]
        self.assertEqual(export.coverage(boards, captured={"Main", "Chat"}, snapshots={"Main"}), [
            {"name": "Main", "covered": True, "rendered": True},
            {"name": "Deposits", "covered": False, "rendered": False},
            {"name": "Chat", "covered": True, "rendered": False},
        ])


class BatchesTest(unittest.TestCase):
    def test_splits_into_requests_of_at_most_the_batch_size(self):
        self.assertEqual(export.batches(list(range(5)), size=2), [[0, 1], [2, 3], [4]])
        self.assertEqual(export.batches([], size=2), [])


class _Reply(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def http_error(code, retry_after=None):
    headers = {"Retry-After": retry_after} if retry_after else {}
    return urllib.error.HTTPError("https://api.figma.com", code, "error", headers, None)


class RequestTest(unittest.TestCase):
    def run_request(self, replies, attempts=5):
        calls, sleeps = [], []

        def opener(req, timeout):
            calls.append(req)
            reply = replies.pop(0)
            if isinstance(reply, Exception):
                raise reply
            return _Reply(reply)

        result = export.request("https://api.figma.com/v1/files/k", "secret", opener, sleeps.append, attempts)
        return result, calls, sleeps

    def test_sends_the_token(self):
        result, calls, _ = self.run_request([b"{}"])
        self.assertEqual(result, b"{}")
        self.assertEqual(calls[0].get_header("X-figma-token"), "secret")

    def test_no_token_header_for_presigned_urls(self):
        calls = []
        export.request("https://s3/x", None, lambda req, timeout: calls.append(req) or _Reply(b"png"), None)
        self.assertFalse(calls[0].has_header("X-figma-token"))

    def test_retries_rate_limit_and_server_errors(self):
        result, calls, sleeps = self.run_request([http_error(429, "7"), http_error(502), b"ok"])
        self.assertEqual(result, b"ok")
        self.assertEqual(len(calls), 3)
        self.assertEqual(sleeps, [7, 4])

    def test_client_errors_are_not_retried(self):
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.run_request([http_error(403), b"never"])
        self.assertEqual(e.exception.code, 403)

    def test_retries_network_errors(self):
        result, calls, sleeps = self.run_request(
            [urllib.error.URLError("reset"), TimeoutError(), ConnectionResetError(), b"ok"])
        self.assertEqual(result, b"ok")
        self.assertEqual(sleeps, [2, 4, 8])

    def test_network_error_on_the_last_attempt_is_raised(self):
        with self.assertRaises(TimeoutError):
            self.run_request([TimeoutError(), TimeoutError()], attempts=2)

    def test_gives_up_after_the_last_attempt(self):
        with self.assertRaises(urllib.error.HTTPError) as e:
            self.run_request([http_error(429), http_error(429)], attempts=2)
        self.assertEqual(e.exception.code, 429)



class MainTest(unittest.TestCase):
    """main() against a fake Figma: one artboard, Main, that renders."""

    def run_main(self, out, *names):
        document = {"document": {"children": [page("1:1", "MVP", [frame("5:2", "Main")])]}}

        def fake_request(url, token=None, *args, **kwargs):
            if "/files/" in url:
                return json.dumps(document).encode()
            if "/images/" in url:
                return json.dumps({"err": None, "images": {"5:2": "https://s3/main"}}).encode()
            return b"png:" + url.encode()

        original, export.request = export.request, fake_request
        try:
            with mock.patch.dict(os.environ, {"FIGMA_TOKEN": "t"}), contextlib.redirect_stdout(io.StringIO()):
                export.main([str(out), *names])
        finally:
            export.request = original

    def test_writes_the_artboard_and_coverage(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.run_main(tmp, "Main")
            self.assertEqual(Path(tmp, "Main.png").read_bytes(), b"png:https://s3/main")
            self.assertEqual([b["name"] for b in json.loads(Path(tmp, "coverage.json").read_text())], ["Main"])

    def test_named_artboard_missing_in_figma_fails_and_leaves_no_stale_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "Chat.png").write_bytes(b"old")
            with self.assertRaises(SystemExit) as e:
                self.run_main(tmp, "Main", "Chat")
            self.assertIn("Chat", str(e.exception.code))
            self.assertFalse(Path(tmp, "Chat.png").exists())
            self.assertTrue(Path(tmp, "Main.png").exists())

    def test_without_token_stops_before_any_request(self):
        with tempfile.TemporaryDirectory() as tmp, mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(SystemExit) as e:
                export.main([tmp, "Main"])
            self.assertIn("FIGMA_TOKEN", str(e.exception.code))


if __name__ == "__main__":
    unittest.main()
