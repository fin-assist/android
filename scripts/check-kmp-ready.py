#!/usr/bin/env python3
"""Keeps `:core:api` and `:mock:backend` from growing JVM-only code (FIN-144, docs/kmp-readiness.md).

The two modules are the ones to move to Kotlin Multiplatform when iOS starts, so that the Swift app can reuse the
contract and the fake backend. Until then the check does not demand a clean module, only no new debt:

- JVM-only APIs in the production sources (every source set under `src/` except `test*` and `androidTest*`):
  - a package of `JVM_PACKAGES` (`java.*`, `javax.*`, `android.*`, `androidx.*`, `dagger.*`, the JDK's `org.w3c.*`,
    `org.xml.*`, `org.ietf.*`, `org.omg.*`, `sun.*`, `com.sun.*`, `jdk.*`, and the JVM-only parts of the Kotlin stdlib
    `kotlin.io.path`, `kotlin.streams`, `kotlin.reflect.full`, `kotlin.reflect.jvm`, `kotlin.jvm.optionals`, the
    JVM-only members of `kotlin.concurrent` (`thread`, `withLock`, ...) and `kotlinx.coroutines.future` / `.stream`
    / `.time` / `.android`), as an import or as a fully qualified class name in the code
    (`java.time.Duration.between(...)`); backticks around name segments do not hide it;
  - a class of `java.lang` that Kotlin/JVM resolves without an import (`JAVA_LANG_IMPLICIT`), anywhere it is used as
    a whole word (`Math.floorDiv(...)`, `worker: Thread`, `System::currentTimeMillis`), unless the file imports or
    declares a class of that name.
  Comments (nested block comments included) and the text of string literals are skipped; the code inside `${...}`
  of a string template is checked. An API is identified by its class (`java.time.Duration`), or by the import as
  written for a star import or a top-level function.
- Build: the module's `build.gradle.kts` as a whole, normalized (comments dropped, whitespace collapsed, blank lines
  skipped). Any other change to the file fails until the baseline is rewritten, in whatever form a plugin or
  dependency is declared (`add("api", ...)`, `"implementation"(...)`, a variable, `apply(plugin = ...)`): the PR
  shows the change and says why it is multiplatform-ready. Convention plugins of `build-logic` (`pf.jvm.library`,
  `pf.hilt`, ...) add dependencies the module's file does not show; they are outside the check.

What the module uses today is listed in `scripts/kmp-baseline.json`. A reference outside it fails the check; an
entry of the baseline that the module no longer uses fails it too, so the baseline only shrinks. The baseline is per
module, not per file: the cost of the move is per API (each `java.time.LocalDate` goes to kotlinx-datetime the same
way), so reusing a listed API in another file is not new debt.

Test sources are not checked: in a multiplatform module they become `jvmTest` / `androidUnitTest`, where JUnit,
Robolectric and `java.io` fixtures stay valid.

Usage:
  scripts/check-kmp-ready.py            check against the baseline (CI)
  scripts/check-kmp-ready.py --write    rewrite the baseline from the sources (to drop entries the code no longer
                                        uses or to accept a reviewed build change; a new entry needs a reason in
                                        the PR, docs/kmp-readiness.md)
"""
import difflib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "scripts" / "kmp-baseline.json"
MODULES = ("core/api", "mock/backend")
JVM_PACKAGES = (
    "java", "javax", "android", "androidx", "dagger",
    # JDK packages outside java.* / javax.*
    "org.w3c", "org.xml", "org.ietf", "org.omg", "sun", "com.sun", "jdk",
    # JVM-only parts of the Kotlin stdlib
    "kotlin.io.path", "kotlin.streams", "kotlin.reflect.full", "kotlin.reflect.jvm", "kotlin.jvm.optionals",
    # JVM-only members of kotlin.concurrent (the package itself has common Volatile and atomics)
    "kotlin.concurrent.thread", "kotlin.concurrent.timer", "kotlin.concurrent.fixedRateTimer",
    "kotlin.concurrent.withLock", "kotlin.concurrent.read", "kotlin.concurrent.write", "kotlin.concurrent.getOrSet",
    # JVM-only parts of kotlinx-coroutines
    "kotlinx.coroutines.future", "kotlinx.coroutines.stream", "kotlinx.coroutines.time", "kotlinx.coroutines.android",
)
# java.lang classes Kotlin/JVM resolves without an import and common Kotlin has no class of that name for
# (`Exception`, `RuntimeException`, `StringBuilder`, `Comparable`, `AutoCloseable` have a common typealias).
JAVA_LANG_IMPLICIT = (
    "System", "Thread", "Runtime", "Class", "ClassLoader", "Math", "StrictMath", "Integer", "Character",
    "ProcessBuilder", "Process", "ThreadLocal", "Runnable", "InterruptedException", "StringBuffer", "Object", "Void",
    "StackTraceElement", "OutOfMemoryError", "StackOverflowError", "ClassNotFoundException",
    "CloneNotSupportedException",
)
TEST_SOURCE_SETS = ("test", "androidTest")

_CHAR_LITERAL = re.compile(r"'(?:\\.|[^'\\\n])+'")
_IMPORT = re.compile(r"^\s*import\s+([A-Za-z_][\w.]*(?:\.\*)?)(?:\s+as\s+(\w+))?\s*;?\s*$", re.MULTILINE)
_PACKAGES = "|".join(re.escape(p) for p in sorted(JVM_PACKAGES, key=len, reverse=True))
# A package of JVM_PACKAGES, lower-case package segments, then a class (a segment that starts with a capital): a
# local `val android` followed by `android.version` is not a package.
_QUALIFIED = re.compile(r"(?<![\w.])((?:%s)(?:\.[a-z_]\w*)*\.[A-Z]\w*(?:\.\w+)*)" % _PACKAGES)
# Any use of the name as a whole word: a call, a type (`worker: Thread?`), `Thread::class`, `System::currentTimeMillis`.
_IMPLICIT = re.compile(r"(?<![\w.$])(%s)\b" % "|".join(JAVA_LANG_IMPLICIT))
_DECLARED = re.compile(r"\b(?:class|interface|object|typealias)\s+(\w+)")


class _Scanner:
    """Kotlin lexer just deep enough to tell code from comments and string text.

    Handles nested block comments, escapes, raw strings (the closing quotes may run longer than three) and `${...}`
    templates with their own strings and braces inside. Newlines are kept, so line numbers stay.
    """

    def __init__(self, text: str, blank_strings: bool):
        self.text = text
        self.n = len(text)
        self.out = list(text)
        self.blank_strings = blank_strings

    def run(self) -> str:
        self.code(0, template=False)
        return "".join(self.out)

    def blank(self, start: int, end: int):
        for k in range(start, end):
            if self.out[k] != "\n":
                self.out[k] = " "

    def blank_text(self, start: int, end: int):
        if self.blank_strings:
            self.blank(start, end)

    def code(self, i: int, template: bool) -> int:
        """Scans code from `i`; inside a template returns the index of its closing `}`."""
        text, depth = self.text, 0
        while i < self.n:
            if text.startswith("//", i):
                end = text.find("\n", i)
                end = self.n if end < 0 else end
                self.blank(i, end)
                i = end
            elif text.startswith("/*", i):
                end = self.block_comment(i)
                self.blank(i, end)
                i = end
            elif text.startswith('"""', i):
                i = self.string(i, raw=True)
            elif text[i] == '"':
                i = self.string(i, raw=False)
            elif text[i] == "'" and (match := _CHAR_LITERAL.match(text, i)):
                self.blank_text(i, match.end())
                i = match.end()
            elif text[i] == "{":
                depth += 1
                i += 1
            elif text[i] == "}":
                if template and depth == 0:
                    return i
                depth -= 1
                i += 1
            else:
                i += 1
        return self.n

    def block_comment(self, i: int) -> int:
        """Index after the block comment opened at `i`; Kotlin block comments nest."""
        text, depth = self.text, 0
        while i < self.n:
            if text.startswith("/*", i):
                depth += 1
                i += 2
            elif text.startswith("*/", i):
                depth -= 1
                i += 2
                if depth == 0:
                    return i
            else:
                i += 1
        return self.n

    def string(self, i: int, raw: bool) -> int:
        """Index after the string literal opened at `i`; its text is blanked, its templates scanned as code."""
        text = self.text
        segment = i  # start of the literal text not blanked yet
        i += 3 if raw else 1
        while i < self.n:
            if raw and text.startswith('"""', i):
                end = i + 3
                while end < self.n and text[end] == '"':
                    end += 1
                self.blank_text(segment, end)
                return end
            if not raw:
                if text[i] == '"':
                    self.blank_text(segment, i + 1)
                    return i + 1
                if text[i] == "\n":  # unterminated: the compiler reports it
                    self.blank_text(segment, i)
                    return i
                if text[i] == "\\":
                    i += 2
                    continue
            if text.startswith("${", i):
                self.blank_text(segment, i + 2)
                close = self.code(i + 2, template=True)
                segment = close
                i = close + 1
                continue
            i += 1
        self.blank_text(segment, self.n)
        return self.n


def strip_code(text: str) -> str:
    """Kotlin source with comments and string text blanked out; code inside `${...}` and newlines are kept."""
    return _Scanner(text, blank_strings=True).run()


def strip_comments(text: str) -> str:
    """Kotlin source with comments blanked out, strings kept."""
    return _Scanner(text, blank_strings=False).run()


def is_jvm_package(name: str) -> bool:
    return any(name == p or name.startswith(p + ".") for p in JVM_PACKAGES)


def api_name(reference: str) -> str:
    """`java.time.Duration.between` → `java.time.Duration`: up to the first segment that starts with a capital."""
    if reference.endswith(".*"):
        return reference
    parts = reference.split(".")
    for i, part in enumerate(parts):
        if part[:1].isupper():
            return ".".join(parts[: i + 1])
    return reference


def _line(code: str, offset: int) -> int:
    return code.count("\n", 0, offset) + 1


def jvm_references(text: str) -> list[tuple[int, str]]:
    """(line, API) for every JVM-only reference of a Kotlin source, in source order."""
    # Backticks are dropped: Kotlin allows them around any name segment (import java.io.`File`).
    code = strip_code(text).replace("`", "")
    found = []
    imports = set()
    local_names = set(_DECLARED.findall(code))
    for match in _IMPORT.finditer(code):
        imports.add(match.start(1))
        name = match.group(1)
        local_names.add(match.group(2) or name.rsplit(".", 1)[-1])
        if is_jvm_package(name):
            found.append((match.start(1), api_name(name)))
    for match in _QUALIFIED.finditer(code):
        if match.start(1) not in imports:
            found.append((match.start(1), api_name(match.group(1))))
    for match in _IMPLICIT.finditer(code):
        if match.group(1) not in local_names:
            found.append((match.start(1), f"java.lang.{match.group(1)}"))
    return [(_line(code, offset), name) for offset, name in sorted(found)]


def build_lines(text: str) -> list[str]:
    """build.gradle.kts normalized: comments dropped, whitespace collapsed, blank lines skipped."""
    lines = (re.sub(r"\s+", " ", line).strip() for line in strip_comments(text).splitlines())
    return [line for line in lines if line]


def production_sources(module_dir: Path) -> list[Path]:
    src = module_dir / "src"
    if not src.is_dir():
        return []
    sources = []
    for source_set in sorted(p for p in src.iterdir() if p.is_dir()):
        if source_set.name.startswith(TEST_SOURCE_SETS):
            continue
        sources += sorted(p for p in source_set.rglob("*") if p.suffix in (".kt", ".kts", ".java"))
    return sources


def scan_module(root: Path, module: str) -> tuple[dict[str, list[str]], list[str]]:
    """({API: ["file:line", ...]}, normalized build.gradle.kts lines) of one module."""
    module_dir = root / module
    apis: dict[str, list[str]] = {}
    for path in production_sources(module_dir):
        for line, name in jvm_references(path.read_text(encoding="utf-8")):
            apis.setdefault(name, []).append(f"{path.relative_to(root)}:{line}")
    build_file = module_dir / "build.gradle.kts"
    build = build_lines(build_file.read_text(encoding="utf-8")) if build_file.is_file() else []
    return apis, build


def problems(root: Path, baseline: dict) -> list[str]:
    found = []
    for module in MODULES:
        allowed = baseline.get(module, {})
        allowed_apis = set(allowed.get("jvm_apis", []))
        allowed_build = list(allowed.get("build", []))
        apis, build = scan_module(root, module)
        for name in sorted(set(apis) - allowed_apis):
            for where in apis[name]:
                found.append(f"{where}: new JVM-only API {name} in {module} (use a multiplatform one, "
                             f"docs/kmp-readiness.md)")
        if build != allowed_build:
            changes = [line for line in difflib.ndiff(allowed_build, build) if line[:2] in ("+ ", "- ")]
            found.append(f"{module}/build.gradle.kts differs from scripts/kmp-baseline.json (only multiplatform-ready "
                         f"plugins and dependencies; give the reason in the PR and run scripts/check-kmp-ready.py "
                         f"--write):" + "".join(f"\n  {line}" for line in changes))
        for name in sorted(allowed_apis - set(apis)):
            found.append(f"scripts/kmp-baseline.json: {module} no longer uses {name}; remove it from the baseline")
    return found


def current_baseline(root: Path) -> dict:
    result = {}
    for module in MODULES:
        apis, build = scan_module(root, module)
        result[module] = {"jvm_apis": sorted(apis), "build": build}
    return result


def main(argv: list[str]) -> int:
    if argv == ["--write"]:
        BASELINE.write_text(json.dumps(current_baseline(ROOT), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"wrote {BASELINE.relative_to(ROOT)}")
        return 0
    if argv:
        print(__doc__, file=sys.stderr)
        return 2
    found = problems(ROOT, json.loads(BASELINE.read_text(encoding="utf-8")))
    for problem in found:
        print(problem, file=sys.stderr)
    if found:
        return 1
    print("core/api and mock/backend: no JVM-only APIs or build changes beyond scripts/kmp-baseline.json")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
