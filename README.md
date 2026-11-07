# Reel — video & music player for Android

No ads. No tracking. No accounts. Plays the files other players leave silent.

**Version 1.0.0** · Android 8.0+ · ~6 MB (arm64-v8a + armeabi-v7a)

## Why
Research into MX Player and its alternatives (VLC, KMPlayer, XPlayer, nPlayer, Infuse, Animiru/mpv) kept turning up the same complaints:

| Complaint | Where | What Reel does |
|---|---|---|
| Ads everywhere | MX Player free, KMPlayer, XPlayer | Zero ads, zero analytics |
| "EAC3/AC3/DTS not supported" — video plays silent | MX Player dropped Dolby/DTS | FFmpeg audio decoders built in |
| Clunky / heavy UI | VLC, mpv | Clean dark design, two pages organised by folders |
| Losing your place | many players | Every video remembers its own position |
| Sound stops when the screen turns off | video-first players | Background play + lock-screen widget |

## Features
**Library:** two pages — Videos and Music — each organised by folders · grid or list, pinch with two fingers to resize tiles (list → 2 → 3 → 4+ columns) · folder cards with a 4-video mosaic · sort (name, date, size, count/length; ascending/descending) · search · NEW tags · resume progress on thumbnails · pull to refresh · long-press multi-select → delete, rename, move to folder, share, properties · create, rename and delete folders.

**Player:** hardware decoding with software audio fallback · drag the progress bar and the picture follows, with a frame preview above it · swipe sideways to seek (1–10 min per swipe) · swipe left/right edge for brightness / volume (up to 200%) · double-tap ±5/10/15/30 s · pinch to zoom · Fit / Crop / Stretch · screen lock · rotation · picture-in-picture (closing it stops playback) · speed 0.25–4× · audio and subtitle track menus · load .srt/.ass/.vtt · subtitle size, colour, background · subtitle delay (−10…+10 s) · screenshot to Pictures/Reel · sleep timer · repeat · playlist · file info · play as audio.

**Background & lock screen:** keeps the sound going when the screen turns off; the lock-screen widget shows a frame of the video and can be limited to play/pause only (no seeking).

**Music:** all songs · background playback with notification and headset controls · shuffle & repeat · sleep timer.

**Settings:** theme (dark / AMOLED black), gestures on/off, seek distance, double-tap step, orientation, resume, auto-next, background play, lock-screen controls, software audio decoder, subtitle style.

## Build & test
```
./gradlew assembleRelease
./gradlew testDebugUnitTest   # opens every screen under Robolectric, saves screenshots to app/build/shots/
```
Built on AndroidX Media3 1.9.0 and the Jellyfin FFmpeg decoder extension.

Signing: put `release.jks` and `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword) in the project root. They are git-ignored. Without them the build falls back to the debug key.

Note: `versionName` is 1.0.0 while `versionCode` keeps increasing, so this build installs over earlier test builds.
