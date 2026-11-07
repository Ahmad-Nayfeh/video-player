# Reel — video & music player for Android

No ads. No tracking. No accounts. Plays the files people usually get silence on.

## Why (what we found)
Research into MX Player and its alternatives (VLC, KMPlayer, XPlayer, Just Player, Next Player, mpv) kept turning up the same complaints:

| Complaint | Where | What Reel does |
|---|---|---|
| Ads everywhere (banners, full-screen, floating icons) | MX Player free, KMPlayer, XPlayer | Zero ads, zero analytics SDKs |
| **"Audio format EAC3/AC3/DTS not supported" — video plays silent** | MX Player dropped Dolby/DTS decoding; custom codec packs are a hassle | FFmpeg software audio decoders built in (AC3, E-AC3, DTS, TrueHD, …) — no codec pack |
| Clunky / heavy UI | VLC, mpv | Two tabs (Videos, Music), folders, one search, dark minimal design |
| Losing your place | many players | Auto-resume for every video + a "Continue watching" shelf |
| Music stops when the screen turns off | video-first players | Real background music service with lock-screen/headset controls and a sleep timer |

## What's new in 1.3.0
- **Seek preview**: a frame + time follows your finger while dragging the progress bar.
- **Screenshot** button: saves the current frame at full video resolution to Pictures/Reel.
- **Subtitle delay** (−10 s … +10 s): loaded subtitle files (SRT/VTT/ASS) are re-timed both ways; embedded subtitles can be delayed.
- **Manual picture adjustments**: brightness, contrast and saturation sliders, on top of the picture modes.

## What's new in 1.2.0
- **Picture modes**: Normal, Auto enhance, Vivid, Sport, Cinema, Night (eye comfort), Warm, Cool, Bright, Black & white — applied live on the GPU (palette button in the player).
- Closing the picture-in-picture window now stops the video completely (no more sound continuing).

## What's new in 1.1.0
- New modern design: Folders / Videos / Music tabs, grid or list view, NEW tags, resume progress on thumbnails.
- **Fixed seeking**: drag the progress bar and the picture follows your finger; swiping sideways seeks live (1–10 min per swipe, set in Settings). The "buffer line" is gone.
- **File management**: long-press to multi-select → delete, rename, move to folder, share, properties.
- **Screen off keeps playing**: the sound continues in the background with a lock-screen widget showing a frame of the video.
- **Lock-screen controls**: "Full" or "Play / pause only" so nothing gets skipped by accident.
- Player menus like MX Player: audio tracks, subtitle tracks, load subtitle file, subtitle size/color/background, speed 0.25–4×, sleep timer, repeat, playlist, aspect, rotation, PiP, file info.
- Full Settings screen (theme incl. AMOLED black, gestures on/off, seek distance, double-tap step, orientation, resume, auto-next…).
- "Continue watching" folder removed — each video remembers its own position.

## Features
**Video:** hardware decoding with automatic software fallback · resume where you left off · swipe left = brightness, right = volume (up to 200% boost), horizontal = seek · double-tap sides ±10 s, middle = pause · pinch to zoom · Fit / Crop / Stretch · screen lock · picture-in-picture · auto-rotate to the video's shape · playback speed, audio track and subtitle track menus · load external .srt / .ass / .vtt subtitles · next/previous within a folder · open videos from any app · network streams (http, HLS m3u8).

**Music:** all songs, search by title/artist/album · background playback with notification controls · shuffle & repeat · sleep timer (15–90 min or end of track).

**Library:** folders, sorting (name, newest, largest, longest), search, long-press to share.

## Tests
`./gradlew testDebugUnitTest` opens every screen under Robolectric and saves screenshots to `app/build/shots/`.

## Build
```
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk` (arm64-v8a + armeabi-v7a, Android 8.0+).
Built on AndroidX Media3 1.9.0 and the Jellyfin FFmpeg decoder extension.

Signing: put `release.jks` and `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword) in the project root. They are git-ignored. Without them the build falls back to the debug key.
