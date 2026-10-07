"""Pair app snapshots with mockup renders and score them.

    python3 design-check/compare.py <mockups dir> <app dir> <out dir>

For every `<name>.png` present in both directories writes `<out>/<name>.png` (mockup | app | difference heat
map) and `<out>/scores.json`, sorted worst first. The score is SSIM on grayscale (1.0 = identical); the
mockups and the app use the same font and size, so the score reflects layout, spacing and colour, not text
rendering. A score is a ranking aid, not a verdict: the report says what differs and why.
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from skimage.metrics import structural_similarity

LABEL_H = 64


def load(path, size=None):
    im = Image.open(path).convert("RGB")
    if size and im.size != size:
        # Same artboard, different height (app content shorter / longer): pad or crop at the bottom.
        canvas = Image.new("RGB", size, im.getpixel((0, im.height - 1)))
        canvas.paste(im.crop((0, 0, size[0], min(im.height, size[1]))), (0, 0))
        im = canvas
    return im


def heatmap(a, b):
    d = np.abs(np.asarray(a, dtype=np.int16) - np.asarray(b, dtype=np.int16)).max(axis=2)
    mask = d > 24
    out = (np.asarray(b, dtype=np.float32) * 0.25 + 191).astype(np.uint8)
    out[mask] = (230, 40, 40)
    return Image.fromarray(out), float(mask.mean())


def labelled(images, titles):
    w, h = images[0].size
    sheet = Image.new("RGB", (w * len(images), h + LABEL_H), "white")
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 40)
    except OSError:
        font = ImageFont.load_default()
    for i, (im, t) in enumerate(zip(images, titles)):
        sheet.paste(im, (i * w, LABEL_H))
        draw.text((i * w + 16, 12), t, fill="black", font=font)
    return sheet


def main(mock_dir, app_dir, out_dir):
    mock_dir, app_dir, out_dir = Path(mock_dir), Path(app_dir), Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    scores = []
    for app in sorted(app_dir.glob("*.png")):
        mock = mock_dir / app.name
        if not mock.exists():
            scores.append({"name": app.stem, "missing": "mockup"})
            continue
        a = load(mock)
        b = load(app, a.size)
        ssim = structural_similarity(np.asarray(a.convert("L")), np.asarray(b.convert("L")), data_range=255)
        diff, changed = heatmap(a, b)
        labelled([a, b, diff], ["Макет", "Приложение", f"Отличия · SSIM {ssim:.3f}"]).save(out_dir / app.name, optimize=True)
        scores.append({"name": app.stem, "ssim": round(float(ssim), 4), "changed": round(changed, 4)})
    scores.sort(key=lambda s: s.get("ssim", -1))
    (out_dir / "scores.json").write_text(json.dumps(scores, ensure_ascii=False, indent=2))
    for s in scores:
        print(f"{s['name']:<28} " + (f"SSIM {s['ssim']:.3f}  changed {s['changed']:.1%}" if "ssim" in s else f"missing {s['missing']}"))


if __name__ == "__main__":
    main(*sys.argv[1:4])
