# Gboard voice input

Add `GBOARD` to `extra-keys` in `~/.termux/termux.properties` for the 🗣
dictation key. `VOICE` uses Android's speech prompt; `GBOARD` uses the voice mode
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

## Automatic dictation

After the editor has focus and Gboard is visible, Aether asks its connected
Shizuku session service to send Android's Dictation key (`KEYCODE_DICTATE`, 319)
once. This requires Android API 36 or newer and Gboard as the selected keyboard.
The service checks the foreground app and cancellation before injection.
No screenshot, screen coordinates, keyboard version pin, or calibration file is
needed. Choose Rambler in Gboard's own voice-typing settings; Aether does not
change that preference.

The event was verified on a Pixel running API 37 with Gboard version code
176049598. Successful injection does not guarantee that every keyboard version
handles the event. If dictation does not start, tap Gboard's microphone manually;
the draft editor remains usable. Requests are not retried automatically.

When upgrading from an APK that used calibrated microphone taps, a surviving
older Shizuku session service does not have the new dictation method. It falls
back to manual input until the service is restarted. Finish active terminal
work before restarting it. Existing calibration files are no longer read by
the `GBOARD` key; the legacy tap endpoint remains for compatibility.
