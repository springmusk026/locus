#!/usr/bin/env python3
"""
Renders docs/icon.svg to a PNG for store listings (F-Droid's fastlane images/icon.png), using only
the Python standard library, so no image tools or build dependencies are needed.

The icon is simple geometry, so this draws it directly rather than parsing SVG: a rounded square
and an even-odd ring with a centre dot. It refuses to run if docs/icon.svg no longer contains the
shapes below, so a changed icon can't silently produce a stale PNG.

Usage: tools/render-icon.py [size] [output]
Default: 512 fastlane/metadata/android/en-US/images/icon.png
"""
import os
import struct
import sys
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SVG = os.path.join(ROOT, "docs", "icon.svg")

# What docs/icon.svg draws, in its 108x108 viewBox.
EXPECTED = [
    'viewBox="0 0 108 108"',
    '<rect width="108" height="108" rx="26" fill="#2E6B5E"/>',
    'fill="#F7F6F2" fill-rule="evenodd" d="M54,32a22,22 0,1 1,0 44a22,22 0,1 1,0 -44zM54,41a13,13 0,1 0,0 26a13,13 0,1 0,0 -26zM54,49a5,5 0,1 1,0 10a5,5 0,1 1,0 -10z"',
]
VIEW = 108.0
CORNER = 26.0
BACKGROUND = (0x2E, 0x6B, 0x5E)
FOREGROUND = (0xF7, 0xF6, 0xF2)
CENTRE = 54.0
RINGS = [22.0, 13.0, 5.0]  # even-odd: inside 22 and outside 13 is filled, inside 5 is filled again
SAMPLES = 4  # per axis, for anti-aliasing


def in_rounded_square(x, y):
    cx = min(max(x, CORNER), VIEW - CORNER)
    cy = min(max(y, CORNER), VIEW - CORNER)
    return 0 <= x <= VIEW and 0 <= y <= VIEW and (x - cx) ** 2 + (y - cy) ** 2 <= CORNER ** 2


def in_mark(x, y):
    d2 = (x - CENTRE) ** 2 + (y - CENTRE) ** 2
    return sum(1 for r in RINGS if d2 <= r * r) % 2 == 1


def render(size):
    rows = []
    step = VIEW / size
    for py in range(size):
        row = bytearray([0])  # PNG filter: none
        for px in range(size):
            cover = mark = 0
            for sy in range(SAMPLES):
                for sx in range(SAMPLES):
                    x = (px + (sx + 0.5) / SAMPLES) * step
                    y = (py + (sy + 0.5) / SAMPLES) * step
                    if in_rounded_square(x, y):
                        cover += 1
                        if in_mark(x, y):
                            mark += 1
            n = SAMPLES * SAMPLES
            if cover == 0:
                row += bytes(4)
                continue
            t = mark / cover
            rgb = [round(b + (f - b) * t) for b, f in zip(BACKGROUND, FOREGROUND)]
            row += bytes(rgb + [round(255 * cover / n)])
        rows.append(bytes(row))
    return b"".join(rows)


def png(size, raw):
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)  # 8-bit RGBA
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def main():
    size = int(sys.argv[1]) if len(sys.argv) > 1 else 512
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "fastlane", "metadata", "android", "en-US", "images", "icon.png")
    with open(SVG, encoding="utf-8") as f:
        svg = f.read()
    missing = [e for e in EXPECTED if e not in svg]
    if missing:
        sys.exit("docs/icon.svg has changed; update tools/render-icon.py to match. Missing:\n  " + "\n  ".join(missing))
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "wb") as f:
        f.write(png(size, render(size)))
    print(f"Wrote {out} ({size}x{size})")


if __name__ == "__main__":
    main()
