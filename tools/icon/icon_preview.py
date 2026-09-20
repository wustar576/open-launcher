"""Reviewer contact-sheet builder for the Open Launcher adaptive icon
(raster pipeline). Imported by make_icon.py; not meant to be run
standalone. Builds every preview tile directly from the ACTUAL generated
xxxhdpi foreground/monochrome rasters (never a separate rendering path),
so the preview can never drift from what's shipped. Sections: adaptive
masks x light/dark wallpaper, raw foreground halo/hole check, monochrome
tinted in Material-You-style circles, a 48px actual-size row, a source-
vs-generated side-by-side, and a pixel-registered 50%-opacity overlay of
the generated foreground directly on the source crop.
"""
from __future__ import annotations

import os

import numpy as np
from PIL import Image, ImageDraw, ImageFont


def _mask_circle(size):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size - 1, size - 1], fill=255)
    return m


def _mask_squircle(size):
    y, x = np.mgrid[0:size, 0:size].astype(float)
    cx = cy = (size - 1) / 2.0
    r = size / 2.0
    nx, ny = (x - cx) / r, (y - cy) / r
    val = (nx ** 4 + ny ** 4) <= 1.0
    return Image.fromarray((val * 255).astype("uint8"), "L")


def _mask_rounded_square(size):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.16, fill=255)
    return m


def _mask_teardrop(size):
    ss = 4
    S = size * ss
    r_big = S * 0.5
    r_small = S * 0.12
    yy, xx = np.mgrid[0:S, 0:S].astype(float)

    def corner_ok(cx, cy, r, sel):
        d2 = (xx - cx) ** 2 + (yy - cy) ** 2
        return ~(sel & (d2 > r * r))

    inside = np.ones((S, S), dtype=bool)
    tl = (xx < r_big) & (yy < r_big)
    tr = (xx > S - r_big) & (yy < r_big)
    bl = (xx < r_big) & (yy > S - r_big)
    br = (xx > S - r_small) & (yy > S - r_small)
    inside &= corner_ok(r_big, r_big, r_big, tl)
    inside &= corner_ok(S - r_big, r_big, r_big, tr)
    inside &= corner_ok(r_big, S - r_big, r_big, bl)
    inside &= corner_ok(S - r_small, S - r_small, r_small, br)

    arr = (inside * 255).astype("uint8")
    return Image.fromarray(arr, "L").resize((size, size), Image.LANCZOS)


MASKS = [
    ("Circle", _mask_circle),
    ("Squircle", _mask_squircle),
    ("Rounded square", _mask_rounded_square),
    ("Teardrop", _mask_teardrop),
]

LIGHT_WALL = (238, 240, 245)
DARK_WALL = (28, 30, 36)
BG_LAYER = (255, 255, 255)


def _fit(img, size):
    return img.resize((size, size), Image.LANCZOS)


def _composite_icon(size, bg_rgb, mask_fn, fg_rgba_full):
    """fg_rgba_full: the OPAQUE foreground raster (already white-flattened,
    background+foreground pre-composited since the foreground is fully
    opaque) resized to `size`, masked onto `bg_rgb`."""
    base = Image.new("RGB", (size, size), bg_rgb)
    fg = _fit(fg_rgba_full, size).convert("RGB")
    mask = mask_fn(size)
    base.paste(fg, (0, 0), mask)
    return base


def _text_w(text, font):
    return int(ImageDraw.Draw(Image.new("RGB", (1, 1))).textlength(text, font=font))


def build_preview_sheet(root, hi_fg: Image.Image, hi_mono: Image.Image,
                         source_img: Image.Image, crop_box, report: dict):
    out_dir = os.path.join(root, "build", "icon-preview")
    os.makedirs(out_dir, exist_ok=True)

    try:
        font = ImageFont.truetype("arial.ttf", 16)
        font_sm = ImageFont.truetype("arial.ttf", 13)
        font_h = ImageFont.truetype("arialbd.ttf", 20)
    except Exception:
        font = ImageFont.load_default()
        font_sm = font
        font_h = font

    TILE = 192
    PAD = 16

    hi_fg = hi_fg.convert("RGB")           # opaque already
    hi_mono = hi_mono.convert("RGBA")      # alpha = coverage, colour = white

    def new_row_canvas(n_cols, title, tile=TILE):
        w = max(PAD + n_cols * (tile + PAD), _text_w(title, font_h) + PAD * 2)
        h = 40 + tile + PAD * 2
        img = Image.new("RGB", (w, h), (255, 255, 255))
        d = ImageDraw.Draw(img)
        d.text((PAD, 8), title, fill=(10, 10, 10), font=font_h)
        return img, d

    # --- Row 1: masks x light/dark backgrounds ---------------------------
    row1, d1 = new_row_canvas(8, "Adaptive mask preview (light / dark wallpaper)")
    x, y = PAD, 40
    for name, mask_fn in MASKS:
        for wall_name, wall in (("light", LIGHT_WALL), ("dark", DARK_WALL)):
            tile = _composite_icon(TILE, wall, mask_fn, hi_fg)
            row1.paste(tile, (x, y))
            d1.rectangle([x, y, x + TILE, y + TILE], outline=(180, 180, 180))
            d1.text((x, y + TILE + 2), f"{name} / {wall_name}", fill=(30, 30, 30), font=font_sm)
            x += TILE + PAD

    # --- Row 2: raw foreground on black / magenta / white ----------------
    row2, d2 = new_row_canvas(3, "Raw foreground: halo check (black / magenta) + hole check (white)")
    x, y = PAD, 40
    fg_hi_tile = _fit(hi_fg, TILE)
    for name, bg in (("black", (0, 0, 0)), ("magenta", (255, 0, 255)), ("white", (255, 255, 255))):
        tile = Image.new("RGB", (TILE, TILE), bg)
        tile.paste(fg_hi_tile, (0, 0))
        row2.paste(tile, (x, y))
        d2.rectangle([x, y, x + TILE, y + TILE], outline=(150, 150, 150))
        d2.text((x, y + TILE + 2), f"foreground on {name}", fill=(30, 30, 30), font=font_sm)
        x += TILE + PAD

    # --- Row 3: monochrome tinted, Material-You style ---------------------
    row3, d3 = new_row_canvas(3, "Monochrome (themed icon) tinted, Material-You style")
    tint_colors = [("Tint A", (103, 80, 164)), ("Tint B", (0, 108, 78)), ("Tint C", (140, 68, 24))]
    x, y = PAD, 40
    mono_hi_tile = _fit(hi_mono, TILE)
    for name, tint in tint_colors:
        bg_tint = tuple(min(255, int(c * 0.35 + 255 * 0.65)) for c in tint)
        circle_mask = _mask_circle(TILE)
        arr = np.array(mono_hi_tile)
        alpha_img = Image.fromarray(arr[:, :, 3], "L")
        glyph_tint = Image.new("RGBA", (TILE, TILE), tint + (255,))
        comp = Image.new("RGBA", (TILE, TILE), bg_tint + (255,))
        comp.paste(glyph_tint, (0, 0), alpha_img)
        final = Image.new("RGB", (TILE, TILE), (255, 255, 255))
        final.paste(comp.convert("RGB"), (0, 0), circle_mask)
        row3.paste(final, (x, y))
        d3.rectangle([x, y, x + TILE, y + TILE], outline=(150, 150, 150))
        d3.text((x, y + TILE + 2), f"monochrome, {name}", fill=(30, 30, 30), font=font_sm)
        x += TILE + PAD

    # --- Row 4: 48px actual-size row --------------------------------------
    AS = 48
    title4 = "Actual size (48dp @ 1x = 48px) — circle mask, various backgrounds"
    row4_w = max(PAD + 5 * (AS + PAD), _text_w(title4, font_h) + PAD * 2)
    row4 = Image.new("RGB", (row4_w, 40 + AS + PAD * 2), (255, 255, 255))
    d4 = ImageDraw.Draw(row4)
    d4.text((PAD, 8), title4, fill=(10, 10, 10), font=font_h)
    fg_48 = _fit(hi_fg, AS)
    x, y = PAD, 40
    for wall_name, wall in (("light", LIGHT_WALL), ("dark", DARK_WALL),
                             ("white", (255, 255, 255)), ("black", (0, 0, 0)),
                             ("magenta", (255, 0, 255))):
        tile = Image.new("RGB", (AS, AS), wall)
        mask = _mask_circle(AS)
        tile.paste(fg_48, (0, 0), mask)
        row4.paste(tile, (x, y))
        d4.rectangle([x, y, x + AS, y + AS], outline=(150, 150, 150))
        d4.text((x, y + AS + 2), wall_name, fill=(30, 30, 30), font=font_sm)
        x += AS + PAD

    # --- Row 5: side-by-side fidelity comparison with source -------------
    l, t, r, b = crop_box
    pad_src = 20
    src_crop_box = (max(l - pad_src, 0), max(t - pad_src, 0),
                     min(r + pad_src, source_img.width), min(b + pad_src, source_img.height))
    src_crop = source_img.crop(src_crop_box)
    CMP = 320
    src_crop_resized = src_crop.resize(
        (CMP, int(CMP * src_crop.height / src_crop.width)), Image.LANCZOS)

    # generated result placed at the SAME relative position/scale as it
    # appears in src_crop_resized (i.e. inset by pad_src, scaled the same).
    gen_scale = CMP / src_crop.width
    gen_size = int((r - l) * gen_scale)
    gen_tile_img = _fit(hi_fg, gen_size)
    result_tile = Image.new("RGB", src_crop_resized.size, (255, 255, 255))
    off_x = int((l - src_crop_box[0]) * gen_scale)
    off_y = int((t - src_crop_box[1]) * gen_scale)
    result_tile.paste(gen_tile_img, (off_x, off_y))

    title5 = "Fidelity comparison: source (cropped) vs. generated foreground (same crop/scale)"
    row5_h = 40 + max(src_crop_resized.height, result_tile.height) + PAD * 2
    row5_w = max(PAD + 2 * (CMP + PAD) + 20, _text_w(title5, font_h) + PAD * 2)
    row5 = Image.new("RGB", (row5_w, row5_h), (255, 255, 255))
    d5 = ImageDraw.Draw(row5)
    d5.text((PAD, 8), title5, fill=(10, 10, 10), font=font_h)
    row5.paste(src_crop_resized, (PAD, 40))
    d5.rectangle([PAD, 40, PAD + src_crop_resized.width, 40 + src_crop_resized.height], outline=(150, 150, 150))
    d5.text((PAD, 40 + src_crop_resized.height + 2), "source (cropped)", fill=(30, 30, 30), font=font_sm)
    x2 = PAD + CMP + PAD
    row5.paste(result_tile, (x2, 40))
    d5.rectangle([x2, 40, x2 + result_tile.width, 40 + result_tile.height], outline=(150, 150, 150))
    d5.text((x2, 40 + result_tile.height + 2), "generated foreground (opaque, white-flattened)", fill=(30, 30, 30), font=font_sm)

    # --- Row 6: pixel-registered 50%-opacity overlay on the source -------
    overlay_base = src_crop_resized.convert("RGBA")
    overlay_glyph = Image.new("RGBA", overlay_base.size, (0, 0, 0, 0))
    gen_rgba = gen_tile_img.convert("RGBA")
    r_, g_, b_, _ = gen_rgba.split()
    faded = Image.merge("RGBA", (r_, g_, b_, Image.new("L", gen_rgba.size, 128)))
    overlay_glyph.paste(faded, (off_x, off_y), faded)
    overlay_composite = Image.alpha_composite(overlay_base, overlay_glyph).convert("RGB")

    title6 = "Alignment check: generated foreground at 50% opacity, overlaid on the source (pixel-registered)"
    row6_w = max(PAD + overlay_composite.width + PAD, _text_w(title6, font_h) + PAD * 2)
    row6_h = 40 + overlay_composite.height + PAD * 2
    row6 = Image.new("RGB", (row6_w, row6_h), (255, 255, 255))
    d6 = ImageDraw.Draw(row6)
    d6.text((PAD, 8), title6, fill=(10, 10, 10), font=font_h)
    row6.paste(overlay_composite, (PAD, 40))
    d6.rectangle([PAD, 40, PAD + overlay_composite.width, 40 + overlay_composite.height], outline=(150, 150, 150))
    d6.text((PAD, 40 + overlay_composite.height + 2),
             "source + generated foreground @ 50% opacity, same scale/position", fill=(30, 30, 30), font=font_sm)

    # --- Row 7: measurement report as text --------------------------------
    lines = [
        f"Plate centre (source px): ({report['plate_center'][0]:.1f}, {report['plate_center'][1]:.1f})   "
        f"Plate diameter: {report['plate_diameter']:.1f}px",
        f"Crop box (source px): {report['crop_box']}   Crop size: {report['crop_size_px']}px square",
        f"Coverage thresholds: S in [{report['s_lo']}, {report['s_hi']}], V in [{report['v_lo']}, {report['v_hi']}]",
        f"Glyph bbox: {report['glyph_bbox_dp'][0]:.2f} x {report['glyph_bbox_dp'][1]:.2f} dp   "
        f"Max radius from canvas centre: {report['max_radius_dp']:.2f}dp (safe-zone limit 33.0dp)",
    ]
    row7_h = 40 + len(lines) * 22 + PAD
    row7_w = max(600, max(_text_w(t, font_sm) for t in lines) + PAD * 2)
    row7 = Image.new("RGB", (row7_w, row7_h), (255, 255, 255))
    d7 = ImageDraw.Draw(row7)
    d7.text((PAD, 8), "Measurement report", fill=(10, 10, 10), font=font_h)
    yy = 40
    for line in lines:
        d7.text((PAD, yy), line, fill=(30, 30, 30), font=font_sm)
        yy += 22

    # --- Assemble ----------------------------------------------------------
    rows = [row1, row2, row3, row4, row5, row6, row7]
    width = max(r_img.width for r_img in rows)
    total_h = sum(r_img.height for r_img in rows) + PAD * (len(rows) + 1) + 50
    sheet = Image.new("RGB", (width, total_h), (250, 250, 250))
    ds = ImageDraw.Draw(sheet)
    ds.text((PAD, 10), "Open Launcher adaptive app icon — review sheet (raster pipeline)", fill=(0, 0, 0), font=font_h)
    y = 50
    for r_img in rows:
        sheet.paste(r_img, (0, y))
        y += r_img.height + PAD

    out_path = os.path.join(out_dir, "preview.png")
    sheet.save(out_path)
    print("wrote", out_path, sheet.size)
    return out_path
