# LocalBeat

An Android music player for a personal music library, part of the LocalBeat suite
(with [LocalBeatStudio](https://github.com/jimbo-yo/LocalBeatStudio) and
[LocalBeatServer](https://github.com/jimbo-yo/LocalBeatServer)).

- **Two editions from one codebase**:
  - **Offline**: no `INTERNET` permission, no accounts, no network code. Plays
    local music files synced to the phone.
  - **Connected**: adds optional streaming and offline caching from a self-hosted
    [LocalBeatServer](https://github.com/jimbo-yo/LocalBeatServer) (Subsonic /
    OpenSubsonic API) with synced playlists.
- **Strict UTF-8 tags**: reads Vorbis/Opus/FLAC tags directly rather than relying
  on Android's MediaStore scanner.
- **Android Auto**: Media3 `MediaLibraryService` with browse tree, artwork
  provider, and voice search support.
- **Audio & UI**: 10-band equalizer, gapless playback, queues, `.m3u8` playlists,
  genres, decades, selectable bundled fonts, and Prism / Material You themes.

## Build

Requires Android Studio / JDK 17+ and Android SDK (compileSdk 37, minSdk 33).

```sh
./gradlew :app:testOfflineDebugUnitTest
./gradlew :app:testConnectedDebugUnitTest
./gradlew dist   # builds dist/LocalBeat-<v>.apk and LocalBeat-Connected-<v>.apk
```

Notes for working on the code are in [CLAUDE.md](CLAUDE.md) and [docs/](docs/).

## License

MIT: see [LICENSE](LICENSE).
