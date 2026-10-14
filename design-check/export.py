"""Export the mockups from Figma to PNG for the design check.

    FIGMA_TOKEN=… python3 design-check/export.py <out dir> [Artboard ...]

An artboard is a top-level frame of the Figma file (a direct child of a page or of a top-level section on it) whose
name is a snapshot name: Latin letters and digits starting with a capital (`Main`, `SearchPeriod`, `MainDark`). Frames
named otherwise (the design-system sheets, archived drafts with Russian names) are not artboards.

Without artboard names, exports every name that has an app snapshot in build/design-check/app. Output:
`<out>/<Artboard>.png` at scale 3: a 390 wide frame gives 1170 px, the same as the app snapshots. An old file of
that name is removed first, so a frame that is gone or failed to render leaves no stale mockup. Also
`<out>/coverage.json`: every artboard of the file, whether a *DesignCheckTest captures it (`covered`: the name is
the first argument of a `capture("…"` call in the test sources) and whether this run has its snapshot
(`rendered`). The artboards with covered: false are the screens still to add.

Exit code 1 when an artboard named on the command line is missing or does not render; without names (every
snapshot) such artboards are only reported: a snapshot may have no mockup yet.

Environment: FIGMA_TOKEN (a personal access token with read access to file content), FIGMA_FILE_KEY (defaults to
the file «Понятные финансы — макеты»). Standard library only.
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

FILE_KEY = "u8paWRq6KAT73UJ1x2jXLv"
API = "https://api.figma.com/v1"
SCALE = 3
WIDTH = 390
BATCH = 50
ARTBOARD = re.compile(r"[A-Z][A-Za-z0-9]*")
CAPTURE = re.compile(r'\bcapture\(\s*(?:name\s*=\s*)?"([A-Za-z0-9]+)"')
SKIP_DIRS = {"build", "node_modules", ".git", ".gradle"}
ROOT = Path(__file__).resolve().parent.parent


def artboards(document):
    """Top-level frames named like snapshots: {name, page, section, node, w, h}, in file order.

    Sections are walked, frames are not: a frame inside a frame is part of a screen. The file is fetched with
    depth=3, so only frames of top-level sections arrive; deeper sections come back empty.
    """
    found = []

    def walk(node, page, section):
        for child in node.get("children", []):
            if child["type"] == "SECTION":
                walk(child, page, child["name"])
            elif child["type"] == "FRAME" and ARTBOARD.fullmatch(child["name"]):
                box = child.get("absoluteBoundingBox") or {}
                found.append({
                    "name": child["name"], "page": page, "section": section, "node": child["id"],
                    "w": round(box.get("width", 0)), "h": round(box.get("height", 0)),
                })

    for page in document.get("children", []):
        walk(page, page["name"], None)
    return found


def duplicates(boards):
    """Names used by more than one artboard, with where each one is: the pair would be ambiguous."""
    seen = {}
    for b in boards:
        seen.setdefault(b["name"], []).append(f'{b["page"]} / {b["section"] or "—"} ({b["node"]})')
    return {name: places for name, places in seen.items() if len(places) > 1}


def captured_names(root):
    """Artboard names passed to capture("…") in every *DesignCheckTest.kt under root, skipping build output."""
    names = set()
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for f in filenames:
            if f.endswith("DesignCheckTest.kt"):
                names.update(CAPTURE.findall(Path(dirpath, f).read_text(encoding="utf-8")))
    return names


def coverage(boards, captured, snapshots):
    return [{**b, "covered": b["name"] in captured, "rendered": b["name"] in snapshots} for b in boards]


def batches(items, size=BATCH):
    return [items[i:i + size] for i in range(0, len(items), size)]


def request(url, token=None, opener=urllib.request.urlopen, sleep=time.sleep, attempts=5):
    """GET with retries on 429, 5xx and network errors (Retry-After when the API sends it, else 2, 4, 8… s)."""
    headers = {"X-Figma-Token": token} if token else {}
    for attempt in range(attempts):
        try:
            with opener(urllib.request.Request(url, headers=headers), timeout=120) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code != 429 and e.code < 500 or attempt == attempts - 1:
                raise
            retry_after = e.headers.get("Retry-After") if e.headers else None
            sleep(int(retry_after) if retry_after and retry_after.isdigit() else 2 ** (attempt + 1))
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            if attempt == attempts - 1:
                raise
            sleep(2 ** (attempt + 1))
    raise AssertionError("unreachable")


def main(argv):
    if not argv:
        sys.exit(__doc__)
    out, names = Path(argv[0]), argv[1:]
    token = os.environ.get("FIGMA_TOKEN")
    if not token:
        sys.exit("FIGMA_TOKEN is not set: a Figma personal access token with read access to file content")
    key = os.environ.get("FIGMA_FILE_KEY", FILE_KEY)
    out.mkdir(parents=True, exist_ok=True)
    app_dir = ROOT / "build/design-check/app"
    snapshots = {p.stem for p in app_dir.glob("*.png")} if app_dir.is_dir() else set()
    wanted = names or sorted(snapshots)

    # depth=3: pages, their children, and one level inside sections (where the screens are).
    document = json.loads(request(f"{API}/files/{key}?depth=3", token))["document"]
    boards = artboards(document)
    dups = duplicates(boards)
    if dups:
        sys.exit("artboard names must be unique in the Figma file:\n" +
                 "\n".join(f"  {n}: {', '.join(p)}" for n, p in sorted(dups.items())))
    cov = coverage(boards, captured_names(ROOT), snapshots)
    (out / "coverage.json").write_text(json.dumps(cov, ensure_ascii=False, indent=2))
    print(f"coverage: {sum(b['covered'] for b in cov)} of {len(cov)} artboards have a design-check test")

    by_name = {b["name"]: b for b in boards}
    todo, failed = [], []
    for name in wanted:
        (out / f"{name}.png").unlink(missing_ok=True)
        b = by_name.get(name)
        if not b:
            print(name, "MISSING in Figma")
            failed.append(name)
        else:
            if b["w"] != WIDTH:
                print(name, f"width {b['w']} ≠ {WIDTH}: the pair will not be compared")
            todo.append(b)

    for batch in batches(todo):
        ids = ",".join(b["node"] for b in batch)
        reply = json.loads(request(f"{API}/images/{key}?ids={ids}&format=png&scale={SCALE}", token))
        if reply.get("err"):
            sys.exit(f"Figma images API: {reply['err']}")
        for b in batch:
            url = reply["images"].get(b["node"])
            if not url:
                print(b["name"], "Figma did not render the frame")
                failed.append(b["name"])
                continue
            # The image URLs are pre-signed: no token for them.
            (out / f"{b['name']}.png").write_bytes(request(url))
            print(b["name"], f"{b['w']}x{b['h']}")
    if names and failed:
        sys.exit(f"no mockup for: {', '.join(failed)}")


if __name__ == "__main__":
    main(sys.argv[1:])
