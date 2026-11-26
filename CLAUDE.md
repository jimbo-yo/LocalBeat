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

- Tested on Pixel 9 (`tokay`). Personal device serial / keystore paths live in
  `CLAUDE.local.md` (gitignored). If adb can't see a plugged-in phone:
  `adb kill-server; adb start-server`
- Music lives in `/sdcard/Music` ("Internal Storage/Music")

## The library (contract with LocalBeat Studio)

Put on the phone by **LocalBeat Studio's phone sync** (`localbeat sync` / the Phone
tab; it replaced phone-sync.py on 2026-11-08). It makes Opus at sync time
from two libraries: `~/Music/FLAC` (verified CD rips) and
`~/Music/Miscellaneous` (older MP3/AAC/fre:ac copies, re-tagged from
MusicBrainz by `localbeat enrich`). A CD rip replaces the old copy.
(LocalBeat Studio is the desktop app in `~/Development/LocalBeatStudio`, formerly the CDRipPipeline repo, called "ripshelf"
until 2026-11-11; the suite is LocalBeat = this phone app, LocalBeatServer
and LocalBeat Studio.)

- **Which albums are CD rips:** `Music/.ripshelf-sync.json` (name kept
  from before the rename; change it only together with the sync; written by
  the sync) maps each phone album folder to its source:
  `{"albums": {"Artist/Album": {"source": "flac:Artist/Album" | "misc:...",
  "files": {...}}}}`. `flac:` = ripped from CD (verified), `misc:` = older
  copy. Albums missing from it (loose files, put on by hand) are unknown.
  The app can't read it (scoped storage: not a media file, written over adb).
  **CD rip** on the phone instead comes from `LOCALBEAT_SOURCE=CD`, which
  Studio's sync writes into every Opus copy of a FLAC (since 2026-11-23;
  copies made before then lack it until re-synced), and for music-server songs
  from `suffix` flac (the server's FLAC library). Never guess from other tags

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
- Voice ("Hey Google, play … on LocalBeat"): Auto sends it to the session as a
  `searchQuery` item; the phone starts `MainActivity` with
  `MEDIA_PLAY_FROM_SEARCH` (`playback/VoiceRequest.kt`, 0.9.19). Both end in
  `BrowseTree.voice`. Apps can't add their own wake words or phrases.
  Gemini on the phone ignores LocalBeat (2026-11-19): it neither knows the
  name nor lists it among music apps, even when reinstalled with the Play
  Store as installer; it seems to use Google's own list of music apps
  (partners only: YouTube Music, Spotify, Pandora…; other local players hit
  the same wall, web search 2026-11-19). The route to watch is
  **AppFunctions** (Android 16+, no network needed): Gemini's use of it was
  a private preview (Calendar/Notes/Tasks) as of 2026-05; a "play" function
  calling `BrowseTree.voice` would be the way in once it opens. Android's own
  chooser and `adb shell am start -n com.jimboyo.localbeat/.ui.MainActivity
  -a android.media.action.MEDIA_PLAY_FROM_SEARCH --es query "…"` do work
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
- Windows: Android Studio in `C:\Program Files\Android\Android Studio`, SDK
  `%LOCALAPPDATA%\Android\Sdk`. No gradlew.bat: run `./gradlew` from Git Bash
  with `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`. Release key
  in `~/.config/localbeat/` (storeFile set to the local path). First `dist`
  build ~11 min
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

**Shared interface across phone, Android Auto and Studio** (2026-11-23):
plan in **`docs/ui-refinement.md`**, terms in **`GLOSSARY.md`** (the one
copy for the suite; Studio points here). Use the glossary's words on screen.

- **Player controls always at the top of the screen**, in every mode. Never a
  bottom mini-player. Now Playing: full controls at the top, big art and the
  queue below. Spotify-style bottom navigation bar; no app title on the main
  page
- Bundled, selectable fonts (Musicolet's font was the main complaint):
  Figtree (default), Inter, Nunito Sans, Atkinson Hyperlegible Next
- Light / dark / follow-system theme; colours **Prism** (default since 0.9,
  2026-11-11: the LocalBeat suite look shared with LocalBeat Studio, `ui/Theme.kt`
  `Prism`, keep in step with LocalBeatStudio `gui/src/theme.rs`) or Material
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
  the Mac 2026-11-17), 1b devices → users (done 2026-11-17: localbeat-people --user, admin page "whose device?", Studio "Whose computer"), 2 Studio CLI
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
  them, 6 Studio's Library page (done 2026-11-17: a Playlists page, Add to playlist on songs and albums), 7 live control over adb (done 2026-11-17, 0.9.11-0.9.15: `playback/ControlProvider.kt`, adb only; see docs/control.md for the Android 17 background-start workarounds)
  (`localbeat player …` via a content provider that refuses all callers but
  the shell user, as planned 2026-11-12)
- **Metadata after that:** play history on the phone (counts, last played,
  skips), then tempo/energy measured offline on the laptop (e.g. Essentia),
  then lyrics from LRCLIB fetched by Studio into `.lrc` files. Rule: the
  laptop fetches or measures and writes tags/files; the phone only reads
- **Open:** asking from the phone without the laptop (phone AI apps only
  use internet-reachable MCP servers, which clashes with the WireGuard-only
  plan): decide after the laptop version works

## TV app (decided 2026-11-26, not built yet)

Why and how: **`docs/adr/0001-tv-app-on-vega.md`**. A separate repo,
`~/Development/LocalBeatTV` (Vega OS: React Native + TypeScript, for the Fire
TV Stick 4K Select), package `com.jimboyo.localbeat.tv`, shown as "LocalBeat".

- Home LAN only, no WireGuard; joins the music server by code + Approve as a
  device of one user (name suggested by the TV, editable on Approve). Talks to
  nothing but the music server at a private address
- Streams original files (no `format=opus`); everything shown, CD rips marked;
  DRM `.m4p` skipped. Nothing stored but the saved queue and position
- Screen: Player strip at the top, left rail with the four entry points,
  Now Playing full screen with the Queue beside it, 4-5 column album grid,
  D-pad focus; Now Playing doubles as a slow-drifting idle screen
- Grouping rules ported from `library/Library.kt`: keep both in step, checked
  by shared test cases
- Not in v1: voice, EQ, control from phone/`localbeat player`, playlist
  editing. Step 2: visualisation (Vega audio tap, else Studio-made analysis
  files)

## The LocalBeat mark (shared by the suite)

A gold disc (#F2B33D) with a navy (#1B2A3A) eighth note, on navy: the phone
app's launcher icon (`app/src/main/res/drawable/ic_launcher_foreground.xml`),
also LocalBeat Studio's app icon and sidebar logo (LocalBeatStudio
`gui/assets/localbeat.svg`, `theme::logo`) and LocalBeatServer's.
`docs/brand/localbeat.svg` is the same mark as an SVG (108-unit space like
the adaptive icon). Change it in all three repos together.

## Agent skills

### Issue tracker

GitHub Issues on `jimbo-yo/LocalBeat`, through the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The five default labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` and `docs/adr/` at the repo root (created when first needed). See `docs/agents/domain.md`.
