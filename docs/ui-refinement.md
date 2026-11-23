# Interface refinement: phone, Android Auto, LocalBeat Studio

Decided with the user on 2026-11-23. Vocabulary: `GLOSSARY.md` (repo root)
is the one copy for the whole suite; use its terms in the UI, code comments
and docs. Studio lives in `~/Development/LocalBeatStudio` (`gui/`).

**Goal:** the three surfaces feel like one product (same names, same ways in,
same actions), then specific pain points, then discovery features. Studio is
an equal listening surface, not just a rip checker.

## One map of the library

Four **entry points**, same names everywhere: **Explore, Library, Playlists,
Search**.

| | Phone | Android Auto | Studio |
|---|---|---|---|
| Tabs / sidebar | Explore · Library · Playlists · Search (bottom bar) | Explore · Library · Playlists · Moods (root tabs; search via Auto's own button) | LIBRARY section: Explore · Albums · Artists · Songs · Playlists; search in the sidebar box |
| Explore | Overview line; Shuffle all, Random album; Recently added (shelf + See all); Moods; Genres; Decades | Shuffle all and Random album first (no "Quick Play" heading); Recently added (8 + See all); Genres; Decades | Same sections, order and names as the phone, laid out to the window width; "Recently added" leaves the sidebar |
| Library | Albums · Artists · Songs | Albums · Artists · Songs only (drop Playlists, Decades, Recently added folders) | Albums · Artists · Songs pages |

- Studio gains Genres (genre → subgenres), Moods and Decades pages with the
  phone's cross-filters, reached from Explore.
- Device and tool pages (Rip, Phone, Server, Covers, AI Genres & Moods,
  Review, Repairs) stay Studio-only, beside the shared pages.

## Wording

- **Queue**, never "Up Next" (Studio panel, tab, tooltip, "Add to queue").
- **Music server** in sentences and labels; badge "On the music server".
  Studio's sidebar page may stay "Server".
- **CD rip** badge (not all caps if Prism allows): phone album page and
  Studio; not on tiles, not in Auto. Phone reads it from
  `Music/.ripshelf-sync.json` (`flac:` source).
- **Synced** (put on by Studio's Phone page; only Studio removes it) vs
  **Kept** (downloaded from the music server by the phone). The switch is
  **"On this phone"** everywhere and covers both. "Remove from phone" only on
  kept albums. Retire "On phone", "Showing music on this phone" variants.
- Albums sort: **Title, Artist, Year, Recently added** (phone and Studio);
  Year = original release year. "Newest" goes.
- Genre pages: a **"Subgenres"** heading over the subgenre chips, "All (N)"
  first (phone and Studio).
- "Recently added", never "Recently Added".

## Behaviour

- **Play and Shuffle** on every album, artist, genre, subgenre, mood, decade
  and playlist page (phone, Studio). Studio's "Play all" goes.
- **Auto groups:** every group except an album starts with one playable item
  "Shuffle <name>" (replaces the artist's "All songs" and "Shuffle all X").
  An album lists its songs.
- **Menus** (song and album, every surface) start with: Play next, Add to
  queue, Add to playlist…, Go to album, Go to artist. Then a divider and the
  surface's own: Keep on phone / Remove from phone (phone); Show in folder,
  Remove from library… (Studio).
- **Search** (Studio) uses the phone's sections and order: Moods, Genres,
  Decades (chips), Playlists, Artists, Albums, Songs.
- **Tag chips:** Studio's album page and Now Playing Details show tappable
  genre, mood and decade chips, like the phone.
- **Player position:** the phone keeps controls at the top (never a bottom
  mini-player). Studio keeps its bottom bar (desktop convention).
- **Studio queue panel:** opens beside every LIBRARY page (not device or
  tool pages). Gets the phone's header ("N songs · m:ss left · shuffled"),
  Save (as playlist), Clear, drag to reorder, Remove from queue.
- **Shuffle and repeat** (both): a page's Shuffle plays that group shuffled
  now; the player's shuffle toggle reshuffles the rest of the queue at once;
  repeat cycles off → all → one.

## Order of work

1. **Phone + Auto (LocalBeat 0.9.22):** finish the uncommitted draft (Explore,
   moods, genres/subgenres, new tabs and browse tree) with the changes above:
   Auto root and Explore, Library contents, sort name, Subgenres heading,
   Play/Shuffle and "Shuffle <name>", menu order, "On this phone" wording,
   Synced vs Kept, CD rip badge. Tests, version bump, commit.
   *Status 2026-11-23:* built and installed. The app can't read
   `Music/.ripshelf-sync.json` (scoped storage), so "CD rip" comes from
   `LOCALBEAT_SOURCE=CD`, written by Studio's sync into each Opus made from a
   FLAC (`core/src/phone_copy.rs`), and for music-server songs from `suffix`
   flac (the server's FLAC library is the verified rips; no server change).
   Phone copies synced before this lack the tag until Studio re-encodes them.
   Studio's own badge for music-server albums can use the same suffix rule
   (step 2).
2. **Studio wording and menus:** Queue, music server, CD rip, menu order,
   search sections and order, album sort.
   *Status 2026-11-23:* built in `gui/src/library_ui.rs`. Search has
   Playlists, Artists, Albums, Songs; the Moods/Genres/Decades chips come
   with their pages in step 3. The sidebar Server item's tooltip in
   `main.rs` still says "The LocalBeat server" (that file had other work in
   progress).
3. **Studio Explore and group pages:** Explore, Genres/Subgenres, Moods,
   Decades, cross-filters, album-page and Details chips; Recently added moves
   into Explore.
   *Status 2026-11-23:* built (LocalBeatStudio `core/src/browse.rs`, the
   phone's grouping rules with its test library; `gui/src/library_ui/explore.rs`).
   Sidebar: Explore, Albums, Artists, Songs, Playlists; Explore is the
   default page. Genre/mood/decade pages have Subgenres and cross-filter
   chips like the phone, but no Albums/Artists view switch. Search starts with
   mood, genre and decade chips.
4. **Studio player behaviour:** queue panel on every LIBRARY page, queue
   actions, shuffle and repeat.

Studio's CLAUDE.md gets a pointer to `LocalBeat/GLOSSARY.md` and this file
("change in step") in step 2.
