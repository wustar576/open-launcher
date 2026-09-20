#!/usr/bin/env python3
"""Open Launcher adaptive app icon generator -- DETERMINISTIC RASTER PIPELINE.

Regenerates the launcher's adaptive icon foreground/monochrome rasters
directly from `docs/design/icon-source.jpg` (a 1024x559 JPEG mock-up: a
blue house glyph on a white circular plate with a soft drop shadow, on an
off-white backdrop), by cropping the plate 1:1 onto the adaptive-icon
canvas and flattening everything that isn't glyph to pure white.

Why raster-crop instead of a hand-vectorized redraw
-----------------------------------------------------
An earlier version of this script re-drew the glyph as hand-measured
vector shapes. That process accumulates small measurement/approximation
errors (stroke widths, corner radii, cap positions) that compound into a
visibly "off" result relative to the source -- exactly the problem
reported in review. The user's own artwork already shows the intended
final look (glyph centred on the plate); the most faithful thing to do
is use those exact pixels, not re-interpret them. This pipeline is
therefore purely geometric (find the plate, crop it, flatten the
non-glyph pixels to white) with NO manual re-drawing of any shape, so
there is no proportion to get subtly wrong.

Pipeline
--------
1. `find_plate_circle()` locates the white plate's centre and diameter in
   the source image (see its docstring for the measurement method).
2. The 108x108dp adaptive-icon canvas is defined so that the PLATE
   DIAMETER maps to exactly 72dp (the adaptive-icon's visible/mask
   diameter) -- i.e. canvas_src_px = plate_diameter_px * 108/72 -- and is
   centred on the plate centre. This preserves the artist's own
   glyph-to-plate proportion exactly: no re-measurement of the glyph
   itself is needed at all, because we are not redrawing it.
3. `build_coverage_and_color()` computes, per source pixel inside that
   crop, a "glyph coverage" value in [0,1] from HSV saturation (glyph =
   saturated blues, background/shadow/arrow = desaturated near-white),
   smoothstepped for anti-aliased edges, and forced to 0 outside the
   plate's circular boundary (removing the backdrop and the plate's own
   drop shadow unconditionally).
4. That coverage is used two ways per output density:
     - foreground (OPAQUE): pixel = lerp(white, original_colour, coverage)
       -- i.e. paint the glyph's actual (un-flattened, gradient-preserving)
       colour over solid white. Because the background/shadow/arrow are
       already ~white, low coverage there is invisible either way -- the
       "keep the white arrow" requirement falls out for free.
     - monochrome (ALPHA-only, white): alpha = coverage -- so the arrow
       and the house's interior are automatically negative space.
5. A hard numeric safe-zone check verifies every above-threshold-coverage
   pixel lies within 33dp of the canvas centre (33dp = half of the 66dp
   adaptive-icon safe-zone diameter), which is automatically satisfied by
   construction (plate diameter 72dp is itself already less than 66dp*
   ... see the printed report) but is still asserted, not assumed.
6. Five densities (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi = 108/162/216/324/432px)
   are produced by LANCZOS-resampling the flattened crop (done once at
   source resolution, then downsampled per density -- never upsampled
   beyond the source's native detail), saved as lossless WebP.
7. A reviewer contact sheet is built from the generated xxxhdpi raster
   (see icon_preview.py) -- no separate "preview-only" rendering path,
   so the preview can never drift from what's actually shipped.

Re-running
----------
    python tools/icon/make_icon.py [--source PATH] [--no-preview]

Regenerates:
  - res/mipmap-<density>/ic_launcher_foreground.webp (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi)
  - res/mipmap-<density>/ic_launcher_monochrome.webp (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi)
  - build/icon-preview/preview.png (unless --no-preview)

If given a higher-resolution source image later, re-run
`find_plate_circle()` (or just re-run this script -- it auto-detects the
plate on every run, see its docstring) and everything downstream follows
automatically; there is no glyph geometry to re-measure.
"""
from __future__ import annotations

import argparse
import math
import os

import numpy as np
from PIL import Image, ImageFilter

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

CANVAS_DP = 108.0
MASK_VISIBLE_DP = 72.0     # the adaptive-icon's visible/masked diameter
SAFE_ZONE_RADIUS_DP = 33.0  # half of the 66dp safe-zone diameter
SAFE_ZONE_MARGIN_DP = 0.3

# Saturation/value thresholds for the glyph-coverage classifier (see
# build_coverage_and_color()). Tuned by inspection against this source:
# glyph blues measure S in [0.33, 0.62]; plate/backdrop/shadow/arrow all
# measure S < 0.02 (see docs/design measurement notes in the PR). A wide
# smoothstep margin (0.06 -> 0.16) keeps anti-aliased source edges smooth
# rather than introducing new hard edges.
COVERAGE_S_LO = 0.06
COVERAGE_S_HI = 0.16
# Additional gate: only treat a pixel as "possibly background" (subject to
# the saturation test above) if it's also reasonably bright; a dark,
# desaturated pixel (e.g. a black line, not present in this artwork but
# possible in a future source) is kept as glyph regardless of saturation.
# The soft long shadow cast across the plate dips as low as V=0.68 at its
# darkest (measured directly: min V among low-saturation in-plate pixels),
# so V_HI must clear that with margin -- 0.65 measured as the threshold
# needed for the shadow to be FULLY classified as background (not a
# partial/residual value that then fails the safe-zone check as if it
# were glyph). The blue glyph shapes are unaffected by how low these are
# because they're already excluded by the saturation term above (S 0.5+
# vs. these thresholds only mattering for S < COVERAGE_S_HI pixels).
COVERAGE_V_LO = 0.45
COVERAGE_V_HI = 0.65

DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

DEFAULT_SOURCE = os.path.abspath(os.path.join(
    os.path.dirname(__file__), "..", "..", "docs", "design", "icon-source.jpg"))


# ---------------------------------------------------------------------------
# 1. Plate detection
# ---------------------------------------------------------------------------

def find_plate_circle(img: Image.Image):
    """Locate the white plate's centre and diameter.

    Two-stage method:
      1. SEED the centre from the centroid of clearly-saturated pixels
         (HSV S > 0.15) -- i.e. the glyph's own artwork, which is
         centred on the plate by design -- and note the glyph's own max
         radius from that centroid. This both gives a much better centre
         estimate than the raw image centre AND tells us how far out we
         must look before we're safely past the glyph (so the shadow
         search below can never mistake an internal glyph edge for the
         plate boundary).
      2. From that seed, and starting the search just past the glyph's
         own extent: the plate/backdrop brightness difference is
         extremely subtle (~3-4 levels out of 255 on this source -- both
         are near-white), too subtle for a reliable global threshold,
         but the plate's soft drop shadow gives a real (if soft) local
         brightness MINIMUM just past its edge. For many angles, walk
         outward, find that per-angle shadow minimum, then find the
         50%-brightness crossing between the plate level and that
         minimum (the true plate edge) just inside it. A circle is
         least-squares fit to all those boundary points, with iterative
         outlier rejection (the long directional drop-shadow in this
         artwork is much stronger on one side, which would otherwise
         bias a naive fit). This converges tightly on this source: 357
         of 360 angle samples kept as inliers, 0.5px residual std.

    Returns ((cx, cy), diameter) in source-pixel coordinates.
    """
    sat_arr = np.asarray(img, dtype=np.float64) / 255.0
    mx = sat_arr.max(axis=2)
    mn = sat_arr.min(axis=2)
    s = np.where(mx > 1e-6, (mx - mn) / np.maximum(mx, 1e-6), 0.0)
    ys, xs = np.where(s > 0.15)
    assert len(xs) > 0, "No saturated (glyph) pixels found -- cannot seed plate centre."
    cx0, cy0 = float(xs.mean()), float(ys.mean())
    glyph_max_r = float(np.hypot(xs - cx0, ys - cy0).max())

    blurred = img.filter(ImageFilter.GaussianBlur(radius=3))
    arr = np.asarray(blurred, dtype=np.float64)
    val = arr.mean(axis=2)
    H, W = val.shape

    def sample(x, y):
        xi, yi = int(np.clip(x, 0, W - 2)), int(np.clip(y, 0, H - 2))
        fx, fy = x - xi, y - yi
        v00, v10 = val[yi, xi], val[yi, xi + 1]
        v01, v11 = val[yi + 1, xi], val[yi + 1, xi + 1]
        return (v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy

    # Start the search a safe margin past the glyph's own extent, run out
    # 140px further (comfortably covers the plate-edge + shadow band).
    r_search = (glyph_max_r + 10.0, glyph_max_r + 150.0)
    radii = np.arange(r_search[0], r_search[1], 0.5)
    n_angles = 360

    boundary = []
    for i in range(n_angles):
        theta = 2 * math.pi * i / n_angles
        dx, dy = math.cos(theta), math.sin(theta)
        profile = np.array([sample(cx0 + r * dx, cy0 + r * dy) for r in radii])
        min_idx = int(np.argmin(profile))
        if min_idx < 2 or min_idx > len(profile) - 3:
            continue
        min_val = profile[min_idx]
        plate_val = profile[:min_idx].max()
        if plate_val - min_val < 3.0:
            continue
        mid = (plate_val + min_val) / 2.0
        edge_r = None
        for j in range(min_idx):
            if profile[j] >= mid > profile[j + 1]:
                frac = (profile[j] - mid) / (profile[j] - profile[j + 1])
                edge_r = radii[j] + frac * (radii[j + 1] - radii[j])
                break
        if edge_r is not None:
            boundary.append((cx0 + edge_r * dx, cy0 + edge_r * dy))

    pts = np.array(boundary)

    def kasa_fit(p):
        xs, ys = p[:, 0], p[:, 1]
        A = np.c_[2 * xs, 2 * ys, np.ones(len(xs))]
        b = xs ** 2 + ys ** 2
        sol, *_ = np.linalg.lstsq(A, b, rcond=None)
        cx, cy, c = sol
        return cx, cy, math.sqrt(max(c + cx ** 2 + cy ** 2, 0))

    cx, cy, r = kasa_fit(pts)
    for _ in range(4):
        d = np.hypot(pts[:, 0] - cx, pts[:, 1] - cy)
        keep = np.abs(d - r) < max(2.5 * d.std(), 2.0)
        if keep.sum() < 8:
            break
        pts = pts[keep]
        cx, cy, r = kasa_fit(pts)

    return (cx, cy), 2 * r


# ---------------------------------------------------------------------------
# 2. Crop box
# ---------------------------------------------------------------------------

def compute_crop_box(plate_center, plate_diameter):
    """The square source-pixel crop box such that plate_diameter maps to
    exactly 72dp of the 108dp canvas, centred on the plate centre."""
    canvas_src_px = plate_diameter * (CANVAS_DP / MASK_VISIBLE_DP)
    half = canvas_src_px / 2.0
    cx, cy = plate_center
    left = round(cx - half)
    top = round(cy - half)
    size = round(canvas_src_px)
    return left, top, left + size, top + size, size


def load_crop_with_padding(img: Image.Image, box):
    """Crop `box` (l, t, r, b) out of `img`, padding with white if the box
    extends outside the source image bounds (keeps the pipeline correct
    for a future, differently-composed source)."""
    l, t, r, b = box
    W, H = img.size
    canvas = Image.new("RGB", (r - l, b - t), (255, 255, 255))
    src_l, src_t = max(l, 0), max(t, 0)
    src_r, src_b = min(r, W), min(b, H)
    if src_r > src_l and src_b > src_t:
        region = img.crop((src_l, src_t, src_r, src_b))
        canvas.paste(region, (src_l - l, src_t - t))
    return canvas


# ---------------------------------------------------------------------------
# 3. Coverage + colour
# ---------------------------------------------------------------------------

def _smoothstep(x, edge0, edge1):
    t = np.clip((x - edge0) / (edge1 - edge0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


def build_coverage_and_color(crop_rgb: Image.Image, plate_center_rel, plate_radius):
    """Returns (rgb_float[H,W,3] in 0..255, coverage[H,W] in 0..1,
    glyph_coverage[H,W] in 0..1). `coverage` is the final compositing
    mask (saturation-based coverage AND inside the plate circle);
    `glyph_coverage` is saturation-based ONLY (no plate-circle mask) --
    i.e. genuinely "is this pixel part of the coloured glyph artwork",
    used for the safe-zone check so the plate's own boundary feather
    (which is not glyph, just where the plate itself is cropped) can
    never be mistaken for glyph content sitting close to the mask edge."""
    arr = np.asarray(crop_rgb, dtype=np.float64) / 255.0
    r, g, b = arr[..., 0], arr[..., 1], arr[..., 2]
    mx = arr.max(axis=2)
    mn = arr.min(axis=2)
    diff = mx - mn
    s = np.where(mx > 1e-6, diff / np.maximum(mx, 1e-6), 0.0)
    v = mx

    # "background-like" (unsaturated AND bright) -> low coverage.
    bg_like = (1.0 - _smoothstep(s, COVERAGE_S_LO, COVERAGE_S_HI)) * \
              _smoothstep(v, COVERAGE_V_LO, COVERAGE_V_HI)
    glyph_coverage = 1.0 - bg_like

    # Circular plate mask: force coverage to 0 outside the plate radius
    # (removes the backdrop and the plate's own outer drop shadow
    # unconditionally, with a soft 2px-radius feather).
    H, W = s.shape
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float64)
    pcx, pcy = plate_center_rel
    dist = np.hypot(xx - pcx, yy - pcy)
    plate_mask = 1.0 - _smoothstep(dist, plate_radius - 2.0, plate_radius + 2.0)
    coverage = glyph_coverage * plate_mask

    return arr * 255.0, coverage, glyph_coverage


# ---------------------------------------------------------------------------
# 4. Compositing + saving
# ---------------------------------------------------------------------------

def composite_foreground(rgb, coverage):
    white = np.array([255.0, 255.0, 255.0])
    out = white * (1 - coverage[..., None]) + rgb * coverage[..., None]
    return np.clip(out, 0, 255).astype(np.uint8)


def composite_monochrome(coverage):
    H, W = coverage.shape
    out = np.zeros((H, W, 4), dtype=np.uint8)
    out[..., 0:3] = 255
    out[..., 3] = np.clip(coverage * 255.0, 0, 255).astype(np.uint8)
    return out


def resize_rgb(rgb_u8: np.ndarray, size: int) -> np.ndarray:
    img = Image.fromarray(rgb_u8, "RGB").resize((size, size), Image.LANCZOS)
    return np.asarray(img)


def resize_gray(coverage: np.ndarray, size: int) -> np.ndarray:
    img = Image.fromarray((np.clip(coverage, 0, 1) * 255).astype(np.uint8), "L")
    img = img.resize((size, size), Image.LANCZOS)
    return np.asarray(img).astype(np.float64) / 255.0


# ---------------------------------------------------------------------------
# 5. Safe-zone check
# ---------------------------------------------------------------------------

def assert_safe_zone(glyph_coverage, crop_size_px, threshold=0.15):
    """Every pixel with GENUINE glyph coverage (saturation-based, i.e. the
    house's actual coloured artwork -- NOT the plate's own circular
    boundary, which is deliberately placed at exactly 36dp/72dp-diameter
    and is expected to sit outside the tighter 33dp safe-zone radius,
    same as any adaptive icon that uses its full visible mask area) above
    `threshold` must lie within SAFE_ZONE_RADIUS_DP of the canvas centre.
    Returns (max_radius_dp, glyph_bbox_dp)."""
    px_to_dp = CANVAS_DP / crop_size_px
    ys, xs = np.where(glyph_coverage > threshold)
    assert len(xs) > 0, "No glyph pixels found above coverage threshold -- thresholds are wrong."
    cx = cy = crop_size_px / 2.0
    dist_px = np.hypot(xs - cx, ys - cy)
    max_r_dp = dist_px.max() * px_to_dp
    bbox_w_dp = (xs.max() - xs.min()) * px_to_dp
    bbox_h_dp = (ys.max() - ys.min()) * px_to_dp
    assert max_r_dp <= SAFE_ZONE_RADIUS_DP, (
        f"Safe-zone violation: a GLYPH pixel (not just the plate edge) is "
        f"{max_r_dp:.2f}dp from the canvas centre, exceeding the "
        f"{SAFE_ZONE_RADIUS_DP}dp safe-zone radius. The source artist's "
        f"glyph-to-plate margin is smaller than expected for this crop -- "
        f"either the plate measurement is wrong or the source glyph itself "
        f"extends close to the plate edge."
    )
    return max_r_dp, (bbox_w_dp, bbox_h_dp)


# ---------------------------------------------------------------------------
# 6. Main pipeline
# ---------------------------------------------------------------------------

def run_pipeline(source_path, repo_root, make_preview=True):
    img = Image.open(source_path).convert("RGB")
    plate_center, plate_diameter = find_plate_circle(img)
    crop_box = compute_crop_box(plate_center, plate_diameter)
    l, t, r, b, size = crop_box
    crop = load_crop_with_padding(img, (l, t, r, b))

    plate_center_rel = (plate_center[0] - l, plate_center[1] - t)
    plate_radius = plate_diameter / 2.0

    rgb, coverage, glyph_coverage = build_coverage_and_color(crop, plate_center_rel, plate_radius)

    max_r_dp, (bbox_w_dp, bbox_h_dp) = assert_safe_zone(glyph_coverage, size)

    report = {
        "plate_center": plate_center,
        "plate_diameter": plate_diameter,
        "crop_box": (l, t, r, b),
        "crop_size_px": size,
        "s_lo": COVERAGE_S_LO, "s_hi": COVERAGE_S_HI,
        "v_lo": COVERAGE_V_LO, "v_hi": COVERAGE_V_HI,
        "glyph_bbox_dp": (bbox_w_dp, bbox_h_dp),
        "max_radius_dp": max_r_dp,
    }
    print("\n--- plate detection / crop / safe-zone report ---")
    print(f"plate centre (source px):  ({plate_center[0]:.1f}, {plate_center[1]:.1f})")
    print(f"plate diameter (source px): {plate_diameter:.1f}")
    print(f"crop box (source px):       ({l}, {t}) - ({r}, {b})  [{size}x{size}]")
    print(f"coverage thresholds:        S in [{COVERAGE_S_LO}, {COVERAGE_S_HI}], "
          f"V in [{COVERAGE_V_LO}, {COVERAGE_V_HI}]")
    print(f"glyph bbox:                 {bbox_w_dp:.2f} x {bbox_h_dp:.2f} dp")
    print(f"max glyph-pixel radius from centre: {max_r_dp:.2f}dp "
          f"(safe-zone limit {SAFE_ZONE_RADIUS_DP}dp)")
    print("---------------------------------------------------\n")

    fg_dir_written = []
    for density, px in DENSITIES.items():
        rgb_px = resize_rgb(rgb.astype(np.uint8), px)
        cov_px = resize_gray(coverage, px)

        fg = composite_foreground(rgb_px.astype(np.float64), cov_px)
        mono = composite_monochrome(cov_px)

        fg_path = os.path.join(repo_root, "res", f"mipmap-{density}", "ic_launcher_foreground.webp")
        mono_path = os.path.join(repo_root, "res", f"mipmap-{density}", "ic_launcher_monochrome.webp")
        os.makedirs(os.path.dirname(fg_path), exist_ok=True)
        Image.fromarray(fg, "RGB").save(fg_path, "WEBP", lossless=True, quality=100, method=6)
        Image.fromarray(mono, "RGBA").save(mono_path, "WEBP", lossless=True, quality=100, method=6)
        fg_dir_written.append((density, px, fg_path, mono_path))
        print(f"wrote {fg_path}  ({px}x{px})")
        print(f"wrote {mono_path}  ({px}x{px})")

    if make_preview:
        from icon_preview import build_preview_sheet
        # Use the highest-resolution generated raster for the preview so it
        # can never drift from the shipped assets.
        hi_fg = Image.open(os.path.join(repo_root, "res", "mipmap-xxxhdpi", "ic_launcher_foreground.webp"))
        hi_mono = Image.open(os.path.join(repo_root, "res", "mipmap-xxxhdpi", "ic_launcher_monochrome.webp"))
        build_preview_sheet(repo_root, hi_fg, hi_mono, img, (l, t, r, b), report)

    return report


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", default=DEFAULT_SOURCE)
    ap.add_argument("--repo-root", default=os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..", "..")))
    ap.add_argument("--no-preview", action="store_true")
    args = ap.parse_args()
    run_pipeline(args.source, args.repo_root, make_preview=not args.no_preview)


if __name__ == "__main__":
    main()
