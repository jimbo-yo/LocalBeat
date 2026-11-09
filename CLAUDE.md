# LocalBeat: Project Notes

A personal Android music player for the user's own CD rips: simple and
lightweight like Musicolet (the player it replaces), but built around this
library's folder layout and tags. For personal use (sideloaded), not the Play
Store.

**Principle: local music only, no accounts, no data collection.** The app
should not request the INTERNET permission at all, so that's verifiable in
Android's app info (cover art and tags all come from the files). Anything that
would need the network (lyrics, scrobbling) is out of scope unless the user
decides otherwise.

## Why

- Musicolet garbles some UTF-8 titles: it guesses the tag encoding from the
  text. "Sittin’ on Top of the World" (Let Love Rule) shows as `Sittinâ€™`,
  while other titles with the same ’ display fine. Proven 2026-11-06 with test
  files: any layout with that exact title garbles, any other title text
  doesn't. Vorbis comments are UTF-8 by spec; LocalBeat must read them as UTF-8
  and never guess
- Root cause found 2026-11-06: it's **Android's own MediaStore scanner** that
  garbles it (`content query` shows `title=Sittinâ€™...`); Musicolet just shows
  MediaStore's columns. So LocalBeat uses MediaStore only to *find* files and
  reads every tag itself (`tags/OggTags.kt`)
- Musicolet has no tag-encoding setting and no manual rescan (it finds new
  files on its own)

## Phone

- Pixel 9 (`tokay`), Android 17, adb serial 00000000000000; USB debugging on,
  this computer authorised. KDE Connect paired too
- Music lives in `/sdcard/Music` ("Internal Storage/Music")

## The library (contract with CDRipPipeline)

Files are put on the phone by `~/development/CDRipPipeline/phone-sync.py`
(copies of `~/Music/phone`, made by `flac2phone.sh`). LocalBeat should rely on:

- Layout: `Music/Artist/Album/NN. Artist - Title.opus`; multi-disc albums
  share one folder with a disc prefix: `1.07. Artist - Title.opus`
- Older albums still use `Music/Artist - Album/NN - Artist - Title.opus`
  (fre:ac era; being re-ripped). Group by tags, not folder names
- Opus (160 kbps); older rips may be AAC `.m4a`. Loose files can sit directly
  in `Music/` (two .mp3s today)
- Tags: Vorbis comments, UTF-8. cyanrip rips use lowercase keys (`title`,
  `artist`, `album`, `TRACKNUMBER`, `tracktotal`, `DISCNUMBER`, `totaldiscs`,
  `ALBUMARTIST`, `date`, `musicbrainz_albumid`, `musicbrainz_trackid`, ...);
  whipper rips use uppercase (`TITLE`, `DISCTOTAL`, `MUSICBRAINZ_ALBUMID`, ...).
  Keys are case-insensitive by spec: compare them that way
- Cover art: embedded `METADATA_BLOCK_PICTURE` (front cover, type 3) in each
  file, plus `cover.jpg` in the album folder
- No ReplayGain tags on the phone copies (stripped: opusenc turned them into a
  bogus +99.6 dB header gain). The Opus header output gain is 0 dB

- Files copied with `adb push` stay `is_pending=1` in MediaStore (owner
  `com.android.shell`) and are **invisible to other apps** until a media scan
  touches them. Scanning a folder clears it:
  `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/<folder>"`.
  LocalBeat re-indexes Music/ on every start; phone-sync should also do it
- Older fre:ac rips (the `Artist - Album/` folders) have no cover art at all,
  embedded or `cover.jpg`. Re-rips fix it
- Rippers disagree on apostrophes (`Guns N' Roses` vs `Guns N’ Roses`):
  grouping folds case, curly/straight quotes and spaces (`library/Library.kt`)

## Android Auto

- A media app needs no special permission: expose a Media3
  `MediaLibraryService` (browse tree + playback); Android Auto draws its own
  UI. The same service gives lock screen, notification, Bluetooth and steering
  wheel controls
- Sideloaded apps only show in Android Auto with its developer mode on (tap
  the version ~10 times in Android Auto settings) and **Unknown sources**
  enabled in its developer settings. Updates have been known to reset it
- Google's Android Auto review only applies to Play Store publishing
- If LocalBeat still doesn't show in the car: Android Auto settings →
  **Customize launcher** must have it ticked. On 2026-11-07 it was unticked
  there after a sideload, which kept it off the car screen even with Unknown
  sources on (proved from Android Auto's log: `GH.SettingAppRowVH ... Enabled
  false`). `adb shell dumpsys activity service com.google.android.projection.gearhead`
  dumps Android Auto's recent log, car sessions included
- Desktop Head Unit (car screen on the laptop) is unpacked in
  `~/Android/Sdk/extras/google/auto` (needs `LD_LIBRARY_PATH=lib:.` and stdin
  kept open), but Google's latest DHU (2.0, 2022) kept dropping the connection
  with Android Auto 17.9

## Tools on this laptop

- Android Studio at `/opt/android-studio` (bundled JDK `jbr/`, Java 25)
- SDK `~/Android/Sdk`: platforms 35, 36, 37.0; build-tools 34-36
- Build and install from the CLI with Gradle, then `adb install`

## Build

```sh
export JAVA_HOME=/opt/android-studio/jbr
./gradlew :app:testDebugUnitTest      # tag reader, incl. every file in ~/Music/phone
./gradlew dist                         # release APK -> dist/LocalBeat-<version>.apk
adb install -r dist/LocalBeat-<version>.apk
aapt2 dump permissions <apk>          # must never list INTERNET
```

Release builds are signed with the LocalBeat key in `~/.config/localbeat/`
(`keystore.properties` + `localbeat-release.jks`, created 2026-11-06, not in
git). **Back it up**: installs on any phone can only be updated with the same
key. Without it the build falls back to the debug key. Bump `versionCode` for
every APK handed out. minSdk 33: other phones need Android 13+.

AGP 9.5 / Gradle 9.8 / compileSdk 37 (Compose 1.12 needs it), Media3 1.11.
Package `com.jimboyo.localbeat`. Code map: `tags/` (Ogg/Vorbis comment
reader), `library/` (scan, cache, grouping), `art/` (cover cache + content
provider for Auto), `audio/` (10-band EQ: an ExoPlayer AudioProcessor, so it applies on
every output incl. Auto; boosts lower the volume to avoid clipping), `playback/` (MediaLibraryService, browse tree, saved queue),
`ui/` (Compose).

## UI decisions (from the user, 2026-11-06)

- **Player controls always at the top of the screen**, in every mode, and
  especially while a song plays. Never a bottom mini-player. Now Playing keeps
  the full controls (seek, shuffle, prev/play/next, repeat) at the top, with
  big art and the queue below
- Musicolet's interface is what bugs them: the font is ugly. Fonts are bundled
  (no network) and selectable: Figtree (default), Inter, Nunito Sans,
  Atkinson Hyperlegible Next
- Selectable light / dark / follow-system theme; Material You colours
- Big cover art (two-column album grid, full-width covers)
- Keep from Musicolet: queues, playlists (create **and share**: .m3u8 with
  paths relative to Music/, so they also work against ~/Music/phone),
  organizing by genre and decade
- Lyrics: maybe later, only from the files (embedded or .lrc), no network

## Library data gaps (for CDRipPipeline)

- cyanrip rips have **no GENRE**; old fre:ac rips have odd ones ("alternative
  hip hop" on Appetite for Destruction). The Genres tab is only as good as this
- DATE is the CD's release date, not the album's (Kink Kronikles: 1989 vs
  1972). LocalBeat prefers ORIGINALDATE / ORIGINALYEAR. Since 2026-11-06 the
  pipeline's `mb-enrich.py` writes them plus up to 3 GENRE values from
  MusicBrainz (run by rip-cd.sh; backfilled). The old fre:ac rips still lack
  them (no MBID) until re-ripped

## Android Auto without developer settings

Android Auto hides apps not installed by the Play Store. Options: install
with the Play Store recorded as installer (`adb install -i com.android.vending`;
the user's call, not done yet), or publish through Play Console (the user
has an account) on a testing track.

## Future: remote access (design note)

Long-term idea (2026-11-08), nothing built: a self-hosted home server with the
whole library, reachable from anywhere over WireGuard + dynamic DNS (not open
SSH); first for Phone Sync from anywhere, later a browse-everything,
cache-what's-played mode (e.g. Navidrome / Subsonic API). Decided: two
editions from the same code: the normal one stays offline (no INTERNET); a
"Connected" edition adds the network and a "Use remote server" checkbox. See
`docs/remote-access.md`.
