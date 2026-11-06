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

## Android Auto

- A media app needs no special permission: expose a Media3
  `MediaLibraryService` (browse tree + playback); Android Auto draws its own
  UI. The same service gives lock screen, notification, Bluetooth and steering
  wheel controls
- Sideloaded apps only show in Android Auto with its developer mode on (tap
  the version ~10 times in Android Auto settings) and **Unknown sources**
  enabled in its developer settings. Updates have been known to reset it
- Google's Android Auto review only applies to Play Store publishing

## Tools on this laptop

- Android Studio at `/opt/android-studio` (bundled JDK `jbr/`, Java 25)
- SDK `~/Android/Sdk`: platforms 35, 36, 37.0; build-tools 34-36
- Build and install from the CLI with Gradle, then `adb install`

## Plan

- Kotlin + Media3 (ExoPlayer: native Opus, queue, gapless, audio focus)
- First: list what the user likes about Musicolet and what bugs them; that
  drives the UI
