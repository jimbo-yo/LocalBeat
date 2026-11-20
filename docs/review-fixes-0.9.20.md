# Code Review & Hardening Fixes (0.9.20)

Summary of bugs, race conditions, security issues, and edge cases found and fixed in the 0.9.20 release (`versionCode = 29`).

## Playback, Voice & Control

- **No spontaneous playback on paused restore errors** (`PlaybackService.kt`): `onPlayerError()` / `skipToPlayable()` only auto-skips and calls `player.play()` when `player.playWhenReady` is `true`, so restoring a paused queue that hits a blocked/unreachable server track no longer starts playing audio on its own. `fetchWhole()` is also gated on `player.playWhenReady`.
- **Restrict `ON_PHONE_ACTION` to trusted controllers** (`PlaybackService.kt`): `onConnect()` only exposes `onPhoneCommand` and media button preferences when `controller.isTrusted`, and `onCustomCommand()` rejects untrusted callers with `ERROR_PERMISSION_DENIED`.
- **Queue resume & cold-start races** (`PlaybackService.kt`, `QueueStore.kt`):
  - `savedItems()` now computes the restored `startIndex` by counting resolved tracks before `saved.index` rather than `indexOfFirst { it.id == current }`, preserving resume position for duplicate tracks and server-to-phone stand-ins.
  - `restoreQueue()` saves the new queue if an incoming playback command already populated the player while `awaitLibrary()` was suspended, without clobbering shuffle/repeat.
  - `QueueStore.load()` and `QueueStore.save()` now run off the main thread (`Dispatchers.IO`, with `@Synchronized` writes and a synchronous save in `onDestroy()`).
  - Clamped `startIndex` in `onSetMediaItems()` and negative `page` in `List<T>.page()`.
  - Wrapped `ToneGenerator` release in `try/finally` inside `chime()` so service destruction never leaks native audio resources.
- **Voice search & browse tree** (`BrowseTree.kt`):
  - Guarded free-text fallback and `score()` against empty queries so unmatched structured extras with an empty `query` return an empty queue instead of playing the first playlist.
  - Filtered `albumName` matches by `artist` when provided, and honored `MediaStore.EXTRA_MEDIA_FOCUS`.
  - Guarded null `serverUri(track)` with a `localbeat://unavailable/<id>` placeholder rejected by `ResolvingDataSource` as `StreamBlockedException`, avoiding ExoPlayer NPE on `localConfiguration == null`.
  - Resolved temporary offline-created playlist IDs via `server.playlistId(id)` in browse/resolve lookups.
  - Hoisted compiled `Regex` instances out of `searchKey()`.
- **`ServerSeekPlayer` `ForwardingPlayer` fixes** (`ServerSeekPlayer.kt`): Overrode `seekForward()`, `seekBack()`, and `seekToDefaultPosition()` to route through `reloadAt()`, re-emitted `onAvailableCommandsChanged()` on media item transitions, and made `addListener()` idempotent to prevent wrapper leaks.
- **`ControlProvider` hardening** (`ControlProvider.kt`): Replaced `future.cancel(true)` with `MediaController.releaseFuture(future)` on timeout/error; awaited library readiness in `play()` before concluding a query has no matches; rejected negative/`Infinity`/`NaN` values in `ControlArgs.millis()`; recognized `artistall:` and `songs` in `ControlArgs.item()`.

## Server Sync, Offline Queue & Playlists

- **Prevent playlist wiping & edit queue corruption** (`SubsonicServer.kt`):
  - `sendEdits()` defers song-referencing edits (`Create`, `Add`, `SetSongs`) if the catalogue hasn't been fetched yet and any track ID is unresolved, preventing empty-list overwrites on the server.
  - `update()` cancels `sendJob`, clears `madeAs`, and resets `formPost`; `sendEdits()`, `fetch()`, and `checkNow()` re-check `api === a` after blocking network calls so old-server responses never corrupt a new server's queue or flip `_connected`/`_status`.
  - Persisted `formPost` per `serverKey()` so restarts don't fall back to GET URLs for large playlist edits.
  - Resolved `madeAs[id] ?: id` inside `synchronized(this)` in `edit()` to fix the TOCTOU race on newly created offline playlists.
  - Consumed mutations in `sendEdits()` once the write HTTP call succeeds even if the follow-up `a.playlist()` read fails, preventing duplicate replays or false conflict drops.
  - Serialized `fetch()` with `fetchMutex`, synchronized `savePlaylists()`, and serialized `runDownloads()`/`unpin()` with `downloadMutex` and `offlineLock` to prevent `.tmp`/`.part` races and stale `ready` entries.
  - Enforced `meteredBlock()` and a 16 MB read cap in `coverBytes()`.
- **`SubsonicApi` resilience** (`SubsonicApi.kt`): Skipped error 70 (deleted/inaccessible playlists) in `playlists()` by calling `playlist(id)`; added a page cap and duplicate-ID stop condition in `paged()`; bounded all HTTP response reads with `readLimited()`; threw clean HTTP errors for non-JSON error pages instead of raw `JSONException`.
- **`ConnectionGate` & credential redaction** (`ConnectionGate.kt`, `MusicServer.kt`, `ConnectLink.kt`): Synchronized `ConnectionGate` methods and cleared `freshUntil` in `connectNow()`; masked `password` in `ServerSettings.toString()` and `ConnectLink.toString()`.
- **Local playlists & M3U** (`M3u.kt`, `PlaylistStore.kt`, `MoveToServer.kt`):
  - Escaped `#`-prefixed relative paths with `./` on write and stripped `./` on parse so tracks under folders like `#1 Hits/` round-trip without being dropped as comments.
  - Stripped both `\r` and `\n` from playlist names and `#EXTINF` metadata.
  - Bounds-checked `PlaylistStore.move()` and checked `renameTo()` in `PlaylistStore.save()`.
  - Normalized `\` to `/` and preserved dotted folder names for extensionless files in `MoveToServer.pathKey()`.

## Library, Tags, Art & Audio

- **Bounded parsing** (`OggTags.kt`, `LibraryCache.kt`): Bound-checked untrusted lengths in `parsePictureBlock()`, `PageStream.readLength()`, and `LibraryCache.read()` against buffer/file size; cleaned up `.tmp` on cache write failure; fixed `VorbisComments.get()` to skip leading blank values on repeated keys.
- **Cover cache & provider** (`ArtStore.kt`, `ArtworkProvider.kt`, `LocalBeatApp.kt`): Ensured `dir.mkdirs()` before creating temp files, skipped `.tmp` files in `prune()`, deferred pruning while an enabled server hasn't loaded its catalogue yet, cleared/trimmed `ArtMemory` on rescan and `onTrimMemory()`, and converted `IOException` to `FileNotFoundException` in `ArtworkProvider.openFile()`.
- **Library & search** (`Library.kt`, `Search.kt`, `LibraryRepository.kt`): Hoisted `Regex` instances out of `fold()` and `Search.key()`; resolved `standIns` in `Library.albumOf(trackId)`; prevented startup `LibraryCache.read()` from overwriting a completed `scan()`; synchronized `_generation` updates; skipped spawning coroutines for in-memory cache hits in `scan()`.
- **Equalizer precision & presets** (`Eq.kt`, `EqualizerProcessor.kt`, `EqualizerStore.kt`): Switched biquad coefficients and filter state to `DoubleArray` (avoiding low-frequency quantization at 96/192 kHz); passed actual `sampleRate` to `Eq.peakDb()`; rejected blank custom preset names, sanitized `\r`/`\n`/`\t`, and filtered/clamped non-finite floats.

## UI, Compose & Activity

- **Paused seek bar snap-back** (`LocalBeatUi.kt`, `PlayerConnection.kt`): Added `positionRevision` (bumped on `seekTo()` and `EVENT_POSITION_DISCONTINUITY`) to `Progress`'s `LaunchedEffect` keys so paused seeks and external seeks update immediately.
- **Configuration change persistence & intent safety** (`MainActivity.kt`, `LocalBeatUi.kt`): Moved `Ui`, `PlayerConnection`, and `connectOffer` into `MainViewModel` so rotation/split-screen/theme changes preserve the back stack, search query, and open `ConnectDialog`; capped `importPlaylist()` stream reads at 2 MB and cleared `SaveableStateHolder` entries via `ui.clearStack()`.
- **Controller, reorder & list safety** (`PlayerConnection.kt`, `Reorder.kt`, `FastScroll.kt`, `NowPlaying.kt`): Guarded `MediaController` connect/release against stale futures and removed listeners on release; bounds-checked `skipTo`/`removeAt`/`move`; prevented multi-event-frame index corruption and stuck `override` in `ReorderState`; wrapped `FastScroll` drag loop in `try/finally` with `index.getOrNull()`; gated `PlayingOverlay` and remove button during drags in `NowPlayingBody`.
- **Rendering & state polish** (`AlbumArt.kt`, `ServerSettings.kt`, `SearchScreen.kt`, `PlayingIndicator.kt`, `PlaylistScreens.kt`, `Components.kt`, `PrismWidgets.kt`, `EqualizerScreen.kt`, `AndroidManifest.xml`): Eliminated 1-frame stale cover flash in `AlbumArt`; keyed `ServerSettings` text fields individually so toggling switches doesn't wipe unsaved edits; cleared stale search results immediately on query change; stopped `PlayingBars` infinite animation while paused; added fallback UI when an open server page/playlist disappears; prefixed `AddToPlaylistDialog` `LazyColumn` keys; fixed `PlayPauseButton` disabled alpha order; guarded zero-height canvas in `EqGraph`; fixed misplaced `FileProvider` comment in `AndroidManifest.xml`.
