# Remote access: design note (long-term plan, 2026-11-08)

Status: idea, nothing built. Written from a brainstorm while ripping CDs.

## The idea

Today the library lives on the laptop and reaches the phone over home Wi-Fi
or USB (CDRipPipeline's Phone Sync). The plan: a small always-on **home
server** holds the whole library, and the phone can reach it **from
anywhere**, either to sync albums or to browse everything and cache what's
played.

## Principles

- Self-hosted only: no accounts, no third-party cloud, no data collection.
- Optional: LocalBeat must stay fully usable with no server.
- Every phase below is useful on its own.

## Phases

### 1. Home server holding the library

- Hardware: Raspberry Pi 5 or an old mini PC + a large USB disk.
- OS: **Debian minimal** (or Alpine for truly tiny). Installed: OpenSSH,
  WireGuard, a dynamic-DNS updater, unattended security upgrades. Nothing else.
- Size guide: 1,000 CDs ~ 350 GB FLAC + ~70 GB Opus (160 kbps).
- The laptop (ripshelf / rip-cd.sh) pushes new rips to it over the LAN
  (rsync/SFTP over SSH, key-only login). FLAC is the master copy; Opus copies
  are made on the laptop or the server.

### 2. Reaching home from anywhere: WireGuard, not open SSH

- Don't expose SSH (port 22) to the internet: bots attack it within hours.
- **WireGuard** on the server: one UDP port, silent to anyone without a key,
  so it doesn't show up to scanners at all.
- **Dynamic DNS** gives the server a stable name that follows the home IP.
- The phone runs the WireGuard app. Connected, it's effectively on the home
  network, so Phone Sync (SFTP/KDE Connect) works unchanged from anywhere.
- **Check first:** whether the ISP uses **CGNAT** (no incoming connections
  possible). If so: IPv6, or a small cloud relay (a VPS running WireGuard that
  both ends connect out to).
- Mind the home **upload** speed: it caps how fast the phone can pull music.

### 3. Browse everything, cache what's played

The phone can't hold the whole library (Pixel 9: ~21 GB free in 2026-11), so
the interesting mode is: the whole catalogue visible, albums downloaded on
demand, pinned albums kept offline, a cache size limit with least-recently-
played eviction.

Two ways to serve it:

- **Navidrome** on the server (recommended starting point): a lightweight,
  self-hosted music server speaking the Subsonic/OpenSubsonic API; converts
  FLAC to Opus on the fly. LocalBeat becomes a Subsonic client with an offline
  cache. Only the client is ours to build.
- **Own protocol**: a catalogue file (albums, tracks, sizes, cover hashes) plus
  plain file serving over the VPN. Fully ours, more work.

## Open decision: LocalBeat's "no INTERNET permission" principle

Remote access needs the network, even if only to the user's own server.
Options:

1. Allow network access, but only ever to the user's configured server
   address (still no third parties; verifiable in the code, not in Android's
   permission screen).
2. A separate companion app does the syncing into the music folder, so
   LocalBeat itself keeps no INTERNET permission and stays provably offline.

Option 2 fits phase 2 (sync); option 1 is needed for phase 3 (on-demand
caching inside the player).
