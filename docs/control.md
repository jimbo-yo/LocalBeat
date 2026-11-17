# AI control and playlists: design note

Written 2026-11-12 (adb-only, before the server existed); **revised
2026-11-17** now that the music server runs on the Mac Mini. Status: design
agreed 2026-11-17. Done: step 1 (server playlists, on the Mac) and step 2 (Studio's `localbeat playlist` and `localbeat match`).

## The idea

Let Claude (or another AI) work with LocalBeat the way a person would:
search the library, build playlists from things found elsewhere ("the 50
best road-trip songs" from a web page, matched against what's here), change
them, and later play them on the phone. A playlist made this way should show
up **everywhere**: on the phone, in LocalBeat Studio and to the AI.

## Decisions

- **Playlists live on the music server** (user, 2026-11-17): LocalBeatServer
  on the Mac Mini stores them, so one playlist shows on the phone, in Studio
  and to an AI. Standard Subsonic/OpenSubsonic calls (`getPlaylists`,
  `getPlaylist`, `createPlaylist`, `updatePlaylist`, `deletePlaylist`), so
  any Subsonic app (and Navidrome, for the tests) works the same way.
- **The phone keeps its own playlists** (`PlaylistStore`, .m3u8 in the app's
  storage) for the offline edition and for using the Connected edition with
  no server. The Connected edition shows both kinds side by side.
- **The AI talks to the server, not to the phone.** `localbeat mcp` (part of
  Studio's `localbeat` command) is an MCP server over stdio that calls the
  server's Subsonic API with Studio's login (`localbeat.json` `server`).
  Searching and building playlists need no phone at all.
- **Live playback control** (play/pause/skip on the phone) stays the adb
  channel from the first version of this note, and comes later (see "Live
  control on the phone").
- **No new way in.** Everything goes over the existing Subsonic API, inside
  WireGuard / the home network, with the existing logins (one per device, each attached to a user).

## The layers

```
AI (Claude Code, Claude Desktop, …)
   │  MCP over stdio (tools)
   ▼
localbeat mcp              ← Studio's CLI (CDRipPipeline cli/), JSON tools
   │  Subsonic API (core subsonic.rs), Studio's login
   ▼
LocalBeatServer (Mac Mini) ── playlists stored here ──┐
   ▲                                                  │
   │  Subsonic API (the phone's own login)            │ same playlists
LocalBeat Connected (phone) ◄─────────────────────────┘
LocalBeat Studio (Library page, later)

Later, live control:  localbeat player … ─adb─► ControlProvider on the phone
```

## 1. Playlists on the server (LocalBeatServer)

### Who sees what

**Everyone has their own playlists and can share one with other people on
the server** (user, 2026-11-17).

- **Users and devices** (user, 2026-11-17): every device (a phone, Studio on
  a computer) is **attached to one user** when it joins. Today's logins
  become devices: `my-phone`, `my-windows-pc`, `my-linux-pc` all belong to
  user `jim`. Playlists belong to the **user**, so every phone and every
  Studio of that user sees and edits the same playlists, with nothing to
  sync by hand; the AI, using Studio's device login, works on the same ones.
- The server learns who is who from a **users file**,
  `/etc/localbeat/users` (setting `users = "…"`), read at start and on
  SIGHUP like the logins:

  ```
  # user: devices
  jim: my-phone my-windows-pc my-linux-pc
  ```

  A device not listed is a user of its own (named after the device), so
  nothing breaks before the file exists. `localbeat-people add DEVICE --user
  jim` writes it, `list` shows devices under their user, and the admin page
  asks "whose device?" when approving a join (an existing user, or a new
  one). Studio's join request can suggest the user; the admin decides.
- **Sharing:** the owner can share a playlist with named people
  (`sharedWith`) or with everyone (Subsonic's `public` flag). People it's
  shared with **see and play it but don't change it**; they can copy it to
  make their own. Only the owner edits, renames,
  shares or deletes (from any of their devices).
- `getPlaylists` returns: mine, shared with me, public; each with `owner`
  (the user) and, for my own, `sharedWith`.
- Subsonic has no "share with a user" call, so this is **our OpenSubsonic
  extension `localbeatPlaylistSharing`** (declared in
  `getOpenSubsonicExtensions`; clients use it only when declared):
  `createPlaylist`/`updatePlaylist` accept `sharedWith` (repeated; the new
  list; `sharedWith=` with no value clears it), and `getUsers` lists the
  users (`username` = user) for a "share with…" picker, for any device.

### Where and how they're stored

- One **JSON file per playlist** in a playlists folder on the Mac:
  `/var/lib/localbeat/playlists/<id>.json` (systemd `StateDirectory=localbeat`;
  owned by the `localbeat` user). Setting `playlists = "/path"` in
  `server.toml`; without it the playlist calls answer "not available".
  **Not in the cache folder** (that one is disposable) and **not under
  `/srv/music`** (the server only reads the library; writers own it).
- Written to `<id>.json.tmp`, synced, then renamed (a crash never leaves half
  a playlist). Read once at start; kept in memory; one lock for changes.
- The folder needs backing up with the library (`plan.md`: backups).
- Playlist IDs: `p` + 12 hex digits (random). File:

```json
{
  "id": "p3f9a0c1d2e4b",
  "name": "Road trip",
  "comment": "From the Rolling Stone list, 2026-11-17",
  "owner": "jim",
  "public": false,
  "sharedWith": ["sam"],
  "created": 1795392000,
  "changed": 1795392600,
  "entries": [
    {
      "path": "Bruce Springsteen/Born to Run/01. Bruce Springsteen - Thunder Road.flac",
      "recording": "bbbbbbbb-0000-4000-8000-000000000001",
      "artist": "Bruce Springsteen",
      "title": "Thunder Road",
      "album": "Born to Run",
      "duration": 289
    }
  ]
}
```

### How songs are referenced (survives a rescan and a re-rip)

A server song ID is `s` + a hash of the file's path inside its library, so
it already survives a rescan, and a re-rip that writes the same file name
keeps it. It changes when the file name changes (a corrected title, a
Miscellaneous album replaced by a FLAC rip). So an entry stores the
**path** plus enough to find the song again:

1. **path** (as `song.path` in the API): the normal case, exact.
2. else the **MusicBrainz recording ID** (`MUSICBRAINZ_TRACKID`), preferring
   the FLAC library and the same album name: a re-rip with a new name, or a
   Misc album superseded by a FLAC rip.
3. else **artist + title** folded like the library (case, curly quotes),
   then album, then the duration closest: older rips without MusicBrainz IDs.
4. else **missing**: kept in the file (the album may come back), left out of
   `getPlaylist` replies, counted in a `missing` field (our extension) so a
   client can say "2 songs not found".

Resolving is done at each scan (in memory, cheap); when an entry resolves by
2 or 3, its stored `path` is updated to the found file so it's exact again.

### The calls (standard Subsonic 1.16.1 / OpenSubsonic)

| Call | Parameters | Notes |
|---|---|---|
| `getPlaylists` | (`username` ignored) | Mine, shared with me, public: `id`, `name`, `comment`, `owner`, `public`, `songCount`, `duration`, `created`, `changed`, `coverArt` (first song's album) |
| `getPlaylist` | `id` | The same plus `entry[]` (songs, like `getAlbum`'s), and `missing` |
| `createPlaylist` | `name` + `songId`… | New playlist; returns it (1.14+) |
| `createPlaylist` | `playlistId` + `songId`… (+ `name`) | **Replaces the songs** (how clients reorder: there is no "move") |
| `updatePlaylist` | `playlistId`, `name`, `comment`, `public`, `songIdToAdd`…, `songIndexToRemove`… | Removals by index in the current list (indices of the songs shown, not the missing ones), done before additions |
| `deletePlaylist` | `id` | Deletes the file |
| `getUsers` | | The people, for sharing (extension, see above) |

- Changing someone else's playlist (shared with me or public): error 50
  (not authorized), nothing changes.

- **Repeated parameters** (`songId=a&songId=b`): the server's parameter
  reading becomes a multimap.
- **Long lists:** OpenSubsonic `formPost` extension: parameters may come in a
  `application/x-www-form-urlencoded` POST body (a 200-song playlist doesn't
  fit a URL nicely). Declared in `getOpenSubsonicExtensions`.
- Unknown song IDs in `songId`/`songIdToAdd`: error 70 and nothing changes
  (the AI should match first; a silently shorter playlist would mislead).
- Logged like other calls (name of the call, `id`), never the song lists.

## 2. Matching: lists from elsewhere → library songs

"Artist – Title" lines in, library song IDs out. **Done in Studio, not on
the server** (keeps the server standard; Studio already fetches the whole
catalogue for its Library page, `server_library.rs`):

- Input: one song per line, `Artist - Title` (also `–`, `—`, `"Title" by
  Artist`, a leading number "12." dropped).
- Folded like the library (case, curly quotes, `&`/`and`, accents kept),
  plus title clean-up: "(Remastered 2011)", "- Live", "(feat. …)" ignored
  for matching but reported.
- Per line: `found` (one song, or several: all versions, the FLAC/studio
  album first), `close` (fuzzy: suggestions with why), or `not_found`.
- Never guesses silently: the reply says how each line matched, so the AI
  (or the person) decides.

## 3. `localbeat mcp` (Studio's CLI)

An MCP server over stdio, `claude mcp add localbeat -- localbeat mcp`. Small,
hand-written JSON-RPC (initialize, tools/list, tools/call) on `serde_json`;
no MCP SDK unless that turns out simpler. Uses the `server` login from
`localbeat.json` and the catalogue cache.

| Tool | What | Changes things |
|---|---|---|
| `library_summary` | Counts, genres, decades, artists with album counts | |
| `search` | Text search (artist, album, title), with filters genre/decade | |
| `list_songs` | Compact lines (id, artist, title, year, genres), paged and filterable: the whole library is ~80k tokens, so filtered | |
| `match_songs` | Lines in → matches out (section 2) | |
| `list_playlists`, `get_playlist` | | |
| `create_playlist` | name, comment, song IDs | yes |
| `add_to_playlist`, `remove_from_playlist`, `reorder_playlist`, `rename_playlist` | | yes |
| `delete_playlist` | | yes (asks the AI to confirm with the user in its description) |

Typical use: the AI reads a web page of road-trip songs, calls
`match_songs`, shows what's here and what isn't, then `create_playlist`.

The same commands also exist for people and scripts: `localbeat playlist
[list|show|new|add|remove|rm]` and `localbeat match < list.txt`, JSON with
`--json`.

## 4. The phone (Connected edition)

- `SubsonicApi.kt`: the five calls (POST form for long lists when the server
  declares `formPost`, else GET).
- `SubsonicServer.kt`: fetches `getPlaylists` with the albums (and again on
  rescan / coming back), entries as server tracks (`trackId(serverKey,
  songId)`), so offline copies and streaming work as for albums.
- **With a server, the phone's playlists are the user's server playlists.**
  One list, no "on this phone / on the server" split: what the user makes
  in Studio, with the AI or on another phone is simply there. Shared with
  me / public ones in their own section ("From sam"), read only, with "Make
  a copy". They play like any playlist (and in Android Auto).
- **Works with the server out of reach:** the last fetched playlists are
  kept on the phone (and their songs, when downloaded for offline, play as
  today). Changes made meanwhile are queued as operations (add, remove,
  move, rename, new, delete) and sent in order when the server is back;
  every change is "set the playlist to this", so a resend is harmless. If
  the same playlist changed on the server meanwhile, the server's version
  wins and the phone says so (rare: one user, edits on one device at a
  time).
- Editing sends the change at once when reachable; a move is
  `createPlaylist playlistId=… songId=…` with the new order.
- **Moving in:** the first time a phone with its own playlists connects to
  a server, it offers to move them to the user's server playlists
  (matching Music-relative paths to server songs by path without extension,
  since the phone has `.opus` copies of the server's `.flac`, else artist +
  title; songs not found listed). Kept on the phone until that succeeds.
- Offline edition, or Connected with no server set: `PlaylistStore` as
  today.

## 5. LocalBeat Studio

- Studio's playlists are the same user's server playlists: the Library page
  shows them (play, rename, remove and reorder songs, "add to playlist" on
  songs and albums), cached for when the server is out of reach, changes
  queued like the phone's. Not needed for the AI to work, so after the
  phone.
- Studio already joins as a device of a user (`docs/joining.md`); the join
  request asks which user.

## Live control on the phone (later; from the 2026-11-12 plan)

Unchanged in substance, moved after playlists:

- An exported `ContentProvider` (`content://com.jimboyo.localbeat.control`),
  only `call(method, arg, extras)`, refusing every caller but adb's shell
  user (uid 2000) and root; one JSON string per reply; in `main`, so both
  editions have it. Playback through a `MediaController` on
  `PlaybackService`, so notification, lock screen and Android Auto agree.
- `localbeat player …` in Studio's CLI finds the phone like `localbeat sync`
  (USB or wireless debugging) and runs `adb shell content call …`:
  `status`, `play`/`pause`/`toggle`/`next`/`prev`/`seek`, shuffle, repeat,
  `play playlist <id>` (phone or server playlist), the queue, and `watch`
  (a blocking `wait` method, one JSON line per change).
- Then MCP tools `now_playing`, `play`, `pause`, `skip`, `play_playlist`.
- First prove the risky part: starting playback with the app in the
  background (Android's limits on starting foreground services).
- Over the network instead of adb (the server passing commands to the phone)
  is not planned.

## Asking for music in words

**What the AI already knows is most of it.** For well-known music it knows
which songs suit driving, a rainy Sunday or a party. What it doesn't know is
what's in this library, so the tools make that cheap: `match_songs` (the AI
suggests, the library confirms), and filtered `list_songs`.

**Metadata that would help** (in order of value):

| What | Where from | Notes |
|---|---|---|
| Genres (have), original year (have) | MusicBrainz, by Studio | Already in the tags |
| **Play history**: counts, last played, skips | The phone, or the server's `stream` log | "Something I haven't heard in a while". Subsonic has `scrobble` for this |
| Tempo, energy, mood | Measured by Studio from the FLAC (e.g. Essentia, offline) | Written as tags; everyone reads them |
| MusicBrainz community tags ("road trip") | MusicBrainz, by our IDs | Patchy |
| Lyrics | LRCLIB, by Studio | `.lrc` or a LYRICS tag; personal use only |

Rule: **Studio fetches or measures and writes tags or files; the server and
the phone only read them.**

## Asking from the phone (no computer)

An AI app on the phone uses *remote* MCP servers (reached from the AI
company's servers over the internet), which goes against the WireGuard-only
rule (`remote-access.md`). Options for later: an MCP endpoint on the Mac,
tunnel-only, for an AI app that runs on a home computer; or on-phone models.
Decide after the computer version works.

## Order of work

Each step ends with something that works.

1. **Playlists on the server** (LocalBeatServer): store, the five calls,
   users file (devices → user), sharing extension + `getUsers`, repeated
   parameters, `formPost`, resolving entries after a rescan.
   Tests in `tests/api.rs` (incl. two devices of one user seeing the same
   playlists, sharing, a renamed file found again by recording ID, a
   missing one kept).
   *Done when:* the tests pass and, on the Mac, a playlist made with `curl`
   survives a server restart.
1b. **Devices attached to users on the Mac**: `localbeat-people add DEVICE
   --user NAME` (and `list` grouped by user), the admin page asks "whose
   device?" on Approve, `/etc/localbeat/users` written for today's devices.
   *Done when:* `my-phone` and `my-windows-pc` show as jim's devices.
2. **Studio CLI: playlists and matching** (CDRipPipeline): core
   `subsonic.rs` playlist calls, `localbeat playlist …`, `localbeat match`.
   *Done when:* a text file of 30 road-trip songs becomes a server playlist
   from the Windows laptop, with a clear list of what wasn't found.
3. **`localbeat mcp`**: the tools above over stdio.
   *Done when:* Claude Code (`claude mcp add localbeat -- localbeat mcp`)
   builds a road-trip playlist from a web page.
4. **Phone: the user's server playlists, shown and played** (Connected
   edition, read only, kept for when the server is out of reach).
   *Done when:* step 3's playlist plays on the phone, also offline after
   downloading, and in Android Auto.
5. **Phone: editing (queued while out of reach), shared ones, moving the
   phone's own playlists in.**
   *Done when:* a song added on the phone shows in Studio's `localbeat
   playlist show`, and one added in airplane mode arrives once back.
6. **Studio Library page: the same playlists.**
7. **Live control on the phone** (adb), as above, then MCP tools for it.

After that: play history, tempo/energy, lyrics.

## Open questions

- Whether people a playlist is shared with may also edit it (now: view,
  play and copy only).
- Whether `match` should also be offered by the server (an OpenSubsonic
  extension) so the phone could import a text list without Studio.
- Navidrome as a test reference for playlists: its own storage differs
  (per user, private by default); clients use only the standard calls.
