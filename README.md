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

## Features
**Video:** hardware decoding with automatic software fallback · resume where you left off · swipe left = brightness, right = volume (up to 200% boost), horizontal = seek · double-tap sides ±10 s, middle = pause · pinch to zoom · Fit / Crop / Stretch · screen lock · picture-in-picture · auto-rotate to the video's shape · playback speed, audio track and subtitle track menus · load external .srt / .ass / .vtt subtitles · next/previous within a folder · open videos from any app · network streams (http, HLS m3u8).

**Music:** all songs, search by title/artist/album · background playback with notification controls · shuffle & repeat · sleep timer (15–90 min or end of track).

**Library:** folders, sorting (name, newest, largest, longest), search, long-press to share.

## Build
```
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk` (arm64-v8a + armeabi-v7a, Android 8.0+).
Built on AndroidX Media3 1.9.0 and the Jellyfin FFmpeg decoder extension.

Signing: put `release.jks` and `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword) in the project root. They are git-ignored. Without them the build falls back to the debug key.
