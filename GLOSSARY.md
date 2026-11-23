# LocalBeat

The LocalBeat suite plays the user's own CD rips and older copies on three
surfaces: the phone app, Android Auto, and LocalBeat Studio on the desktop.
They share one map of the library and one vocabulary.

## The library

**Album**:
A release as the tags describe it, grouped by tags, never by folder.

**Artist**:
The artist an album or song is filed under.
_Avoid_: Album artist (on screen)

**Song**:
One recording on an album.
_Avoid_: Track (on screen)

**Genre**:
A broad style (e.g. Rock) that groups one or more subgenres.
_Avoid_: Broad genre (on screen)

**Subgenre**:
A specific style within a genre (e.g. Jangle pop within Rock).

**Mood**:
A feeling tag on an album (e.g. Driving, Mellow), written by Studio's AI Genres & Moods tool.

**Decade**:
The ten years an album was first released in, by original release year (e.g. 1980s).

**CD rip**:
An album ripped from CD and verified; every other album is an older copy or unknown.
_Avoid_: Verified FLAC, CD RIP (all caps)

## Ways in

**Entry point**:
One of the four shared ways into the library: Explore, Library, Playlists, Search. Each surface shows them in its own form.

**Explore**:
The entry point for discovering music: Shuffle all, Random album, Recently added, Moods, Genres, Decades.
_Avoid_: Browse, Quick Play

**Library**:
The entry point that lists everything by Albums, Artists and Songs.

**Recently added**:
Albums in the order they arrived in the library, newest arrival first.
_Avoid_: Newest, Recently Added

**Playlist**:
A named, ordered list of songs, belonging to a user and stored on the music server (or only on the phone when there is no server).

**Search**:
The entry point that finds moods, genres, decades, playlists, artists, albums and songs by typed words.

## Playing

**Queue**:
The songs that will play next, in order.
_Avoid_: Up Next

**Player**:
The transport controls and the playing song. At the top of the screen on the phone; a bottom bar in Studio.

## Where music is

**Music server**:
The user's LocalBeatServer, reached over WireGuard, holding the full library.
_Avoid_: LocalBeat server, the server, Subsonic server

**Synced**:
An album copied onto the phone by Studio's Phone page; only Studio adds or removes it.
_Avoid_: Kept, downloaded

**Kept**:
A music-server album the phone has downloaded so it plays without the server; the phone can remove it.
_Avoid_: Synced, pinned, offline

**On this phone**:
Music that plays without the music server: synced albums, kept albums and loose files.
_Avoid_: On phone, local music
