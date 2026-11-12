# Controlling LocalBeat from a computer: design note (2026-11-12)

Status: idea, nothing built. Remote access is on hold until the Mac Mini is
running; this is planned now and built later.

## The idea

A command line on the laptop that controls the phone app fully:
- **Real-time control:** play, pause, skip and seek, plus what's playing.
- **The library:** list and search albums, artists and songs.
- **The queue and playlists:** show, build and change them.

Everything comes back as JSON, so scripts and AI assistants can use it as
well as people. On top of that, an **MCP server** lets an AI (Claude Code,
Claude Desktop, others) use it as a set of tools: "make a 1970s
singer-songwriter playlist and play it in the car". Beyond that, it's a
platform: a stable, documented command set that other things can be built on.

## Principles

- **The offline edition stays offline.** No INTERNET permission, no
  sockets, not even on the phone itself (a localhost web server would need
  INTERNET). So the channel is **adb**, over USB or wireless debugging.
- **No new way in.** Only adb may use the control channel. Other apps on the
  phone can't, and anything with adb access can already control the phone.
- **Works on a Google-free phone:** adb and content providers are plain
  Android. No Google services are involved.
- **One set of rules.** Commands go through the same code as the app's own
  screens and Android Auto (`BrowseTree`, `PlaylistStore`, the
  `MediaLibraryService`), so a playlist made from the command line is the same
  as one made by hand.
- **A versioned contract.** Commands and JSON are documented and versioned
  (`api_version`). New fields may be added; anything that changes or goes away
  means a new version.

## The layers

```
AI (Claude Code, Claude Desktop, …)
   │  MCP (tools)
   ▼
localbeat mcp            ← a thin MCP server: one tool per command
   │
localbeat player …       ← the CLI on the laptop (JSON out)
   │  adb shell content call …
   ▼
LocalBeat on the phone: ControlProvider (callers other than adb refused)
   │
PlaybackService / Library / PlaylistStore / QueueStore
```

### 1. On the phone: a control provider

An exported `ContentProvider`, `content://com.jimboyo.localbeat.control`,
that only implements `call(method, arg, extras)`. From the laptop:

```sh
adb shell content call --uri content://com.jimboyo.localbeat.control \
    --method status
adb shell content call --uri content://com.jimboyo.localbeat.control \
    --method play --arg album:a1b2c3 --extra shuffle:b:true
```

- **Request and response:** `call` returns a Bundle. The reply is one string
  of JSON in it, so commands like `songs` or `status` return data, not just
  "sent".
- **adb only:** `call` checks `Binder.getCallingUid()` and refuses anyone
  but the shell user (2000; adb runs as it) and root. No permission dialog,
  nothing for other apps.
- **Playback** goes through a `MediaController` connected to
  `PlaybackService`, the same way the UI does, so the notification, lock
  screen and Android Auto all stay in step.
- **Lives in `main`**, so both editions have it.
- **Large replies:** a Binder reply has to stay under about 1 MB. The full
  song list (thousands of songs) may need paging (`--extra offset/limit`).

### 2. On the laptop: `localbeat player …`

Part of LocalBeat Studio's `localbeat` command (CDRipPipeline `app/cli`,
Rust), so the suite has one `localbeat`. It finds the phone the way
`localbeat sync` does (USB or wireless debugging), turns arguments into
`content call`s and prints JSON (`--human` for a readable table).

Sketch of the commands (names to settle when built):

| Area | Commands |
|---|---|
| Now | `status` (song, album, position, playing/paused, shuffle, repeat, queue position) |
| Control | `play`, `pause`, `toggle`, `next`, `prev`, `seek 1:30`, `shuffle on/off`, `repeat off/all/one`, `volume` (maybe) |
| Play something | `play album <id>`, `play artist <id>`, `play playlist <id>`, `play songs <id>…`, `--shuffle`, `--from <n>` |
| Library | `albums`, `artists`, `songs [--album/--artist/--genre/--decade]`, `genres`, `decades`, `recent`, `search <text>`, `album <id>` (with its songs) |
| Queue | `queue`, `queue add <id>… [--next]`, `queue remove <n>`, `queue move <from> <to>`, `queue clear` |
| Playlists | `playlists`, `playlist <id>`, `playlist new <name> [songs…]`, `playlist add/remove/move/rename/delete`, `playlist export <id>` (.m3u8) |
| Live | `watch`: prints one JSON line per change (song, play/pause, queue) |
| Info | `version` (app version, `api_version`, edition) |

- **IDs:** the library's own IDs (`t…` songs, `a…` albums, `r…` artists),
  plus the Music-relative path for songs, which playlists use and which
  survives a rescan. Search results carry both, so you can search and then
  play.
- **`watch` without sockets:** a `wait` method that blocks until something
  changes or 30 s pass, then returns the new state and a change number;
  the CLI calls it in a loop. No polling every second, no INTERNET.

### 3. For AI: `localbeat mcp`

**MCP (Model Context Protocol)** is an open standard for giving an AI
assistant tools: a small program declares its tools (name, description,
JSON parameters), and the AI app runs it and calls them. Here, each CLI
command becomes a tool (`search_library`, `play`, `create_playlist`, …).

- A subcommand of the same `localbeat` binary, speaking MCP over
  stdin/stdout (the "stdio" transport): nothing listens on the network.
  Claude Code adds it with `claude mcp add localbeat -- localbeat mcp`.
- Thin: it calls the same code as the CLI, so they never disagree.
- **Without MCP:** Claude Code can already run the CLI through its shell,
  so the CLI alone is enough for Claude Code. MCP makes it work in apps
  without a shell (Claude Desktop, others) and gives the AI clear tool
  descriptions.
- Tools that change things (delete a playlist, clear the queue) say so in
  their description, so the AI app can ask before using them.

## The Connected edition later

With the music server, the same command set could also go over the network
(the server passing commands to the phone, or the phone answering on the
WireGuard tunnel). Not planned: adb first; revisit once remote access works.

## Asking for music in words ("play me some great driving songs")

**What the AI already knows is most of it.** For well-known music, an AI
knows which songs suit driving, a rainy Sunday or a party, and what they
sound like. What it doesn't know is **what's in this library**, so the tools
have to make that cheap to look at:

- `songs --compact`: one short line per song (id, artist, title, year,
  genres), so the whole library fits in one reply. That's about 3,900 songs
  today (2026-11), roughly 80k tokens: workable, but not on every request. Paged and
  filterable (`--genre rock --decade 1970`).
- Or the other way round: the AI suggests songs from its own knowledge, and
  `match` checks which are here: a list of "artist – title" lines in, the
  library's IDs out (folded like `Library.kt`, so curly and straight
  apostrophes match). Cheap, and it suits "great driving songs" well.
- Then `play songs …` or `playlist new "Driving" …`.

**Metadata that would help** (in order of value):

| What | Where from | Notes |
|---|---|---|
| Genres (have), original year (have) | MusicBrainz, via `mb-enrich.py` | Already in the tags; fine for "70s rock" |
| **Play history**: play counts, last played, skips | The phone itself | Local and private. "Something I haven't heard in a while", "my favourites". The most useful new data, and no network |
| Tempo, energy, mood, "danceable" | **Measured on the laptop** from the FLAC (e.g. Essentia, open source, offline) | Good for "upbeat", "calm", "fast" without trusting anyone's tags. Written as tags by Studio, read by the phone |
| MusicBrainz community tags ("road trip", "melancholic") | MusicBrainz, by the IDs we already have | Patchy for less-known albums |
| **Lyrics** | **LRCLIB** (free, open, no account, time-synced) on the laptop | Saved as `.lrc` next to the songs or in a LYRICS tag; the app only reads files (CLAUDE.md: lyrics only from the files). Lyrics are copyrighted: fine for personal use, never shared or published |

Rule for all of it: **the laptop (LocalBeat Studio) fetches or measures, and
writes tags or files; the phone only reads them.** That keeps the offline
edition offline, the same as `localbeat enrich` today.

## Asking from the phone (no laptop)

- **An AI app on the phone can't reach an adb-only channel.** The Claude
  phone app (and others) use *remote* MCP servers: the AI company's servers
  connect to an address on the internet. LocalBeat's offline edition can't
  be that, and exposing a home server to the internet goes against the
  WireGuard-only plan in `remote-access.md`.
- **Voice already half-works:** `BrowseTree.voice` handles Android's
  "play X on LocalBeat" (needs Google Assistant or Gemini, so not on a
  Google-free phone). It matches names; it doesn't understand "driving
  songs".
- **Options for later:** (a) an AI inside the Connected edition calling an
  AI company's API directly (network, an account and a key: a big step away
  from the principles); (b) an MCP endpoint on the home server, reachable
  only on the tunnel, with an AI app that runs on the laptop or on a home
  computer; (c) on-phone AI models (Android's built-in Gemini Nano, or an
  open model) once they are good enough: offline, but Google-dependent or
  heavy. **Decide after the laptop version works.**

## Order of work

Each step ends with something that works; 2 and 3 already give control.

1. **The contract** (`docs/control-api.md`): method names, arguments, JSON
   for each reply, error format, IDs, `api_version`. Reviewed before any
   code.
   *Done when:* every command in the table above has an example request and
   reply.
2. **The control channel on the phone**: the content provider, adb-only,
   with `version`, `status`, `play`/`pause`/`toggle`/`next`/`prev`/`seek`,
   shuffle and repeat. **First, prove the risky part:** starting playback
   while the app is in the background or closed (Android limits starting
   foreground services; Media3 may cover it).
   *Done when:* `adb shell content call …` pauses and resumes music with
   the screen off, and another app's call is refused.
3. **`localbeat player` on the laptop** (CDRipPipeline `app/cli`): finds
   the phone like `localbeat sync`, JSON out, `--human` tables, clear errors
   ("phone not connected", "LocalBeat too old: update it").
   *Done when:* `localbeat player status` and `pause` work over USB and
   wireless debugging.
4. **Library and playing things:** `albums`, `artists`, `songs`, `genres`,
   `decades`, `recent`, `search`, `match`, `album <id>`, `play album/artist/
   playlist/songs`, and the queue (`queue`, `add [--next]`, `remove`,
   `move`, `clear`). Paging for big lists.
   *Done when:* "search, then play the third result" works, and the whole
   library lists in pages.
5. **Playlists:** `playlists`, `playlist <id>`, `new`, `add`, `remove`,
   `move`, `rename`, `delete`, `export`. Same `PlaylistStore` as the app.
   *Done when:* a playlist made from the laptop shows up in the app and in
   Android Auto, and exports as the same .m3u8.
6. **`watch`:** the blocking `wait` method on the phone, a loop in the CLI,
   one JSON line per change.
   *Done when:* skipping a song on the phone prints a line on the laptop
   within a second.
7. **`localbeat mcp`:** an MCP server over stdio, one tool per command, with
   descriptions written for an AI, and "changes things" marked.
   *Done when:* Claude Code (`claude mcp add localbeat -- localbeat mcp`)
   builds and plays "great driving songs" from this library.

**After the seven:** play history on the phone (counts, last played,
skips), then tempo/energy measured on the laptop, then lyrics (LRCLIB, by
Studio). Each makes the AI better at choosing.

## Open questions

- Which extra metadata to go after, and in what order (see "Asking for
  music in words").
- Asking from the phone without the laptop: which of the options, if any.

- Where the CLI lives: Studio's `localbeat` (recommended) or a separate
  tool in this repo.
- Whether controlling playback should wake the app if it isn't running
  (probably yes: a `call` starts the app's process anyway).
- Volume: the phone's media volume (`adb shell cmd media_session volume`)
  or nothing.
- Testing: unit tests for the JSON (Robolectric), plus a script that runs
  the CLI against the phone.
