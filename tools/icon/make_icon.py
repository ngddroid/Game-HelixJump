#!/usr/bin/env python3
"""
Spindle Drop - launcher icon, splash icon and store-art generator (v2 "Spiral Vortex").

Design: a top-down, slightly tilted rainbow spiral ramp (cyan -> mint -> yellow -> orange -> pink)
winding into a glowing core, with two gaps cut into it, and a glossy golden ball dropping into
the vortex with a comet trail - on a deep night-sky gradient with sparkles.

Pure Python 3 + Pillow (no numpy). Everything is drawn on a supersampled master canvas and
downscaled with LANCZOS for clean anti-aliasing.

    python3 tools/icon/make_icon.py               # write all outputs
    python3 tools/icon/make_icon.py --preview DIR # also write a preview sheet
"""
from __future__ import annotations

import argparse
import colorsys
import math
import random
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"
FONT_DIR = ROOT / "app" / "src" / "main" / "assets" / "fonts"
STORE = ROOT / "docs" / "store"

PX = 16                     # master px per dp
CANVAS = 108 * PX           # adaptive icon canvas (108 dp)
C = CANVAS / 2.0            # centre
TILT = 0.84                 # vertical squash: spiral seen from slightly above
DENSITIES = [("mdpi", 1.0), ("hdpi", 1.5), ("xhdpi", 2.0), ("xxhdpi", 3.0), ("xxxhdpi", 4.0)]


def dp(v: float) -> float:
    return v * PX


def hexc(h: str):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def ramp(stops, x):
    x = max(0.0, min(1.0, x))
    for i in range(len(stops) - 1):
        p0, c0 = stops[i]
        p1, c1 = stops[i + 1]
        if x <= p1:
            return mix(c0, c1, (x - p0) / max(1e-6, p1 - p0))
    return stops[-1][1]


def shade(c, k):
    if k >= 1:
        return tuple(int(min(255, v + (255 - v) * (k - 1))) for v in c)
    return tuple(int(v * k) for v in c)


def radial(size, center, radius, stops):
    """RGB radial gradient image."""
    w, h = size
    g = Image.radial_gradient("L").resize((int(radius * 2), int(radius * 2)), Image.BILINEAR)
    canvas = Image.new("L", size, 255)
    canvas.paste(g, (int(center[0] - radius), int(center[1] - radius)))
    lut = [ramp(stops, i / 255.0) for i in range(256)]
    r = canvas.point([c[0] for c in lut])
    gch = canvas.point([c[1] for c in lut])
    b = canvas.point([c[2] for c in lut])
    return Image.merge("RGB", (r, gch, b))


def proj(x, y):
    """Tilted top-down projection around the canvas centre (shifted down to balance the ball)."""
    return (C + x, C + dp(5.0) + y * TILT)


# --------------------------------------------------------------------------- background
def render_background(size=CANVAS) -> Image.Image:
    s = size / CANVAS
    bg = radial((size, size), (size * 0.5, size * 0.47), size * 0.78, [
        (0.0, hexc("#3A2BB8")), (0.35, hexc("#25157A")), (0.7, hexc("#140A4A")), (1.0, hexc("#0A0528")),
    ]).convert("RGBA")
    # Coloured nebula glows.
    for (cx, cy, r, col, a) in [(0.18, 0.2, 0.45, "#19C3FF", 95), (0.86, 0.84, 0.5, "#FF3D8B", 110), (0.5, 0.52, 0.3, "#8B5CFF", 120)]:
        glow = Image.new("L", (size, size), 0)
        ImageDraw.Draw(glow).ellipse([size * (cx - r), size * (cy - r), size * (cx + r), size * (cy + r)], fill=a)
        glow = glow.filter(ImageFilter.GaussianBlur(size * r * 0.45))
        layer = Image.new("RGBA", (size, size), hexc(col) + (0,))
        layer.putalpha(glow)
        bg = Image.alpha_composite(bg, layer)
    # Stars.
    rnd = random.Random(7)
    stars = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(stars)
    for _ in range(70):
        x, y = rnd.random() * size, rnd.random() * size
        r = (0.25 + rnd.random() * 0.55) * dp(1) * s * 0.55
        a = int(90 + rnd.random() * 140)
        d.ellipse([x - r, y - r, x + r, y + r], fill=(255, 255, 255, a))
    bg = Image.alpha_composite(bg, stars.filter(ImageFilter.GaussianBlur(0.6 * s * PX / 16)))
    return bg


# --------------------------------------------------------------------------- spiral
SPIRAL_COLORS = [(0.0, hexc("#FF3D8B")), (0.25, hexc("#FF8A3D")), (0.48, hexc("#FFD23F")),
                 (0.72, hexc("#3DDC97")), (1.0, hexc("#39C6FF"))]
TURNS = 2.05
R0, R1 = 8.5, 30.5          # dp, inner/outer radius of the spiral centre line
GAPS = [(0.60, 0.665)]      # one gap the ball can drop through
ANG0 = math.radians(150)


def spiral_pt(u, off=0.0):
    """Point on the spiral centre line at u in [0,1] offset radially by off (dp)."""
    a = ANG0 + u * TURNS * 2 * math.pi
    r = R0 + (R1 - R0) * u + off
    return (math.cos(a) * dp(r), math.sin(a) * dp(r))


def band_width(u):
    return 4.4 + 3.6 * u   # dp: thin at the core, wide outside


def in_gap(u):
    return any(a <= u <= b for a, b in GAPS)


def render_spiral() -> Image.Image:
    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    N = 900
    side = dp(2.6)
    # Soft drop shadow of the whole ramp.
    shadow = Image.new("L", (CANVAS, CANVAS), 0)
    sd = ImageDraw.Draw(shadow)
    for i in range(N):
        u0, u1 = i / N, (i + 1) / N
        if in_gap(u0):
            continue
        w0, w1 = band_width(u0) / 2, band_width(u1) / 2
        poly = [proj(*spiral_pt(u0, -w0)), proj(*spiral_pt(u0, w0)), proj(*spiral_pt(u1, w1)), proj(*spiral_pt(u1, -w1))]
        sd.polygon([(x + dp(1.2), y + dp(4.0)) for x, y in poly], fill=150)
    shadow = shadow.filter(ImageFilter.GaussianBlur(dp(2.2)))
    sl = Image.new("RGBA", (CANVAS, CANVAS), (8, 2, 30, 0))
    sl.putalpha(shadow)
    img = Image.alpha_composite(img, sl)

    d = ImageDraw.Draw(img)
    # Side walls first (inner-to-outer so outer turns overlap correctly), then top faces.
    for i in range(N):
        u0, u1 = i / N, (i + 1) / N
        if in_gap(u0):
            continue
        base = ramp(SPIRAL_COLORS, u0)
        w0, w1 = band_width(u0) / 2, band_width(u1) / 2
        o0, o1 = proj(*spiral_pt(u0, w0)), proj(*spiral_pt(u1, w1))
        poly = [o0, o1, (o1[0], o1[1] + side), (o0[0], o0[1] + side)]
        d.polygon(poly, fill=shade(base, 0.55) + (255,))
        i0, i1 = proj(*spiral_pt(u0, -w0)), proj(*spiral_pt(u1, -w1))
        d.polygon([i0, i1, (i1[0], i1[1] + side), (i0[0], i0[1] + side)], fill=shade(base, 0.42) + (255,))
    # Top face: 4 strips across the width for a rounded, lit look.
    strips = [(-1.0, -0.55, 0.78), (-0.55, 0.0, 0.92), (0.0, 0.6, 1.02), (0.6, 1.0, 1.12)]
    for i in range(N):
        u0, u1 = i / N, (i + 1) / N
        if in_gap(u0):
            continue
        base = ramp(SPIRAL_COLORS, u0)
        w0, w1 = band_width(u0) / 2, band_width(u1) / 2
        # Light comes from the upper-left: faces pointing that way are brighter.
        a = ANG0 + u0 * TURNS * 2 * math.pi
        facing = 0.92 + 0.12 * math.cos(a - math.radians(-130))
        for (s0, s1, k) in strips:
            poly = [proj(*spiral_pt(u0, w0 * s0)), proj(*spiral_pt(u0, w0 * s1)), proj(*spiral_pt(u1, w1 * s1)), proj(*spiral_pt(u1, w1 * s0))]
            d.polygon(poly, fill=shade(base, k * facing) + (255,))
    # Rounded end caps at gaps and ends.
    ends = [0.0] + [g[0] for g in GAPS] + [g[1] for g in GAPS] + [1.0]
    for u in ends:
        uu = min(0.999, max(0.0, u))
        base = ramp(SPIRAL_COLORS, uu)
        cx, cy = proj(*spiral_pt(uu))
        r = dp(band_width(uu) / 2)
        d.ellipse([cx - r, cy - r * TILT + side * 0.5, cx + r, cy + r * TILT + side], fill=shade(base, 0.5) + (255,))
        d.ellipse([cx - r, cy - r * TILT, cx + r, cy + r * TILT], fill=shade(base, 1.0) + (255,))
    # Glossy rim highlight along the outer edge.
    hl = Image.new("L", (CANVAS, CANVAS), 0)
    hd = ImageDraw.Draw(hl)
    pts = [proj(*spiral_pt(i / N, band_width(i / N) / 2 * 0.78)) for i in range(N + 1)]
    for i in range(N):
        if in_gap(i / N):
            continue
        hd.line([pts[i], pts[i + 1]], fill=200, width=int(dp(0.9)))
    hl = hl.filter(ImageFilter.GaussianBlur(dp(0.35)))
    white = Image.new("RGBA", (CANVAS, CANVAS), (255, 255, 255, 0))
    white.putalpha(hl)
    img = Image.alpha_composite(img, white)
    return img


def render_core() -> Image.Image:
    """Glowing vortex core at the centre of the spiral."""
    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    glow = Image.new("L", (CANVAS, CANVAS), 0)
    cx, cy = proj(0, 0)
    ImageDraw.Draw(glow).ellipse([cx - dp(9), cy - dp(9) * TILT, cx + dp(9), cy + dp(9) * TILT], fill=255)
    glow = glow.filter(ImageFilter.GaussianBlur(dp(4)))
    g = Image.new("RGBA", (CANVAS, CANVAS), hexc("#FFB8F0") + (0,))
    g.putalpha(glow.point(lambda v: int(v * 0.9)))
    img = Image.alpha_composite(img, g)
    d = ImageDraw.Draw(img)
    for k, col in [(5.0, "#2A0C5E"), (3.6, "#4A1690"), (2.2, "#160434")]:
        d.ellipse([cx - dp(k), cy - dp(k) * TILT, cx + dp(k), cy + dp(k) * TILT], fill=hexc(col) + (255,))
    return img


# --------------------------------------------------------------------------- ball
def ball_pos():
    """The ball drops straight into the vortex core."""
    cx, cy = proj(0, 0)
    return cx, cy - dp(11.0)


def render_ball(cx, cy, r) -> Image.Image:
    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # Concentric shading towards the upper-left highlight.
    steps = 90
    hx, hy = cx - r * 0.38, cy - r * 0.42
    dark, mid, light = hexc("#C4430F"), hexc("#FF9A1F"), hexc("#FFF1A3")
    for i in range(steps):
        t = i / (steps - 1)
        rr = r * (1 - t * 0.92)
        x = cx + (hx - cx) * t * 0.9
        y = cy + (hy - cy) * t * 0.9
        col = mix(dark, mid, min(1, t * 1.8)) if t < 0.55 else mix(mid, light, (t - 0.55) / 0.45)
        d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=col + (255,))
    # Clip to the ball.
    mask = Image.new("L", (CANVAS, CANVAS), 0)
    ImageDraw.Draw(mask).ellipse([cx - r, cy - r, cx + r, cy + r], fill=255)
    img.putalpha(ImageChops.multiply(img.getchannel("A"), mask))
    # Rim light (bottom-right, magenta bounce light).
    rim = Image.new("L", (CANVAS, CANVAS), 0)
    ImageDraw.Draw(rim).ellipse([cx - r, cy - r, cx + r, cy + r], outline=255, width=int(r * 0.16))
    rim = rim.filter(ImageFilter.GaussianBlur(r * 0.08))
    side = Image.new("L", (CANVAS, CANVAS), 0)
    ImageDraw.Draw(side).ellipse([cx - r * 0.2, cy - r * 0.2, cx + r * 1.6, cy + r * 1.6], fill=255)
    rim = ImageChops.multiply(ImageChops.multiply(rim, side.filter(ImageFilter.GaussianBlur(r * 0.3))), mask)
    rl = Image.new("RGBA", (CANVAS, CANVAS), hexc("#FF6FD8") + (0,))
    rl.putalpha(rim.point(lambda v: int(v * 0.8)))
    img = Image.alpha_composite(img, rl)
    # Specular window highlight.
    spec = Image.new("L", (CANVAS, CANVAS), 0)
    sd = ImageDraw.Draw(spec)
    sd.ellipse([cx - r * 0.66, cy - r * 0.70, cx - r * 0.02, cy - r * 0.28], fill=235)
    spec = spec.filter(ImageFilter.GaussianBlur(r * 0.06))
    sw = Image.new("RGBA", (CANVAS, CANVAS), (255, 255, 255, 0))
    sw.putalpha(ImageChops.multiply(spec, mask))
    img = Image.alpha_composite(img, sw)
    dot = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    ImageDraw.Draw(dot).ellipse([cx + r * 0.32, cy + r * 0.36, cx + r * 0.5, cy + r * 0.5], fill=(255, 255, 255, 150))
    img = Image.alpha_composite(img, dot.filter(ImageFilter.GaussianBlur(r * 0.03)))
    return img


def render_trail(cx, cy, r) -> Image.Image:
    """Tapered comet streak above the falling ball."""
    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    length = r * 3.4
    m = Image.new("L", (CANVAS, CANVAS), 0)
    d = ImageDraw.Draw(m)
    n = 80
    for i in range(n):
        t = i / n
        py = cy - r * 0.2 - t * length
        rr = r * (0.92 - t * 0.8)
        d.ellipse([cx - rr, py - rr, cx + rr, py + rr], fill=int(210 * (1 - t) ** 1.6))
    m = m.filter(ImageFilter.GaussianBlur(r * 0.18))
    glow = Image.new("RGBA", (CANVAS, CANVAS), hexc("#FFC94A") + (0,))
    glow.putalpha(m)
    img = Image.alpha_composite(img, glow)
    core = m.point(lambda v: int(max(0, v - 110) * 2.0))
    w = Image.new("RGBA", (CANVAS, CANVAS), (255, 255, 255, 0))
    w.putalpha(core)
    img = Image.alpha_composite(img, w)
    # Speed lines.
    lines = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    ld = ImageDraw.Draw(lines)
    for dx, l0, l1 in [(-1.35, 0.9, 2.6), (1.35, 1.1, 2.9), (-0.7, 2.2, 3.6)]:
        x = cx + dx * r
        ld.line([(x, cy - l0 * r), (x, cy - l1 * r)], fill=(255, 255, 255, 170), width=int(r * 0.12))
    return Image.alpha_composite(img, lines.filter(ImageFilter.GaussianBlur(r * 0.04)))


def render_sparkles(scale_alpha=1.0) -> Image.Image:
    img = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for (x, y, s) in [(25, 30, 3.2), (82, 24, 2.4), (86, 70, 2.8), (22, 74, 2.0)]:
        cx, cy, R = dp(x), dp(y), dp(s)
        pts = []
        for k in range(8):
            rr = R if k % 2 == 0 else R * 0.22
            a = -math.pi / 2 + k * math.pi / 4
            pts.append((cx + math.cos(a) * rr, cy + math.sin(a) * rr))
        d.polygon(pts, fill=(255, 255, 255, int(235 * scale_alpha)))
    glow = img.filter(ImageFilter.GaussianBlur(dp(1.0)))
    return Image.alpha_composite(glow, img)


def render_foreground(with_sparkles=True, with_glow=True) -> Image.Image:
    fg = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    if with_glow:
        fg = Image.alpha_composite(fg, render_core())
    fg = Image.alpha_composite(fg, render_spiral())
    bx, by = ball_pos()
    r = dp(8.6)
    if with_glow:
        # Soft shadow of the ball on the core / inner ramp.
        sh = Image.new("L", (CANVAS, CANVAS), 0)
        sx, sy = proj(0, 0)
        ImageDraw.Draw(sh).ellipse([sx - r * 0.85, sy - r * 0.32, sx + r * 0.85, sy + r * 0.32], fill=170)
        sh = sh.filter(ImageFilter.GaussianBlur(r * 0.3))
        shl = Image.new("RGBA", (CANVAS, CANVAS), (20, 6, 40, 0))
        shl.putalpha(sh)
        fg = Image.alpha_composite(fg, shl)
        fg = Image.alpha_composite(fg, render_trail(bx, by, r))
        g = Image.new("L", (CANVAS, CANVAS), 0)
        ImageDraw.Draw(g).ellipse([bx - r * 1.5, by - r * 1.5, bx + r * 1.5, by + r * 1.5], fill=150)
        g = g.filter(ImageFilter.GaussianBlur(r * 0.6))
        gl = Image.new("RGBA", (CANVAS, CANVAS), hexc("#FFB020") + (0,))
        gl.putalpha(g)
        fg = Image.alpha_composite(fg, gl)
    fg = Image.alpha_composite(fg, render_ball(bx, by, r))
    if with_sparkles:
        fg = Image.alpha_composite(fg, render_sparkles())
    return fg


# --------------------------------------------------------------------------- outputs
def save_png(img, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "PNG", optimize=True)
    print(f"  wrote {path.relative_to(ROOT)}  {img.size[0]}x{img.size[1]} {img.mode}")


def save_webp(img, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, "WEBP", lossless=True, quality=100, method=6)
    print(f"  wrote {path.relative_to(ROOT)}  {img.size[0]}x{img.size[1]} {img.mode}")


def rounded_mask(size, radius_frac=0.22):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size - 1, size - 1], radius=int(size * radius_frac), fill=255)
    return m


def circle_mask(size):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse([0, 0, size - 1, size - 1], fill=255)
    return m


def monochrome(fg: Image.Image) -> Image.Image:
    a = fg.getchannel("A").point(lambda v: 255 if v > 110 else int(v * 2.3))
    out = Image.new("RGBA", fg.size, (255, 255, 255, 0))
    out.putalpha(a)
    return out


def feature_graphic(full_art: Image.Image) -> Image.Image:
    W, H = 2048, 1000
    bg = radial((W, H), (W * 0.72, H * 0.5), W * 0.6, [
        (0.0, hexc("#3A2BB8")), (0.4, hexc("#22126E")), (1.0, hexc("#0A0528")),
    ]).convert("RGBA")
    for (cx, cy, r, col, a) in [(0.12, 0.15, 0.35, "#19C3FF", 80), (0.9, 0.9, 0.4, "#FF3D8B", 110)]:
        glow = Image.new("L", (W, H), 0)
        ImageDraw.Draw(glow).ellipse([W * (cx - r), H * (cy - r), W * (cx + r), H * (cy + r)], fill=a)
        glow = glow.filter(ImageFilter.GaussianBlur(W * r * 0.3))
        layer = Image.new("RGBA", (W, H), hexc(col) + (0,))
        layer.putalpha(glow)
        bg = Image.alpha_composite(bg, layer)
    art = full_art.resize((1060, 1060), Image.LANCZOS)
    bg.alpha_composite(art, (W - 1060 - 40, -30))
    d = ImageDraw.Draw(bg)
    try:
        title = ImageFont.truetype(str(FONT_DIR / "LilitaOne-Regular.ttf"), 210)
        sub = ImageFont.truetype(str(FONT_DIR / "Fredoka.ttf"), 64)
    except Exception:
        title = sub = ImageFont.load_default()
    for i, line in enumerate(["Spindle", "Drop"]):
        y = 210 + i * 220
        d.text((120 + 8, y + 12), line, font=title, fill=(10, 4, 30, 160))
        d.text((120, y), line, font=title, fill=(255, 255, 255, 255), stroke_width=14, stroke_fill=hexc("#1A0B3D"))
    d.rounded_rectangle([120, 700, 820, 800], radius=50, fill=(20, 8, 60, 200), outline=(255, 255, 255, 90), width=4)
    d.text((170, 716), "Spin · Drop · Smash", font=sub, fill=hexc("#FFD23F"))
    return bg.convert("RGB").resize((1024, 500), Image.LANCZOS)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", type=Path, default=None)
    args = ap.parse_args()

    print("Rendering master layers…")
    bg = render_background()
    fg = render_foreground()
    full = Image.alpha_composite(bg, fg)
    mono = monochrome(render_foreground(with_sparkles=False, with_glow=False))

    for name, dens in DENSITIES:
        size = int(round(108 * dens))
        d = RES / f"mipmap-{name}"
        save_png(fg.resize((size, size), Image.LANCZOS), d / "ic_launcher_foreground.png")
        save_png(bg.resize((size, size), Image.LANCZOS), d / "ic_launcher_background.png")
        save_png(mono.resize((size, size), Image.LANCZOS), d / "ic_launcher_monochrome.png")
        # Legacy icons (48 dp): crop the central 72 dp of the art (adaptive-icon visible area).
        legacy = int(round(48 * dens))
        crop = full.crop((dp(18), dp(18), dp(90), dp(90))).resize((legacy, legacy), Image.LANCZOS)
        sq = crop.copy(); sq.putalpha(rounded_mask(legacy))
        rd = crop.copy(); rd.putalpha(circle_mask(legacy))
        save_webp(sq, d / "ic_launcher.webp")
        save_webp(rd, d / "ic_launcher_round.webp")

    # Splash icon: 1152 px, art cropped tight to its bounds and fitted to the central circle.
    splash = Image.new("RGBA", (1152, 1152), (0, 0, 0, 0))
    art_full = render_foreground()
    bbox = art_full.getchannel("A").point(lambda v: 255 if v > 40 else 0).getbbox()
    art = art_full.crop(bbox)
    scale = 980 / max(art.size)
    art = art.resize((int(art.size[0] * scale), int(art.size[1] * scale)), Image.LANCZOS)
    splash.alpha_composite(art, ((1152 - art.size[0]) // 2, (1152 - art.size[1]) // 2))
    save_png(splash, RES / "drawable-nodpi" / "splash_icon.png")

    save_png(full.crop((dp(9), dp(9), dp(99), dp(99))).resize((512, 512), Image.LANCZOS).convert("RGB"), STORE / "icon_512.png")
    save_png(feature_graphic(full.crop((dp(6), dp(6), dp(102), dp(102)))), STORE / "feature_graphic_1024x500.png")

    if args.preview:
        args.preview.mkdir(parents=True, exist_ok=True)
        sheet = Image.new("RGB", (1500, 560), (30, 30, 40))
        big = full.crop((dp(9), dp(9), dp(99), dp(99))).resize((512, 512), Image.LANCZOS)
        sheet.paste(big.convert("RGB"), (20, 24))
        x = 560
        for s in (192, 144, 96, 48):
            ic = full.crop((dp(18), dp(18), dp(90), dp(90))).resize((s, s), Image.LANCZOS)
            ic.putalpha(circle_mask(s))
            sheet.paste(ic, (x, 280 - s // 2), ic)
            x += s + 40
        m = mono.resize((160, 160), Image.LANCZOS)
        sheet.paste(Image.new("RGB", (160, 160), (60, 60, 70)), (1300, 30))
        sheet.paste(m, (1300, 30), m)
        sheet.save(args.preview / "preview_sheet.png")
        print(f"  preview -> {args.preview / 'preview_sheet.png'}")


if __name__ == "__main__":
    main()
