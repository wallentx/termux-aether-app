# Gboard voice input

Add `GBOARD` to `extra-keys` in `~/.termux/termux.properties` for a second
microphone key. `VOICE` uses Android's speech prompt; `GBOARD` uses the voice mode
selected in Gboard, including Rambler. Both can appear in the same layout.

```properties
extra-keys = [['ESC','TAB','CTRL','ALT','DRAWER','VOICE','GBOARD']]
```

Reload settings after editing. Tapping `GBOARD` focuses a transparent text editor
without replacing the extra-key row. Dictate, tap Gboard's checkmark, then its
Send key to insert the finished text into the original terminal session.
Insertion does not press Enter; line breaks and terminal control characters
are filtered. Tap `GBOARD` again or press Back to discard the draft. Leaving the
activity or moving focus away cancels it. Drafts are not saved across restarts.

## Automatic microphone tap

The key always provides the editor. Automatic microphone activation additionally
requires the connected Shizuku session service and a local calibration at
`~/.termux/gboard-mic.json`. Without a matching calibration, tap Gboard's microphone
manually. Choose Rambler in Gboard's own voice-typing settings; Aether does not
change that preference.

To calibrate, open the ordinary text-input panel, focus it, and take an original
PNG screenshot while the microphone is idle. Find the microphone's center in
that image's original pixel coordinates and Gboard's installed version code:

```sh
termux-rish -c 'dumpsys package com.google.android.inputmethod.latin' | grep versionCode
python scripts/gboard-voice/calibrate.py /path/to/screenshot.png \
  --x MICROPHONE_X --y MICROPHONE_Y --version-code GBOARD_VERSION_CODE
```

The calibration helper requires Python and FFmpeg. It stores coordinates,
screen dimensions, the keyboard version, and a hash of a 64x64 pixel microphone
sample. It does not store the screenshot or transcript in the calibration file.

Before sending one tap, Aether checks the selected keyboard and version, screen
size, the actual microphone image, and that Termux still has focus. Cancelling
also invalidates an in-flight request. The service briefly captures the screen
into an owner-only temporary file and removes it afterward; it does not log
screen contents. No repeated taps or guessed fallback positions are used.

A changed keyboard layout, theme, rotation, or update can require recalibration.
This is a calibrated UI action, not a supported Gboard API, so the manual-mic
fallback remains available. The existing `VOICE` key is unaffected.
