# LocalBeat: Project Notes

A personal Android music player for the user's own CD rips, replacing
Musicolet: simple and lightweight, built around this library's folder layout
and tags. Sideloaded for now; the Play Store is possible later.

**Principle: local music only, no accounts, no data collection.** The app
never requests the INTERNET permission, so that's verifiable in Android's app
info. Network features (lyrics, scrobbling, remote server) are out of scope
unless the user decides otherwise; the remote server is planned as a separate
"Connected" edition (see the end).

**Why it exists:** Android's own MediaStore scanner garbles some UTF-8 tags
("Sittin’ on Top of the World" → `Sittinâ€™`), and Musicolet just shows
MediaStore's columns. So LocalBeat uses MediaStore only to *find* files and
reads every tag itself as strict UTF-8 (`tags/OggTags.kt`). Never guess
encodings.

## Phone

- Pixel 9 (`tokay`), Android 17, adb serial 00000000000000; USB debugging on,
  this computer authorised. If adb can't see it while plugged in:
  `adb kill-server; adb start-server`
- Music lives in `/sdcard/Music` ("Internal Storage/Music")

## The library (contract with CDRipPipeline)

Put on the phone by **LocalBeat Studio's phone sync** (`localbeat sync` / the Phone
tab; it replaced phone-sync.py on 2026-11-08). It makes Opus at sync time
from two libraries: `~/Music/FLAC` (verified CD rips) and
`~/Music/Miscellaneous` (older MP3/AAC/fre:ac copies, re-tagged from
MusicBrainz by `localbeat enrich`). A CD rip replaces the old copy.
(LocalBeat Studio is the desktop app in CDRipPipeline, called "ripshelf"
until 2026-11-11; the suite is LocalBeat = this phone app, LocalBeatServer
and LocalBeat Studio.)

- **Which albums are CD rips:** `Music/.ripshelf-sync.json` (name kept
  from before the rename; change it only together with the sync; written by
  the sync) maps each phone album folder to its source:
  `{"albums": {"Artist/Album": {"source": "flac:Artist/Album" | "misc:...",
  "files": {...}}}}`. `flac:` = ripped from CD (verified), `misc:` = older
  copy. Albums missing from it (loose files, put on by hand) are unknown.
  Use this to badge/filter albums; don't guess from tags

- `Music/Artist/Album/NN. Artist - Title.opus`; multi-disc albums share one
  folder: `1.07. Artist - Title.opus`. Older fre:ac rips: `Music/Artist -
  Album/NN - Artist - Title.opus`, no cover art (being re-ripped). Loose files
  can sit in `Music/`. Group by tags, never folder names
- Opus 160 kbps; older rips may be AAC `.m4a`
- Vorbis comments, UTF-8; key case varies by ripper (cyanrip lowercase,
  whipper uppercase): compare keys case-insensitively
- Cover: embedded `METADATA_BLOCK_PICTURE` (type 3) plus `cover.jpg`
- No ReplayGain tags (stripped: opusenc turned them into a bogus +99.6 dB
  header gain); Opus header gain is 0 dB
- Year: prefer ORIGINALDATE / ORIGINALYEAR (DATE is the CD's release); GENRE
  may have up to 3 values. Both come from the pipeline's `mb-enrich.py`; old
  fre:ac rips lack them until re-ripped
- `adb push`ed files stay `is_pending=1` and invisible to other apps until a
  media scan; LocalBeat re-indexes Music/ on every start
- Rippers disagree on apostrophes: grouping folds case, curly/straight quotes
  and spaces (`library/Library.kt`)

## Android Auto

- A Media3 `MediaLibraryService` (browse tree + playback) is all it needs; the
  same service drives the lock screen, notification and Bluetooth controls
- Sideloaded apps only appear with Android Auto's developer mode on (tap
  Version ~10×; developer settings are in the ⋮ menu), **Unknown sources**
  ticked, and the app ticked in **Customize launcher** (it was unticked after
  a sideload on 2026-11-07). Updates can reset these
- Android Auto's own log, car sessions included:
  `adb shell dumpsys activity service com.google.android.projection.gearhead`
- Without developer settings: install with the Play Store as installer
  (`adb install -i com.android.vending`, the user's call, not done) or a
  Play Console testing track
- The Desktop Head Unit (`~/Android/Sdk/extras/google/auto`) kept dropping
  the connection with Android Auto 17.9; not useful for now

## Build

```sh
export JAVA_HOME=/opt/android-studio/jbr
./gradlew :app:testOfflineDebugUnitTest   # incl. every file in ~/Music/phone
./gradlew :app:testConnectedDebugUnitTest
./gradlew dist    # both editions -> dist/LocalBeat-<v>.apk, LocalBeat-Connected-<v>.apk
adb install -r dist/LocalBeat-<version>.apk
aapt2 dump permissions <apk>   # the offline APK must never list INTERNET
```

- Android Studio at `/opt/android-studio`; SDK `~/Android/Sdk` (platforms
  35-37, build-tools 34-36). AGP 9.5 / Gradle 9.8 / compileSdk 37 (Compose
  1.12 needs it), Media3 1.11, minSdk 33. Package `com.jimboyo.localbeat`
- Release key: `~/.config/localbeat/` (not in git). **Back it up**: updates
  need the same key; without it the build falls back to the debug key. Bump
  `versionCode` for every APK handed out
- Code map: `tags/` (Ogg/Vorbis reader), `library/` (scan, cache, grouping),
  `art/` (cover cache + content provider for Auto), `audio/` (10-band EQ as
  an ExoPlayer AudioProcessor, so it applies on every output; boosts lower the
  volume to avoid clipping; takes 16-bit or float PCM only), `playback/`
  (MediaLibraryService, browse tree, saved queue), `ui/` (Compose)

## Editions (product flavours)

- **offline** (`src/offline`): no INTERNET permission, no network code.
  **connected** (`src/connected`): useful, non-spyware network features only
  (first: the remote server). `BuildConfig.CONNECTED` tells them apart
- Same package and key: installing one over the other keeps the library and
  settings
- Network code goes in `src/connected` only; `main` sees it through
  interfaces. Check the offline APK stays clean (`aapt2`, and grep its dex)
- Cast/DLNA output is built but parked on branch `cast-dlna` (untested on
  real devices)

## UI decisions (from the user)

- **Player controls always at the top of the screen**, in every mode. Never a
  bottom mini-player. Now Playing: full controls at the top, big art and the
  queue below. Spotify-style bottom navigation bar; no app title on the main
  page
- Bundled, selectable fonts (Musicolet's font was the main complaint):
  Figtree (default), Inter, Nunito Sans, Atkinson Hyperlegible Next
- Light / dark / follow-system theme; Material You colours
- Big cover art (two-column album grid)
- Queues, playlists (create **and share**: .m3u8 with paths relative to
  Music/), genres and decades; the playing song is marked with animated bars
- Lyrics maybe later, only from the files (embedded or .lrc)

## Remote access (planned, nothing built)

Design: **`docs/remote-access.md`**. Server side: its own repo,
**`~/development/LocalBeatServer`** (Raspberry Pi: FLAC library, WireGuard,
Opus on demand); keep its CLAUDE.md and the design note in step. Decided: two
editions from the same code. The normal one stays offline; a "Connected"
edition adds the network and a "Use remote server" checkbox.

## The LocalBeat mark (shared by the suite)

A gold disc (#F2B33D) with a navy (#1B2A3A) eighth note, on navy: the phone
app's launcher icon (`app/src/main/res/drawable/ic_launcher_foreground.xml`),
also LocalBeat Studio's app icon and sidebar logo (CDRipPipeline
`app/gui/assets/localbeat.svg`, `theme::logo`) and LocalBeatServer's.
`docs/brand/localbeat.svg` is the same mark as an SVG (108-unit space like
the adaptive icon). Change it in all three repos together.
