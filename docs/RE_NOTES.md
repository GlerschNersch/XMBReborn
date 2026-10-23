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

- ~~Motion~~: measured, see below. (Originally: focus scale, spacing, easing and durations, in
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

## Second capture session (RPCS3, 21 Oct 08:19–08:28, day)

Captures are in `re/out/captures/` (gitignored): `vert`, `horiz`, `opts`, `idle` (.mp4 + key logs),
`day_oct_0819.png`, `opts_open.png`, `icons_full.png`. `regions.py` gives per-frame box stats.
If the XMB sits idle it goes to a black "Press and hold START" screen and then the VSH shuts
down (`SceVshForceShutdown` in the log). Ctrl+P resumes RPCS3 itself; after a shutdown, reboot vsh.self.

Day background: the flip holds by day too (screen top = texture's bottom row). After the
BACKGROUND.mnu corner multipliers (1, 1, 0.847, 0.924 = tl, tr, bl, br on screen), day reaches the
screen as ≈ 0.303·c + 0.112 (a flat white haze), rms 10/255 against the frame, worst at the left edge.
Night refits to 0.342·c^1.73 with the multipliers. A bright band along the very top edge (y < 60)
isn't modelled.

Vertical motion: same exponential as horizontal, 22–27% of the remaining distance per frame,
τ ≈ 0.05–0.06 s (tracked on the uniformly spaced rows, so no smearing).

Column change: the lists travel with their columns and cross-fade **linearly over the whole move**.
The old list's opacity is exactly 1 − progress (0.24→0.76, 0.42→0.58, 0.67→0.34, 0.90→0.09), so both
lists are half visible mid-move. Bar icon size also lerps linearly with distance (mid-move widths
within ±3 px of the lerp).

Options (Triangle) menu:
- The panel spans from x 880 to the right edge, full height. A 1 px hairline marks the left edge,
  then a white overlay at 20% opacity, flat to x ~950, falling linearly to 0 by x ~1140.
- Text at x 890, ~17 px. Rows 27 apart. Column-wide options (Sort By, Group Content) sit just under
  the bar (y 207, 234). Item options start 30 below the focused row (y 367, 394, 420). The focused
  option is white, the others dimmer. Submenus show a ▸ at x ~1050.
- Opening: the rows above, the clock and the "Options" hint fade out (τ ≈ 0.055 s, done in ~0.12 s).
  The focused row gets a darker band across to the panel and its label dims and shifts right.
  About 0.13 s after the press the panel appears at x ~906 and eases left to 880 (τ ≈ 0.09 s).
- Closing reverses it: the panel slides right ~40 px and fades within ~0.17 s while the rest comes back.

Wave and particles: `idle.mp4` (9 s, 30 fps). In 2 s the ribbon's height and tilt change a lot
(it drifts 100–200 px vertically across the screen). It's reference for tuning `Wave.kt`'s speed.

## Sounds and theme colours (21 Oct)

Interface sounds (`system_plugin.rco`): `snd_cursor`, `snd_decide`, `snd_option` and
`snd_category_decide` are **the same sound**. It's a 40 ms click centred near 6.84 kHz with faint
partials at 8.9 kHz (−17 dB) and 4.87 kHz (−19 dB), peak 0.09 FS. Nearly all its energy is in the
first 4 ms (window peaks 1, 0.23, 0.14, 0.11 … of the peak). `snd_cancel` is the same click at
6.45 kHz and about half the level. `Sfx.kt` synthesizes clicks to this description: a struck
partial with a 2.7 ms + 12 ms double decay. Rendered, its 4 ms windows run 0.135 / 0.041 / 0.020
against the original's 0.160 / 0.037 / 0.023 (scaled to the same peak).

There's no ambient loop anywhere in dev_flash. The only long sounds are the boot chime
(`coldboot_*.ac3`, `coldboot.raf`) and the Store's own effects (`newstore/sound/*.vag`). The
launcher's optional ambient bed (`Ambient.kt`) is therefore original material, not a recreation.

Theme colours: the only background colours in the firmware are the 24 month gradients (12 day +
12 night). The colour setting (`sysconf_plugin` → `page_theme_config_color`) can therefore only
choose one of those 12 months or "Original" (follow the calendar), and that's what the launcher
offers. The setting's text table didn't decode (the RCO text section needs decompressing), so the
option names are ours.

## Wave speed and icon look (21 Oct)

Wave speed, from `idle.mp4`: on columns clear of UI (x 1000/1120/1240) the ribbon's brightest
ridge drifts about 140 px in 9 s, a mean of 15–17 px/s on the 720 px reference. Our `Wave.kt`
centre line gives 14.5–17.9 px/s and sweeps a similar band of heights (≈300–515 vs the 320–490 seen
in 9 s), so it already matches and is unchanged. A 9 s sample can't pin the longer periods.

Icons, from `icons_full.png`: over an October-day background (115, 94, 42), the focused Settings
icon's flat face reads (210, 199, 150) and an unfocused body (184, 170, 122) over (126, 104, 50).
That fits a warm, background-tinted colour at ~0.68 opacity focused and ~0.4 unfocused (× the
bar's 0.6). The edges are pillowed: a lighter top bevel, a ~30% darker lower bevel, and barely any
drop shadow. Our glyphs bake that in (`IconLighting`: blurred-silhouette height field lit by
ICONS.mnu's two lights). They're drawn at `IconTint.OPACITY` with a tint 30% toward the
background's hue, which is a stand-in for the PS3's environment reflection.

## Particles (21 Oct)

From `idle.mp4` / `icons_full.png` (x 760–1280, below the clock): about 160 small bright spots in
that region per frame (~470 per screen at the PS3's density). They're mostly 1–2 px (p90 5 px),
peaking 34 (median) to 72 (p90) above the background, and gather around and above the ribbon,
densest at its bright end, with almost none below. A few are large faint out-of-focus discs, and
some carry an iridescent pink, cyan or gold tint. Drift: median 15 px/s, no net direction
(mean vx −0.5, vy +1.6 px/s). `Particles.kt` runs 260 to that description: spawned on the wave's
centre line in PARTICLES.mnu's 52° cone (17% sent downward), with friction, Brownian jitter, a
slight lift and a 4–10 s life.

User-supplied sounds: `Sfx` plays `files/sounds/snd_{cursor,decide,cancel,option}.wav` from the
app's private storage if present (16-bit PCM WAVs as `rco.py` writes them), else our synthesized
clicks. To install your own, push them and copy them in with `adb shell run-as dev.xmbreborn`
(in Git Bash set `MSYS_NO_PATHCONV=1` first). Nothing of Sony's is in the APK or the repo.

Start-up sound: `BootChime` plays `files/sounds/coldboot.m4a` (the user's own, e.g.
`ffmpeg -i dev_flash/vsh/resource/coldboot_stereo.ac3 -c:a aac coldboot.m4a`, a 10 s stereo
chime) once per device boot (`Settings.Global.BOOT_COUNT`). It only counts as played after 2 s, so
the pause that follows launch, or power-on behind the lock screen, doesn't use it up. Ambient waits
for it to finish.

Density check (landscape on the 2772×1272 phone vs `icons_full.png`, same detector, 720-high
scale, clear area right of the list): PS3 46.9 spots per 100k px², ours 16.3 with 260 particles and
47.3 with 750. Sizes (median 2 px) and peaks (35 vs 40) already matched.

Cost (release build, phone): ~14% of one core in total (GL ~9%, ambient synth ~2%). Debug builds
read 3–4× higher because ART limits JIT for debuggable apps, so measure release. `run-as` (used to
copy sound files in) only works on the debug build.

## Message sounds and Settings folders (21 Oct, evening)

`re/tools/sound_shape.py` prints a sound's 10–50 ms envelope and strongest partials.

- `snd_system_ok` (0.30 s, peak 0.027 FS, about a third of the click): one partial at 3.14 kHz,
  held for about 60 ms while falling only 4 dB, cut off over a few ms, then a faint tail about
  30 dB down for the next 0.2 s.
- `snd_system_ng` (0.80 s, peak 0.071 FS): a 1.05 kHz partial (faint 3.14 kHz above) swelling
  over 20–30 ms and down 30 dB by 110 ms. A rough, fluttering tail sits about 26 dB down from
  150 to 320 ms, then fades out by about 550 ms.
- `snd_category_decide` is the same click as `snd_cursor`, so it adds nothing.

`Sfx.kt` synthesizes both from these figures (`okBeep`, `errorBuzz`) when the user hasn't supplied
their own files.

Settings structure (`category_sysconf.xml`): the PS3's Settings column is a list of folders
(`msg_theme_settings`, `msg_sound_setting`, `msg_datetime_setting` …), each a `child` view with its
own items and `icon_rsc`. The launcher mirrors that: Theme, Sound and Home Settings folders,
plus Android's settings in a folder of their own. The open-folder motion wasn't captured. Ours
slides the bar one column left per folder (`XmbMetrics.FOCUS_TIME_CONSTANT`, as the bar) and keeps
the folder's icon in the focused row's line. In portrait the slide is capped so that icon stays
on screen.

## `qgl/canyon.qrc` and `qgl/earth.qrc`

Both are separate animated scenes with their own shaders and plain-text `.mnu` parameters
(`#MNU_1.0`, then `NAME:float:value` per line). `re/tools/mnu_table.py` gathers them into a table.

- **Canyon:** 58 presets (`override_<name>_canyon_*.mnu`, e.g. `Bir_A01`…) over 47 parameters
  in six groups:
  - camera: position, target, speed, look-ahead time, zoom
  - terrain: height, width, slide, X scale, bump; valley height, centre, width and sharpness
  - a glowing line ahead of the camera: distance, width, height, length
  - fog: near/far and colour
  - colour grading: scale and bias, line colour
  - feedback blur: focal depth and variance, feedback amount, scale and rotation

  The terrain is shaped by a normal map (`canyon_normalmap.dds`). A clean-room version would
  need our own procedural terrain.
- **Earth:** a globe with atmosphere scattering tables (`atm__*.dump`), clouds, sun, HDR and
  auto-exposure, several camera paths (`camera_4m_camera*.ini`, `camera.path`) and presets.

Where the PS3 shows these isn't confirmed yet (themes, music visualizers, something else?). Next
step: find them in RPCS3 and capture their motion, as we did for the wave.

**Built (2026-10-23) as Theme Settings > Background, from the parts listed above only.** No preset
values, textures, tables or shaders are used; both are our own full-screen fragment shaders
(`render/Canyon.kt`, `render/Earth.kt`), drawn at half resolution (`render/Offscreen.kt`):
- Canyon: raymarched procedural height field (value-noise fbm walls around a wandering centre
  line), fog and sky from the month gradient, and a guide line where the ray meets a plane above
  the valley floor.
- Earth: ray-sphere planet with fbm continents, ice, ocean glint, a second fbm cloud layer, city
  lights past the terminator, and a limb glow inside and outside the sphere. Spins are whole turns
  per hour, so the hourly clock wrap doesn't jump. Canyon's camera does jump once an hour.
If a capture ever shows the real scenes, re-tune camera, speed, fog and colours against it.
