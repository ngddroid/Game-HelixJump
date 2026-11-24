# Spindle Drop icon generator

`make_icon.py` renders the Spindle Drop launcher icon, splash icon and Play Store
art from code. It uses only Python 3 and Pillow (no numpy, no external images).
The only files it reads are the bundled fonts in `app/src/main/assets/fonts/`,
which it needs for the feature graphic.

```bash
python3 tools/icon/make_icon.py                     # regenerate every output
python3 tools/icon/make_icon.py --preview /tmp/sd   # also write preview sheets
python3 tools/icon/make_icon.py --skip-feature      # skip the 1024x500 banner
```

A full run takes a few seconds. You can run it from any working directory,
because paths are resolved relative to the script.

## Outputs

| Output | Size | Notes |
|---|---|---|
| `app/src/main/res/mipmap-*/ic_launcher_foreground.png` | 108/162/216/324/432 px | Adaptive foreground: ball, spiral tower, glow and outline on transparent. Key art sits in the central 66 dp safe circle. |
| `app/src/main/res/mipmap-*/ic_launcher_background.png` | same | Adaptive background: radial violet-to-magenta burst with rays, sparkles and dust (opaque). |
| `app/src/main/res/mipmap-*/ic_launcher_monochrome.png` | same | Themed-icon layer (Android 13+): a white silhouette with cut-out separations and a highlight notch. |
| `app/src/main/res/mipmap-*/ic_launcher.webp` / `ic_launcher_round.webp` | 48/72/96/144/192 px | Legacy icons for API 24-25: the central 72 dp of the full icon in a rounded square or circle, with a soft shadow. Saved as lossless WebP. |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` | - | Adaptive icon XML that points to the three mipmap layers. |
| `app/src/main/res/drawable-nodpi/splash_icon.png` | 1152 x 1152 | Android 12+ SplashScreen icon: the ball and mini spiral on transparent, fitted inside the central 768 px circle. |
| `docs/store/icon_512.png` | 512 x 512, RGB | Play Store icon: the full icon with no transparency. Google Play applies its own corner mask. |
| `docs/store/feature_graphic_1024x500.png` | 1024 x 500, RGB | Play feature graphic: the burst background, the artwork, the "Spindle Drop" title in Lilita One and the "Spin · Drop · Smash" tagline in Fredoka. |

When you run it, the script also deletes the old template vectors
`res/drawable/ic_launcher_foreground.xml` and `ic_launcher_background.xml`.

## How it works

* Each element is rendered on a 1728 px master canvas. That is the 108 dp
  adaptive canvas at 16 px/dp, or 4x supersampling of xxxhdpi. The script then
  downscales the canvas with LANCZOS for every density, which gives clean
  anti-aliasing.
* Shading is done per pixel in pure Python:
  * The ball is a lit sphere. It has half-Lambert diffuse shading, a hot-pink to
    orange base, a warm bounce rim, a Blinn specular highlight, a glossy window
    highlight and a small glint.
  * Each ring is a flat disc slab seen from slightly above (an ellipse with
    `K = 0.34`). It has a wedge gap, lit outer and cut walls, a bevelled rim and
    ambient occlusion near the pillar.
  * The pillar is a cream cylinder.
* Layers are combined with the painter's algorithm, bottom ring first, so the
  pillar and lower ring show through the gaps.
* The background is a per-pixel radial ramp with a little dither against
  banding. Rays, sparkles and dust are drawn at 4x and downscaled.
* The monochrome layer is built from the same element masks. A 1.4 dp
  transparent gap separates overlapping parts, so the themed icon keeps its
  depth.

## Tweaking

Scene geometry is in dp near the top of the script: `BALL_C`, `BALL_R`,
`RING1`, `RING2`, `PILLAR_*` and `K`. Colours are the brand palette constants
(`VIOLET`, `MAGENTA`, `HOT_PINK`, `SUN`, `MINT` and others). Keep the ball and
the rings inside the 33 dp radius around (54, 54) so launcher masks never clip
them. Use `--preview` to check the circle and squircle masks at 48 to 192 px
and the themed (monochrome) rendering before you commit.

The design is original to Spindle Drop: a glossy candy ball over a mint and
cream spiral tower on a violet burst. Please don't replace it with assets from
other games.
