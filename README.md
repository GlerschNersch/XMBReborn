# XMB Reborn

An Android home-screen launcher in the style of the PlayStation 3's XrossMediaBar. Categories
run across, their items run down, and an animated wave and sparkles drift behind them, with
the month's background colour shifting between day and night.

It's a clean-room recreation. The layout, motion timing, colours, lighting and sound shapes were
measured from the real interface, and everything in the app was written from those measurements.
**No Sony code, artwork, fonts or sounds are included.**

<p align="center">
  <img src="docs/screenshots/landscape.png" width="62%" alt="The bar in landscape: categories across, the Settings column down">
  <img src="docs/screenshots/portrait.png" width="22%" alt="The bar in portrait">
</p>
<p align="center">
  <img src="docs/screenshots/theme-color.png" width="62%" alt="The options panel choosing a theme colour, previewed live">
</p>

## Install

1. Download `XMBReborn-<version>.apk` from [Releases](../../releases).
2. Open it on your phone and allow installing from that source when Android asks.
3. Press Home and choose **XMB Reborn**. To change it later, go to Settings → Apps → Default apps →
   Home app.

Requires Android 10 or newer and OpenGL ES 3.

## Using it

- **Swipe sideways** to change category and **up and down** to move through a list. Tap a row to
  focus it, then tap it again to open it. D-pads and game controllers work too: Ⓐ opens, Ⓑ goes
  back, and Ⓨ/Menu opens options.
- **Long-press** a row (or press Ⓨ/Menu) for its options: Open, App Info, Hide, Uninstall.
- **Settings** column:
  - **Theme Color:** Original follows the month, or pick one of the 12 month colours. The
    background previews each colour as you scroll.
  - **Ambient Sound:** a soft background pad of our own (the PS3 has none). It pauses whenever
    another app plays media.
  - **Interface Sounds:** the menu clicks.
  - **Startup Sound:** plays once after the phone powers on, if you've supplied a sound (see below).
  - **Hidden Apps:** bring back apps you've hidden.
  - Shortcuts into Android's own settings.
- Apps are grouped by the category the developer gave them (Photo, Music, Video, Game, Network),
  most recently opened first. **Apps** lists everything A–Z.

## Using your own PS3 sounds (optional)

The app makes its own menu clicks. If you own a PS3 firmware copy, you can have it play the
original sounds and boot chime on your own phone instead. You'll need Python, ffmpeg and adb on a
computer:

```sh
# Writes out/sounds/snd_cursor.wav, snd_decide.wav, snd_cancel.wav, snd_option.wav (and more)
python -I re/tools/rco.py <dev_flash>/vsh/resource/system_plugin.rco out/
# The boot chime
ffmpeg -i <dev_flash>/vsh/resource/coldboot_stereo.ac3 -c:a aac out/sounds/coldboot.m4a

D=/sdcard/Android/data/dev.xmbreborn/files/sounds
adb shell mkdir -p $D
adb push out/sounds/snd_cursor.wav out/sounds/snd_decide.wav out/sounds/snd_cancel.wav          out/sounds/snd_option.wav out/sounds/coldboot.m4a $D/
adb shell am force-stop dev.xmbreborn   # it picks them up when it next opens
```

(On Windows Git Bash, run `export MSYS_NO_PATHCONV=1` first.) The files stay on your phone and are
never part of the app. Delete the folder to go back to the built-in clicks.

## Building

```sh
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest
```

Needs JDK 17 and the Android SDK (compileSdk 36). Release builds are signed with the key named
in your `~/.gradle/gradle.properties` (`XMB_KEYSTORE`, `XMB_KEYSTORE_PASSWORD`, `XMB_KEY_ALIAS`,
`XMB_KEY_PASSWORD`), or with the local debug key if those aren't set.

## How it was made

`docs/RE_NOTES.md` records the measurements: firmware file formats, the layout and motion
captured from the real XMB running in RPCS3, background tone curves, icon lighting, particle
density and sound envelopes. `re/tools/` has the scripts that produced them. They read a
firmware you supply and ship nothing from it.

## Licence

MIT; see [LICENSE](LICENSE). This covers this project's own code and docs only.

## Disclaimer

XMB Reborn is an unofficial fan project. It isn't affiliated with or endorsed by Sony Interactive
Entertainment. PlayStation, PS3 and XrossMediaBar (XMB) are trademarks of Sony Interactive
Entertainment Inc.
