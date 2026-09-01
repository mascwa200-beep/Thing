#!/usr/bin/env python3
"""Render the star map's pre-26 launcher icon from the same drawing as the adaptive one.

⚠️ **WHY THIS EXISTS, and it is a build failure rather than a cosmetic gap.** `:sky` ships its
launcher icon as `mipmap-anydpi-v26/ic_launcher.xml`, an adaptive icon, and nothing else. At
minSdk 26 that is complete: every device that can install the app asks for the `-v26` bucket. Drop
the floor below 26 and `@mipmap/ic_launcher` — which the manifest names — has NO resource for
API 23-25 at all. Found by reading the resource tree before lowering the floor, not by a red build.

⚠️ **The artwork is transcribed from `ic_launcher_foreground.xml`, deliberately by hand rather than
by parsing it.** A general Android-vector renderer is a much larger thing than this needs, and it
would be a second way of reading a file that already has exactly one reader — the platform. What
matters is that the two are the same MARK, and that is a design property no parser could check
anyway. The constants below are the vector's own path data, in its own 108-unit viewport, so the
comparison is a glance rather than an arithmetic exercise: five stars joined into an invented
asterism, sized by brightness, with a glow on the brightest.

⚠️ Regenerate whenever the vector changes — nothing in the build enforces it, because a build cannot
tell two drawings apart. `SkyIconTest` in `:sky` holds the much weaker property that both exist and
that the raster is present at every density, which is the half a machine can check.

Usage: python3 tools/sky/build_legacy_icon.py
"""

from __future__ import annotations

import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "sky", "src", "main", "res")

# The vector's viewport, so every coordinate below can be copied straight across.
VIEWPORT = 108.0

# ⚠️ **The safe zone, and rendering the whole viewport instead is the mistake this constant exists
# to prevent.** An adaptive icon is 108 units of which a launcher shows only the central 72 — the
# outer 18 on every side is masked away and used for parallax. So the mark occupies about two thirds
# of what a modern launcher actually displays. Drawn full-viewport into a legacy icon, which has no
# mask, the same mark fills only about 44% of the frame and reads as a smaller, weaker version of
# the same app beside itself. Cropping to the safe zone makes the two look like one icon.
#
# Measured on the shipped vector: the asterism spans x 32..80, so 44% of 108 against 66% of 72.
SAFE_ZONE = 72.0
SAFE_INSET = (VIEWPORT - SAFE_ZONE) / 2.0

# `@color/ic_launcher_background`, which the adaptive icon paints behind the same foreground.
BACKGROUND = (0x0B, 0x14, 0x30, 255)

# The joining lines: strokeColor #7FA8E0 at 0.75 alpha, width 2.
LINE_RGB = (0x7F, 0xA8, 0xE0)
LINE_ALPHA = 0.75
LINE_WIDTH = 2.0
ASTERISM = [(32, 68), (44, 42), (58, 56), (74, 34), (80, 62)]

# The five stars: (x, y, radius, fill). Sized by brightness, exactly as the vector does.
STARS = [
    (44, 42, 6.0, (0xFF, 0xFF, 0xFF)),
    (74, 34, 4.5, (0xFF, 0xFF, 0xFF)),
    (58, 56, 3.5, (0xE8, 0xEF, 0xFA)),
    (32, 68, 3.0, (0xE8, 0xEF, 0xFA)),
    (80, 62, 2.5, (0xC8, 0xD6, 0xEC)),
]

# The glow ring on the brightest: radius 11, stroke #FFFFFF at 0.3 alpha, width 1.5.
GLOW = (44, 42, 11.0, 1.5, 0.3)

# ⚠️ **Rendered at 4x and downsampled, because PIL has no antialiasing.** A circle drawn directly at
# 48 px has visibly stepped edges, which on a launcher icon reads as a broken image rather than as a
# small one. Supersampling is the cheap fix and costs nothing at these sizes.
SUPERSAMPLE = 4

# The legacy density buckets. mdpi is the baseline 48 px; each step up is the usual multiplier.
DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}


def draw(size: int, round_icon: bool) -> Image.Image:
    """The mark at `size` pixels, square or circular."""
    big = size * SUPERSAMPLE
    scale = big / SAFE_ZONE
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    canvas = ImageDraw.Draw(image)

    def at(value: float) -> float:
        """A viewport coordinate in pixels, with the masked border taken off."""
        return (value - SAFE_INSET) * scale

    # ⚠️ The background is painted as a SHAPE rather than as the image's fill, because the round
    # variant has to leave its corners transparent. A launcher that masks the icon itself would
    # otherwise get a square of dark blue behind whatever shape it applies.
    if round_icon:
        canvas.ellipse((0, 0, big - 1, big - 1), fill=BACKGROUND)
    else:
        canvas.rectangle((0, 0, big, big), fill=BACKGROUND)

    # ⚠️ Lines, then stars, then the glow — the vector's own declaration order, which is its paint
    # order. The three do not currently overlap enough for it to show, so this is fidelity against a
    # future change to a radius rather than a visible fix today.
    canvas.line(
        [(at(x), at(y)) for x, y in ASTERISM],
        fill=LINE_RGB + (int(255 * LINE_ALPHA),),
        width=max(1, round(LINE_WIDTH * scale)),
        joint="curve",
    )

    for sx, sy, radius, fill in STARS:
        canvas.ellipse(
            (at(sx - radius), at(sy - radius), at(sx + radius), at(sy + radius)),
            fill=fill + (255,),
        )

    gx, gy, gr, gw, ga = GLOW
    canvas.ellipse(
        (at(gx - gr), at(gy - gr), at(gx + gr), at(gy + gr)),
        outline=(0xFF, 0xFF, 0xFF, int(255 * ga)),
        width=max(1, round(gw * scale)),
    )

    return image.resize((size, size), Image.LANCZOS)


def main() -> int:
    written = []
    for folder, size in sorted(DENSITIES.items(), key=lambda kv: kv[1]):
        directory = os.path.join(RES, folder)
        os.makedirs(directory, exist_ok=True)
        for name, is_round in (("ic_launcher.png", False), ("ic_launcher_round.png", True)):
            path = os.path.join(directory, name)
            draw(size, is_round).save(path, "PNG", optimize=True)
            written.append((os.path.relpath(path, ROOT), size, os.path.getsize(path)))

    total = sum(entry[2] for entry in written)
    for relative, size, byte_count in written:
        print(f"  {size:>3}px  {byte_count:>6} B  {relative}")
    print(f"{len(written)} file(s), {total} bytes in total")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
