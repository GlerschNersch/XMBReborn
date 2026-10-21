# PS3 XMB reverse-engineering notes

Source: the firmware an RPCS3 install has already decrypted (`<rpcs3>/dev_flash`), CEX 4.93.
Extracted output lives in `re/out/` (gitignored). It is reference material only.
The launcher must ship our own art, sounds and shaders, rebuilt from these measurements.

Re-run everything with the scripts in `re/tools/` (all `python -I`).

## Container formats (all big-endian)

| Format | Magic | Tool | Notes |
|---|---|---|---|
| `.qrc` | `QRCC` + u32 size + zlib → `QRCF` | `paftree.py` | PAF tree of `<file src id [size]>`. If `size` is present, the blob is zlib too |
| `.p3t` | `P3TF` | `paftree.py` | Same PAF tree. Icons are zlib'd GIMs, backgrounds are JPEG |
| `.rco` | `FRP\0`, v0x130 | `rco.py` | Table block is zlib'd at 0xB0 and addressed as if inflated at 0xA4. Text, image and sound data are raw at header offsets |
| `.gim` | `.GIM1.00\0PSP` | `gim.py` | Block tree. Image block id 4, palette id 5. Mostly RGBA8888 |
| VAG | `VAGp` | `vag.py` | PS-ADPCM, 48 kHz. Stereo = two VAGp streams back to back |

PAF tree header: magic, version, then (offset, size) pairs for tree, id table,
name table, int array, float array and file data. Node = 7×u32 (name, nattr, parent,
prev, next, first_child, last_child) + nattr × (name, type, v0, v1).
Attr types: 1 int, 2 float, 3 string, 4 int[], 5 float[], 6 file(off, size), 7 id (id-table off; string at +4).

RCO entry: u8 type, u8 subtype, u16, label, ?, first_child_off, n_children, next_sibling_off, …(0x28 total).
**PS3 swaps the type IDs: 5 = model, 6 = sound** (rcomage's PSP numbering is wrong here).
Image payload: u16 fmt (5 = GIM), u16 comp (1 = zlib), u32 packed, u32 offset, u32 ?, u32 unpacked.
Sound payload: u16 fmt (1 = VAG), u8 channels, u8, u32 total, u32 offset.

## Wave background: `qgl/lines.qrc`

The "wave" is a spline-driven line ribbon (`spurs/moyou/spline/spline.elf`, run on SPU),
bent by a 2-stage FFD, shaded with Fresnel, then HDR tone-map + glare. There's also a
separate particle field. **All tuning ships as plain text `.mnu` files**:

- `BACKGROUND.mnu`: 4 corner RGB multipliers, FOVY 71.846
- `LINE1.mnu`: DAMPING 0.0001, LENGTH 0.306, TENSION 0.25, SPACING 407.66,
  BRIGHTNESS 0.70, FRESNEL 0.639, FALLOFF 1.003, TIMESTEP 4, PERTURBATION 0.0999,
  pos (-7.68, -1.09, -6.40), ang (-0.994, 0.087, 0.065) rot 18.12°, end (3, 0.4, 0.2),
  FFD scale1 (5.677, 1.0008, 1), scale2 (2.828, 1.276, 2.888), offset y -0.47, param1 -1.335
- `HDR.mnu`: exposure 1.05, white 0.899, glare 1.102 above thresh 0.739, gaussian radii
  R/G/B 1.239/1.432/1.558
- `PARTICLES.mnu`: emit rate, cone, wind, brownian, DOF, size near/mid/far, iridescent tint (`proc_iridescent.tga`)
- `override/<mode>/` variants: `day`, `night`, `yoake` (dawn), `higure` (dusk), `black`,
  `bright`, `coldboot1-2`, `gameboot1-5`, `welcome_1-2`, `music_1`, `initial_setting`

Background colour = `textures/month_bg/{rgb,night}/MM.dds` (64×32 gradients), one per month,
day and night. Corner/centre samples are in `re/out/month_bg/month_colors.json`.
1 silver, 2 yellow-gold, 3 green, 4 pink, 5 dark green, 6 purple, 7 teal, 8 deep blue,
9 magenta, 10 orange, 11 brown, 12 red. Night variants fade to near-black at the bottom.

## Icons: `qgl/icons.qrc` + `qgl/icontex.qrc`

Stock icons are **128×128 tangent-space normal maps** (`1n`–`70n`, DDS) plus a shared `dif`.
They're lit live, which is where the glassy, embossed look comes from. `ICONS.mnu`:
2 directional lights (L1 rotZ 22.8° rotX -32.4°, L2 rotZ 60° rotX 135°), spec pow 100/30,
ambient (0.74, 0.80, 0.90), env attenuation 0.74 with `texenv.dds`, chromatic refraction
R/G/B -0.032/-0.038/-0.048, drop shadow offset (0.07, 0.047) strength 0.02, exposure 0.82.
Our version: author our own normal maps and port this lighting model to a GLES fragment shader.

Themes (`.p3t`) replace these with flat 128×128 RGBA icons (`icon_user`, `icon_setting`, …,
~65 ids; that list is the full set of themeable slots).

## Menu structure: `explore/xmb/*.xml` (XMBML)

`<View id>` → `<Attributes><Table key><Pair key>` + `<Items><Query class key src attr>`.
Item tables carry `icon_rsc`, `title_rsc`, `info_rsc`, `item_type`, `child`, `focus_priority`.
`src` is `#view` (local), `xcb://localhost/query?table=…` (media DB query) or
`sel://localhost/…` (native provider). This maps cleanly onto Android: queries become
PackageManager/MediaStore providers.

Categories, in order: user, sysconf, photo, music, video, tv, game, network, psn, friend.
`xmb_plugin_normal.rco` → `xmenu` object at (-395, 40) on the 1280×720 canvas, with child
lists `list_user` … `list_friend`. Bar events: `native:/Bar::onInit/onPush/onContextMenu/
onScrollIn/onScrollOut/onCursorMove`.

## Sounds: `system_plugin.rco`

`snd_cursor`, `snd_decide`, `snd_cancel`, `snd_category_decide`, `snd_option` (~40 ms ticks),
`snd_system_ok` 0.30 s, `snd_system_ng` 0.80 s, `snd_trophy` 1.0 s. Boot: `coldboot.raf`
(+ `coldboot_{stereo,multi}.ac3`, `qgl/raf.qrc`).

## Not yet extracted

- Motion: focus scale, icon spacing, scroll easing and durations. They're in
  `explore_plugin.sprx` (PPU code). Plan: measure them behaviourally by booting
  `dev_flash/vsh/module/vsh.self` in RPCS3 and frame-capturing navigation, instead of
  disassembling.
- Shader maths: `.vpo`/`.fpo` are compiled RSX microcode. Rebuild from the `.mnu` params and
  captured frames. If an exact formula matters, RPCS3's RSX decompiler can emit GLSL.
- `coldboot.raf` format.

## Measured layout and motion (RPCS3, 4.93, 1280×720, 2026-10-20)

Getting there: boot `dev_flash/vsh/module/vsh.self` (first boot compiles ~1500 PPU modules,
~25 min, then exits once; boot again). Initial Setup runs once. Input goes through a Keyboard pad
profile (`re/tools/xmb_keys.ps1`, `xmb_capture.ps1`). RPCS3 sometimes draws text on white boxes
or a white background for a few seconds after boot, then renders correctly.

Static layout (`XmbMetrics.kt` holds these):
- Bar centre y 193 (0.268 H). Focused column centre x 378 (0.295 W). Column step 135.
- Category icons ~56 px, focused ~76. Label centre 41 below the bar centre, ~15 px text.
- Focused row 144 below the bar, the row after it +116, then rows every 53.5. Rows above the
  bar: nearest at bar −113, then every 53. Unfocused row icons ~38, focused ~80.
- Titles start 78 px right of the column centre on every row (~21 px text). Subtitle ~12 px,
  ~20 px under the title.
- Clock: right-aligned text (right edge ~105 from the screen edge) on a translucent bar from
  x 887 to the right edge, y 52–88.

Motion: `motion.py` cross-correlates a screen strip frame to frame. Column changes are a pure
exponential approach with no ease-in, 24% of the remaining distance per 60 Hz frame
(0.24, 0.42, 0.56, 0.67, 0.75 …), τ ≈ 0.060 s, settled in ~0.27 s. Row changes settle in the
same ~0.23–0.28 s. (Non-uniform row spacing smears the strip correlation, so treat that as
the same τ.)

Background: the month textures are drawn **vertically flipped** (texture row 0 = screen bottom).
At night (20 Oct 23:57) the screen is black at the top and #371c01 at the bottom, against
night texture #d89200, which fits ≈ 0.29·c^1.73. The schedule comes from `BACKGROUND.mnu`:
NIGHT2DAY 0 → 5.17 h, DAY2NIGHT 18.50 → 20.33 h, NIGHT BLEND 0.5, DAYSPREAD 2.68,
NIGHT WHIT BIAS 0.486 (the last three aren't used yet).
