#!/usr/bin/env python3
"""Regenerate the Roboco app-icon family from the master artwork.

Input: one square, already-cropped artwork image (default
dist/roboco-artwork.png, or a path given as the first argument). Outputs:

- dist/roboco.png                        512x512, rounded corners (r=28)
- dist/roboco.svg                        256x256 SVG wrapping the rounded PNG
                                         (dual xlink:href/href data URI)
- crates/ui/assets/icons/roboco-logo.svg byte-identical copy of the above
- dist/windows/roboco.ico                16..256 multi-size ICO
- dist/macos/icon-1024.png               824px artwork in an Apple squircle,
                                         margins and soft shadow baked in
                                         (sips can't alpha-mask; see 39644a46)
- web/packages/app/public/favicon.png    128x128 rounded favicon

All outputs are committed so CI never needs Pillow — rerun this only when
changing the artwork (the previous swap was b59ba1a7, which also missed the
macOS icon; this script keeps the whole family in lockstep).

Usage: python3 scripts/generate-app-icons.py [artwork.png]
"""

import base64
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

ARTWORK = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "dist/roboco-artwork.png")

# Rounded-corner radius of the shared artwork, at 512px. 90/512 measured off
# the b59ba1a7 logo by row-scanning the alpha mask (the diagonal only reaches
# full opacity at ~r/3.4, so don't re-measure it that way).
CORNER_RADIUS_512 = 90

# macOS icon geometry: 824px artwork centered on a transparent 1024 canvas
# (Apple icon-grid squircle mask, exponent-5 superellipse), soft shadow
# offset 10px down. Matches the shape measured off 39644a46.
MAC_SIZE = 1024
MAC_ART = 824
MAC_OFFSET = (MAC_SIZE - MAC_ART) // 2
MAC_SHADOW_BLUR = 13
MAC_SHADOW_DROP = 10
MAC_SHADOW_ALPHA = 90


def rounded(art: Image.Image, size: int, radius: int) -> Image.Image:
    """Artwork resized to `size` with hard rounded corners (supersampled)."""
    scale = 4
    mask = Image.new("L", (size * scale, size * scale), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, size * scale - 1, size * scale - 1], radius=radius * scale, fill=255
    )
    mask = mask.resize((size, size), Image.LANCZOS)
    out = art.resize((size, size), Image.LANCZOS).convert("RGBA")
    out.putalpha(mask)
    return out


def squircle(size: int, exponent: float = 5.0) -> Image.Image:
    """Apple icon-grid shape as an exponent-n superellipse mask (AA'd)."""
    scale = 4
    a = size / 2.0
    pts = []
    steps = 8192
    for i in range(steps):
        t = 2.0 * math.pi * i / steps
        ct, st = math.cos(t), math.sin(t)
        x = a * (abs(ct) ** (2.0 / exponent)) * (1 if ct >= 0 else -1)
        y = a * (abs(st) ** (2.0 / exponent)) * (1 if st >= 0 else -1)
        pts.append(((a + x) * scale, (a + y) * scale))
    mask = Image.new("L", (size * scale, size * scale), 0)
    ImageDraw.Draw(mask).polygon(pts, fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def svg_wrapper(png_bytes: bytes) -> str:
    b64 = base64.b64encode(png_bytes).decode("ascii")
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" '
        'xmlns:xlink="http://www.w3.org/1999/xlink" '
        'viewBox="0 0 256 256" width="256" height="256">'
        '<image xlink:href="data:image/png;base64,{0}" '
        'href="data:image/png;base64,{0}" '
        'width="256" height="256"/></svg>'
    ).format(b64)


def main() -> None:
    art = Image.open(ARTWORK).convert("RGB")
    if art.width != art.height:
        sys.exit(f"artwork must be square, got {art.width}x{art.height}")
    print(f"artwork: {ARTWORK} {art.width}x{art.height}")

    # Shared artwork: 512 PNG + the 256 SVG pair.
    r512 = rounded(art, 512, CORNER_RADIUS_512)
    r512.save(os.path.join(ROOT, "dist/roboco.png"), optimize=True, compress_level=9)

    r256 = rounded(art, 256, CORNER_RADIUS_512 // 2)
    import io

    buf = io.BytesIO()
    r256.save(buf, format="PNG", optimize=True, compress_level=9)
    svg = svg_wrapper(buf.getvalue())
    for path in (
        os.path.join(ROOT, "dist/roboco.svg"),
        os.path.join(ROOT, "crates/ui/assets/icons/roboco-logo.svg"),
    ):
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            f.write(svg)

    # Windows ICO: same rounded artwork, every size the old one carried.
    r512.save(
        os.path.join(ROOT, "dist/windows/roboco.ico"),
        format="ICO",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )

    # macOS: squircle-masked artwork + baked shadow on the 1024 canvas.
    canvas = Image.new("RGBA", (MAC_SIZE, MAC_SIZE), (0, 0, 0, 0))
    shape = squircle(MAC_ART)
    shadow = Image.new("L", (MAC_SIZE, MAC_SIZE), 0)
    shadow.paste(shape, (MAC_OFFSET, MAC_OFFSET))
    shadow = shadow.filter(ImageFilter.GaussianBlur(MAC_SHADOW_BLUR))
    shifted = Image.new("L", (MAC_SIZE, MAC_SIZE), 0)
    shifted.paste(shadow, (0, MAC_SHADOW_DROP))
    shifted = shifted.point(lambda v: v * MAC_SHADOW_ALPHA // 255)
    canvas.paste((0, 0, 0, 255), (0, 0), shifted)
    mac_art = art.resize((MAC_ART, MAC_ART), Image.LANCZOS).convert("RGBA")
    mac_art.putalpha(shape)
    canvas.alpha_composite(mac_art, (MAC_OFFSET, MAC_OFFSET))
    canvas.save(os.path.join(ROOT, "dist/macos/icon-1024.png"), optimize=True, compress_level=9)

    # Web favicon.
    rounded(art, 128, CORNER_RADIUS_512 // 4).save(
        os.path.join(ROOT, "web/packages/app/public/favicon.png"),
        optimize=True,
        compress_level=9,
    )

    for rel in (
        "dist/roboco.png",
        "dist/roboco.svg",
        "crates/ui/assets/icons/roboco-logo.svg",
        "dist/windows/roboco.ico",
        "dist/macos/icon-1024.png",
        "web/packages/app/public/favicon.png",
    ):
        p = os.path.join(ROOT, rel)
        print(f"{rel:45s} {os.path.getsize(p):>9,} bytes")


if __name__ == "__main__":
    main()
