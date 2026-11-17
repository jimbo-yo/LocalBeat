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
- Windows (the Surface, 2026-11-17): Android Studio in `C:\Program
  Files\Android\Android Studio`, SDK `%LOCALAPPDATA%\Android\Sdk`. No
  gradlew.bat: run `./gradlew` from Git Bash with `JAVA_HOME="/c/Program
  Files/Android/Android Studio/jbr"`. Release key copied from the Mac's
  backup to `C:\Users\user\.config\localbeat\` (storeFile set to the
  Windows path). First `dist` build ~11 min
- Release key: `~/.config/localbeat/` (not in git; a copy on the Mac server,
  `~/backup/localbeat/`, since 2026-11-13). **Back it up**: updates
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
- Light / dark / follow-system theme; colours **Prism** (default since 0.9,
  2026-11-11: the LocalBeat suite look shared with LocalBeat Studio, `ui/Theme.kt`
  `Prism`, keep in step with CDRipPipeline `app/gui/src/theme.rs`) or Material
  You from the wallpaper (Settings > Colours)
- Big cover art (two-column album grid)
- Queues, playlists (create **and share**: .m3u8 with paths relative to
  Music/), genres and decades; the playing song is marked with animated bars
- Lyrics maybe later, only from the files (embedded or .lrc)

## Remote access

Design: **`docs/remote-access.md`**. Server side: its own repo,
**`~/development/LocalBeatServer`** (Raspberry Pi: FLAC library, WireGuard,
Opus on demand); keep its CLAUDE.md and the design note in step. Decided: two
editions from the same code. The normal one stays offline; a "Connected"
edition adds the network and a "Use remote server" checkbox.

- **Catalogue refresh** (0.9.6, 2026-11-17): fetched at process start, on
  settings changes, with Refresh (Settings → Music server) and "Rescan
  library", and on coming back on screen when older than 5 min
  (`SubsonicServer.STALE_MS`; the playback service keeps the process alive,
  so the start fetch alone missed new rips)
- **Server links** (0.9.5, 2026-11-13): the Connected edition opens
  `localbeat://connect?server=…&user=…&secret=…` (LocalBeatServer's second QR
  code, read by the phone's camera; no scanner, no camera permission). The
  intent filter is in `src/connected/AndroidManifest.xml` only; parsing in
  `server/ConnectLink.kt` (http/https address with nothing after host:port,
  user, secret; tested in `ConnectLinkTest`); `MainActivity` asks "Use this
  music server?" before changing Settings, and warns that songs kept on the
  phone are tied to the old address + user (track IDs hash both)

## AI control and playlists (revised 2026-11-17; server step being built)

Design and plan: **`docs/control.md`** (read it first). In short (user's
decisions, 2026-11-17):

- **Playlists live on the music server** (LocalBeatServer), through the
  standard Subsonic playlist calls, so one playlist shows on the phone, in
  Studio and to an AI. The phone's own `PlaylistStore` stays for the offline
  edition and Connected with no server.
- **Devices belong to users:** each login (`my-phone`, `my-windows-pc`…) is a
  device of one user (the server's users file); a user's playlists are the
  same on all their devices. With a server, the Connected edition's
  playlists *are* the user's server playlists (kept for offline, changes
  queued while out of reach; phone playlists moved in once). Shared with
  other users or everyone: view, play, copy only. Extensions to check:
  `formPost`, `localbeatPlaylistSharing`.
- `localbeat mcp` (Studio's CLI, stdio) gives an AI tools against the
  server: search, `match_songs` (artist – title lines → song IDs), playlist
  create/edit. No phone needed.
- Order: 1 server playlists (built, LocalBeatServer branch `playlists`, on
  the Mac 2026-11-17), 1b devices → users on the Mac, 2 Studio CLI
  `playlist`/`match` (done 2026-11-17), 3 (done 2026-11-17), 4 (done
  2026-11-17, 0.9.7: `MusicServer.playlists`, fetched with the catalogue
  and saved in `server-playlists.json`; `Playlist.onServer` entries are
  server track IDs; `Library.standIns` maps a server song merged away to
  its phone copy; Playlists tab section "On the music server", read-only
  page; Android Auto via `LocalBeatApp.allPlaylists()`), 5 (done
  2026-11-17, 0.9.8/0.9.9: `server/PlaylistEdits.kt` queue saved in
  `server-playlist-edits.json`, shown on top of the server's list, sent in
  order; a playlist changed elsewhere (its `changed` differs) keeps the
  server's version; `playlists/MoveToServer.kt`; Playlists tab: yours,
  "Shared with you" (Make a copy), "Only on this phone" (Move to the music
  server))
  `localbeat mcp`, 4 phone shows/plays server playlists, 5 phone edits
  them, 6 Studio's Library page, 7 live control over adb
  (`localbeat player …` via a content provider that refuses all callers but
  the shell user, as planned 2026-11-12)
- **Metadata after that:** play history on the phone (counts, last played,
  skips), then tempo/energy measured offline on the laptop (e.g. Essentia),
  then lyrics from LRCLIB fetched by Studio into `.lrc` files. Rule: the
  laptop fetches or measures and writes tags/files; the phone only reads
- **Open:** asking from the phone without the laptop (phone AI apps only
  use internet-reachable MCP servers, which clashes with the WireGuard-only
  plan): decide after the laptop version works

## The LocalBeat mark (shared by the suite)

A gold disc (#F2B33D) with a navy (#1B2A3A) eighth note, on navy: the phone
app's launcher icon (`app/src/main/res/drawable/ic_launcher_foreground.xml`),
also LocalBeat Studio's app icon and sidebar logo (CDRipPipeline
`app/gui/assets/localbeat.svg`, `theme::logo`) and LocalBeatServer's.
`docs/brand/localbeat.svg` is the same mark as an SVG (108-unit space like
the adaptive icon). Change it in all three repos together.
