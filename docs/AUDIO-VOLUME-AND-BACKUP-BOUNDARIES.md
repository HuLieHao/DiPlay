# App-side volume and portable settings

The optional **CarPlay volume bar (experimental)** control lives with the existing
firmware-sensitive navigation wheel control in Advanced. It is off by default.
Only the visible CarPlay window routes received volume keys (Android 24/25 and the
existing BYD 291/292, 307/308 codes). It changes one level per DOWN/repeat, clamps
to the actual stream limits and verifies readback. UP remains paired with the
consumed DOWN. A held key does not switch from guidance to media when guidance
ends. Calls and priority voice/Siri pass new presses through unchanged.

Without guidance, this path changes the music stream. During guidance it uses
`NavigationPlayback`'s actual, unambiguous legacy stream, never a guessed stream
number. Automatic/usage routes, music-mapped guidance, conflicting routes and
unsupported reads/writes leave the initial press with the head unit. On API 27
the public stream-minimum API is unavailable; the existing minimum policy is used.
The existing accessibility opt-in remains separate: it only adjusts known active
guidance, preserves map zoom/joystick priority and does not grant itself new
permissions. Its successful guidance writes notify the visible host's bar.

The dark translucent bar contains a navigation or speaker icon and a level track,
with no caption. It is an ordinary noninteractive child of the CarPlay window;
there is no global overlay permission or hidden global window. It hides after
1.5 seconds, on host pause, when the video is hidden, or on detach. Other apps
retain their original controls.

## Verified firmware boundary

Archived MainUI source at
`artifacts/analysis/connection-priority-20261025/mainui-java/sources/` in the main
workspace shows:

- `com/ts/main/common/MainVolume.java:475–497`: `NaviVolShow()` compares the stored
  navigation volume; the main task separately polls `Iop.GetVolume(0/1)` and mute
  state, then calls `VolWinShow()`. It does not rely solely on Android SHOW_UI.
- `MainUI.java:2508–2509`: AIDL `TsVolumeShow()` sets `nAidlVolumeShow=1`.
  `ITsCommon.java` exposes volume increment/decrement/set/show, but no verified
  suppression method. `PopMuteSet/Clear` call Evc audio pop-mute methods, not UI
  suppression. `MainVolume.VolWinHide()` directly manipulates the vendor window;
  it is not exposed by this AIDL interface.
- `com/ts/MainUI/Evc.java:178–194` checks legacy stream **11** as `STREAM_GIS`.
  This is evidence against assuming stream 14 for this firmware.

All handled writes use flags **0**, so DiPlay does not request Android/OEM UI.
Firmware can consume physical keys before DiPlay or independently notice a volume
change and display its own bar. Therefore **two bars may still appear**, or DiPlay
may receive no key at all. There is no verified normal-app suppression API in the
inspected interface. No binder guessing, system patch, root write or global style
replacement is part of this implementation. The setting description states this
limitation in every supported locale.

## Audio transition audit

Existing `AndroidMediaSink` keeps independent tracks and usages. Navigation does
not acquire audio focus or force Android media ducking: iPhone mixing and the
head-unit firmware determine audible guidance/media mixing. `AudioFocusCoordinator`
changes only per-media-track gain for transient loss/duck and restores it on gain;
phone, assistant and navigation gain/user stream volumes are not overwritten.
The existing renderer `NavigationPlayback` tokens track buffered priority voice
and close/expire independently. No proven focus lifecycle gap was found in this
change; the existing coordination is preserved rather than restructuring it.
JVM tests cover transient loss/gain, ducking, new media tracks, stale callbacks,
voice release, navigation focus exclusion and independent user volumes. They do
not establish audible duck/restore behavior on the car.

## Existing backup entry and migration

The existing phone backup/import entry and format version 1 remain. The allowlist
already includes hotspot mode/band/channel/security and automatic car hotspot
preferences; audio stream/channel, buffering, focus and call settings; and supported
video/picture/display preferences. This change adds `carplay_clock_position`,
`carplay_clock_size_sp`, the navigation-wheel enabled preference and the foreground
volume-bar enabled preference. Accessibility authorization is deliberately excluded.
Older version-1 documents leave new preferences at their existing/default values;
unknown newer keys are skipped. Future unsupported document versions are rejected.
The app version string is descriptive metadata, not an installation identity.

Formal `com.shihab.diplay` and debug `com.shihab.diplay.hudtest` installs have separate
Android data. Supported settings can be exported/imported between them; importing
does not transfer either installation's authentication or pairing identity.
Pairing/lockdown/authentication keys, selected phone identity, local consents,
permissions, detected vehicle data and caches stay local. The existing policy also
excludes manual hotspot **SSID and password**; enter them again on the destination.
Custom launcher icon files are **not migrated**. This is a portable supported
configuration backup, not a complete app-data clone. The existing backup dialog
now explains these exclusions; no second backup entry is added.

## Physical acceptance still required

On Redmi first, then FF-5000/API27 while parked: confirm each actual physical key
code reaches DiPlay, level step/readback and route independence; try guidance
start/end while holding volume, ambiguous/automatic routing, Siri start/end,
incoming/answered/ended calls, music resume, audio focus enabled/disabled, map
zoom and joystick priority. Check Android/OEM double bars for both media and
navigation and whether firmware intercepts keys before the app. Verify bar
readability and touch passthrough in real CarPlay, hidden/return lifecycle, and
that other apps keep their controls. Verify cross-version/formal-debug restore,
re-enter hotspot SSID/password, permissions and pairing, and replace custom icons
manually. No device installation or car-side action was performed offline.
