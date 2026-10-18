#!/usr/bin/env python3
"""Keeps `:core:api` and `:mock:backend` from growing JVM-only code (FIN-144, docs/kmp-readiness.md).

The two modules are the ones to move to Kotlin Multiplatform when iOS starts, so that the Swift app can reuse the
contract and the fake backend. Until then the check does not demand a clean module, only no new debt:

- JVM-only APIs: every reference to a package of `java.*`, `javax.*`, `android.*`, `androidx.*` or `dagger.*` in
  the production sources (every source set under `src/` except `test*` and `androidTest*`), as an import or as a
  fully qualified name in the code (`java.time.Duration.between(...)`). Comments and string literals are skipped.
  An API is identified by its class (`java.time.Duration`), or by the import as written for a star import.
- Build: every plugin and dependency declaration of the module's `build.gradle.kts` (`id("pf.android.library")`,
  `implementation(libs.kotlinx.coroutines.android)`).

What the module uses today is listed in `scripts/kmp-baseline.json`. A reference or declaration outside it fails the
check; an entry of the baseline that the module no longer uses fails it too, so the baseline only shrinks. The
baseline is per module, not per file: the cost of the move is per API (each `java.time.LocalDate` goes to
kotlinx-datetime the same way), so reusing a listed API in another file is not new debt.

Test sources are not checked: in a multiplatform module they become `jvmTest` / `androidUnitTest`, where JUnit,
Robolectric and `java.io` fixtures stay valid.

Usage:
  scripts/check-kmp-ready.py            check against the baseline (CI)
  scripts/check-kmp-ready.py --write    rewrite the baseline from the sources (to drop entries the code no longer
                                        uses; a new entry needs a reason in the PR, docs/kmp-readiness.md)
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "scripts" / "kmp-baseline.json"
MODULES = ("core/api", "mock/backend")
JVM_ROOTS = ("java", "javax", "android", "androidx", "dagger")
TEST_SOURCE_SETS = ("test", "androidTest")

_STRING_OR_COMMENT = re.compile(
    r'"""[\s\S]*?"""'  # raw string
    r'|"(?:\\.|[^"\\\n])*"'  # string literal
    r"|'(?:\\.|[^'\\\n])+'"  # char literal
    r"|//[^\n]*"  # line comment
    r"|/\*[\s\S]*?\*/"  # block comment (KDoc included)
)
_IMPORT = re.compile(r"^\s*import\s+([A-Za-z_][\w.]*(?:\.\*)?)(?:\s+as\s+\w+)?\s*;?\s*$", re.MULTILINE)
_QUALIFIED = re.compile(r"(?<![\w.])((?:%s)(?:\.[A-Za-z_]\w*)+)" % "|".join(JVM_ROOTS))
_DECLARATION = re.compile(
    r"\b(\w+)\(\s*("
    r"libs\.[\w.]+"
    r"|projects\.[\w.]+"
    r'|project\(\s*"[^"]*"\s*\)'
    r'|platform\([^()]*\)'
    r'|"[^"]*"'
    r")\s*\)"
)


def strip_code(text: str) -> str:
    """Kotlin source with comments and string literals blanked out (newlines kept for line numbers)."""
    return _STRING_OR_COMMENT.sub(lambda m: re.sub(r"[^\n]", " ", m.group(0)), text)


def api_name(reference: str) -> str:
    """`java.time.Duration.between` → `java.time.Duration`: up to the first segment that starts with a capital."""
    if reference.endswith(".*"):
        return reference
    parts = reference.split(".")
    for i, part in enumerate(parts):
        if part[:1].isupper():
            return ".".join(parts[: i + 1])
    return reference


def jvm_references(text: str) -> list[tuple[int, str]]:
    """(line, API) for every JVM-only reference of a Kotlin source."""
    code = strip_code(text)
    found = []
    imports = set()
    for match in _IMPORT.finditer(code):
        imports.add(match.start(1))
        name = match.group(1)
        if name.split(".")[0] in JVM_ROOTS:
            found.append((code.count("\n", 0, match.start(1)) + 1, api_name(name)))
    for match in _QUALIFIED.finditer(code):
        if match.start(1) in imports:
            continue
        found.append((code.count("\n", 0, match.start(1)) + 1, api_name(match.group(1))))
    return found


def build_declarations(text: str) -> list[str]:
    """Plugin and dependency declarations of a build.gradle.kts, whitespace removed: `id("pf.hilt")`."""
    code = _STRING_OR_COMMENT.sub(
        lambda m: m.group(0) if m.group(0).startswith('"') else re.sub(r"[^\n]", " ", m.group(0)), text
    )
    return [re.sub(r"\s+", "", f"{m.group(1)}({m.group(2)})") for m in _DECLARATION.finditer(code)]


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
    """({API: ["file:line", ...]}, [build declarations]) of one module."""
    module_dir = root / module
    apis: dict[str, list[str]] = {}
    for path in production_sources(module_dir):
        for line, name in jvm_references(path.read_text(encoding="utf-8")):
            apis.setdefault(name, []).append(f"{path.relative_to(root)}:{line}")
    build_file = module_dir / "build.gradle.kts"
    build = build_declarations(build_file.read_text(encoding="utf-8")) if build_file.is_file() else []
    return apis, build


def problems(root: Path, baseline: dict) -> list[str]:
    found = []
    for module in MODULES:
        allowed = baseline.get(module, {})
        allowed_apis = set(allowed.get("jvm_apis", []))
        allowed_build = set(allowed.get("build", []))
        apis, build = scan_module(root, module)
        for name in sorted(set(apis) - allowed_apis):
            for where in apis[name]:
                found.append(f"{where}: new JVM-only API {name} in {module} (use a multiplatform one, "
                             f"docs/kmp-readiness.md)")
        for decl in sorted(set(build) - allowed_build):
            found.append(f"{module}/build.gradle.kts: new declaration {decl} (only multiplatform-ready "
                         f"dependencies; add it to scripts/kmp-baseline.json with a reason in the PR)")
        for name in sorted(allowed_apis - set(apis)):
            found.append(f"scripts/kmp-baseline.json: {module} no longer uses {name}; remove it from the baseline")
        for decl in sorted(allowed_build - set(build)):
            found.append(f"scripts/kmp-baseline.json: {module} no longer declares {decl}; remove it from the baseline")
    return found


def current_baseline(root: Path) -> dict:
    result = {}
    for module in MODULES:
        apis, build = scan_module(root, module)
        result[module] = {"jvm_apis": sorted(apis), "build": sorted(set(build))}
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
    print("core/api and mock/backend: no JVM-only APIs or dependencies beyond scripts/kmp-baseline.json")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
