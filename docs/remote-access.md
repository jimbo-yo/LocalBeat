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

- Hardware: Raspberry Pi 5 or an old mini PC + a large USB disk. The user
  has a spare **Raspberry Pi 4**: enough for testing and very likely for up
  to ~10 users (2026-11-08 estimate; measure Opus encode speed on it first).
  Needs: the music on a USB 3 disk (not the SD card; a powered enclosure
  or hub for a hard drive), wired Ethernet, a heatsink or fan for sustained
  encoding. Home upload speed limits it more than the Pi does.
- OS: **Debian minimal** (or Alpine for truly tiny). Installed: OpenSSH,
  WireGuard, a dynamic-DNS updater, unattended security upgrades. Nothing else.
- Size guide: 1,000 CDs ~ 350 GB FLAC (an Opus copy of everything would be
  ~70 GB more; not needed, see phase 3).
- The laptop (LocalBeat Studio, formerly ripshelf / rip-cd.sh) pushes new rips to it over the LAN
  (rsync/SFTP over SSH, key-only login). FLAC is the master copy and the only one
  stored; Opus is made on demand (see phase 3).

### 2. Reaching home from anywhere: WireGuard, not open SSH

- Don't expose SSH (port 22) to the internet: bots attack it within hours.
- **WireGuard** on the server: one UDP port, silent to anyone without a key,
  so it doesn't show up to scanners at all.
- **Dynamic DNS** gives the server a stable name that follows the home IP.
- The phone runs the WireGuard app and reaches whatever its `AllowedIPs`
  cover: just the server (the plan below), or the whole home LAN if Phone
  Sync over KDE Connect/SFTP should work from anywhere too.
- **Check first:** whether the ISP uses **CGNAT** (no incoming connections
  possible). If so: IPv6, or a small cloud relay (a VPS running WireGuard that
  both ends connect out to). Fios normally gives a public IPv4, so probably
  not an issue here.
- Upload speed: the user's Fios is 200/200 Mbps, enough for hundreds of Opus
  streams or 100+ FLAC streams. Not a limit.

#### WireGuard in brief (for someone who knows IPsec)

A layer-3 VPN in the Linux kernel (since 5.6), UDP only, ~4,000 lines.

- **Fixed crypto** (Curve25519, ChaCha20-Poly1305, BLAKE2s): no negotiation;
  a broken primitive means a new protocol version.
- **Identity is a public key per peer**, like SSH: no certificates, no PKI,
  no users or MFA.
- **Cryptokey routing**: each peer's `AllowedIPs` is the routing table on
  the way out (these destinations are encrypted to this peer) and the ACL on
  the way in (a decrypted packet must come from an address in its sender's
  list). Tunnel source addresses can't be spoofed between peers.
- **No SAs to bring up**: a handshake happens when traffic flows, it rekeys
  every 2 minutes, and nothing is sent while idle.
- **Roaming built in**: a peer's endpoint follows its last authenticated
  packet, so Wi-Fi → LTE mid-song just works.
- **Silent**: it doesn't answer unauthenticated packets, so port scans see
  nothing.
- **No address assignment**: each peer's tunnel IP is static in the config.

#### The setup

```
Phone (anywhere) ──UDP 51820──> Fios router ──port forward──> Pi (wg0 10.8.0.1)
   10.8.0.2                                                    └─ music server on 10.8.0.1 only
```

Pi, `/etc/wireguard/wg0.conf` (start with `systemctl enable --now wg-quick@wg0`):

```ini
[Interface]
Address    = 10.8.0.1/24
ListenPort = 51820
PrivateKey = <pi private key>

[Peer]                      # the user's phone
PublicKey  = <phone public key>
AllowedIPs = 10.8.0.2/32

[Peer]                      # the son's phone
PublicKey  = <son's phone public key>
AllowedIPs = 10.8.0.3/32
```

Each phone (WireGuard Android app):

```ini
[Interface]
Address    = 10.8.0.2/32
PrivateKey = <phone private key>

[Peer]
PublicKey  = <pi public key>
Endpoint   = music.<domain>:51820
AllowedIPs = 10.8.0.1/32    # split tunnel: only the music server goes through it
```

Keys: `wg genkey | tee phone.key | wg pubkey > phone.pub`. Write each phone's
config on the Pi and load it by QR code: `qrencode -t ansiutf8 < phone.conf`.
Delete the config files and private keys from the Pi afterwards.

Choices:

- **Split tunnel, per app**: `AllowedIPs = 10.8.0.1/32`, and the Android app's
  "included applications" set to LocalBeat only, with Android's Always-on VPN.
  Everything else on the phone is untouched.
- **No `PersistentKeepalive`**: the phone always starts the conversation (which
  opens the NAT mapping) and the Pi only replies, so an idle tunnel sends
  nothing and costs no battery.
- **The music service binds to `10.8.0.1` only**, backed by an nftables rule.
  From the internet the only open port is UDP 51820, and it's silent.
- **Dynamic DNS with Route 53** (the user works with AWS): a cron job on the
  Pi updates an A record when the Fios IP changes, using an IAM user allowed to
  change only that record.
- **One key per person**: removing someone is deleting their `[Peer]` block
  (`wg syncconf` applies it without dropping the others).

For LocalBeat: it just talks to `http://10.8.0.1`; the WireGuard app does the
tunnel (embedding WireGuard would make LocalBeat a VPN app: not worth it).
Android blocks cleartext HTTP by default, so the Connected edition allows it
for the server address only (network security config), or uses TLS inside
the tunnel; allowing it is reasonable since WireGuard already encrypts.

For other people later: WireGuard plus a port forward is a real hurdle.
**Tailscale** is WireGuard with automatic key exchange and NAT traversal (no
port forward) but needs a Tailscale account (a third party); **Headscale** is
its self-hosted coordination server.

### 3. Browse everything, cache what's played

The phone can't hold the whole library (Pixel 9: ~21 GB free in 2026-11), so
the interesting mode is: the whole catalogue visible, albums downloaded on
demand, pinned albums kept offline, a cache size limit with least-recently-
played eviction.

Two ways to serve it:

**Decided (2026-11-09): Navidrome to start**, client side first, developed
against Navidrome on the laptop (LocalBeatServer `dev/navidrome/`; trial
findings in its CLAUDE.md). The server should also run as a background
"desktop server" on an existing Linux or Windows computer, not only on a Pi;
Navidrome runs on both.

**2026-11-09:** the Rust server is being built now, instead of setting up
Navidrome on the Pi. It **pre-converts the whole library to Opus in the
background** (the user's choice; it replaces "Opus on demand"), because on
the Pi converting on request would take about a tenth of a song's length,
so every first play would wait and couldn't seek. Details in LocalBeatServer's
CLAUDE.md.

Also decided for the server (2026-11-09; details in LocalBeatServer's
CLAUDE.md, "Installing and onboarding"): it runs as a normal background
service (systemd / Windows service), no containers; it **announces itself on
the home network** so LocalBeat can offer it in Settings without typing an
address (LocalBeat side to build: discovery plus the `localbeat://connect`
link); and a small **admin web page** later to add and remove people.

Later, our own **Rust** server for the same OpenSubsonic subset. LocalBeat
supports **both**: it uses only the standard calls both implement, and extras
only as named OpenSubsonic extensions it finds via `getOpenSubsonicExtensions`
(with a fallback when a server lacks them). Details: LocalBeatServer's
CLAUDE.md.

- **Navidrome** on the server (recommended starting point): a lightweight,
  self-hosted music server speaking the Subsonic/OpenSubsonic API; converts
  FLAC to Opus on the fly. LocalBeat becomes a Subsonic client with an offline
  cache. Only the client is ours to build.
- **Own protocol**: a catalogue file (albums, tracks, sizes, cover hashes) plus
  plain file serving over the VPN. Fully ours, more work.

### Phase 3 in LocalBeat: how it should work (from the user, 2026-11-08)

**Turning it on** (Connected edition only; see "Decided" below)
- Remote access is **off by default**. A checkbox in Settings ("Use remote
  server") turns it on, and only then do the remote settings appear: server
  address, login/token, and the storage settings below.

**Setting up a phone** (2026-11-08)

Two separate steps, two QR codes. The Pi stays headless: no monitor, no X.

1. **The tunnel: the official WireGuard app** (existing, free, open source,
   by the WireGuard project; Play Store and F-Droid). Nothing of ours.
   - On the Pi, make a peer for the phone and print its config as a QR code
     in the terminal: `qrencode -t ansiutf8 < phone.conf`, over SSH from the
     laptop. Later, a small admin page on the server ("Add a person") can show
     it instead.
   - In the WireGuard app: **+** → **Scan from QR code**. Optionally limit the
     tunnel to LocalBeat ("included applications") and turn on Android's
     **Always-on VPN** for it (Settings → Network → VPN).
   - This QR holds the phone's **private key**: show it once on a trusted
     screen and scan it on the spot; never screenshot, email or text it.
     Delete the config and key from the Pi afterwards. If it leaks: remove
     that peer, make a new one.
   - LocalBeat never sees WireGuard keys (handling them would make it a VPN
     app).
2. **LocalBeat: the server address and login.** Typed in, or a second QR
   holding a link such as `localbeat://connect?server=10.8.0.1:4533&token=…`:
   the phone's normal camera app reads QR codes and opens links, so it opens
   LocalBeat with the settings filled in. LocalBeat needs no camera permission
   and no scanner of its own. The token is a secret too (same care, less
   damage: it only works through the tunnel).

**Storage**
- Settings shows the phone's **free space** and lets the user set **how much
  to use for music** (a size limit). Downloaded music never goes over it.
- Optional **"Only use FLAC"** checkbox for audiophiles: download the lossless
  originals instead of Opus. Roughly 4-5x the space per album, so the size
  limit holds far fewer albums. (Note: LocalBeat's equaliser currently takes
  16-bit or float audio; 24-bit FLAC would need support added, or ExoPlayer
  set to float output.)

**Pinning and making space**
- Albums and songs (and probably artists and playlists) can be **pinned to
  the phone**: pinned music is never removed.
- Everything else downloaded is **eligible for removal**: when space is
  needed, the least recently played unpinned music goes first.
- If everything is pinned and there's no room, say so plainly; never remove
  pinned music silently.

**Browsing**
- The library shows **everything on the server**, with a clear mark on each
  album and song for what is on the phone right now: pinned, downloaded
  (removable), or server only. A "on this phone only" filter matters for
  places with no signal (the car).
- **Pressing play on a server-only song starts the transfer immediately**,
  making space first if needed. Streaming while it downloads would let it
  start within a second or two instead of waiting for the whole file; the
  next songs in the queue can download in the background.

**Where downloads live**
- In LocalBeat's own storage, not in `Music/`: `Music/` stays Phone Sync's
  (CDRipPipeline), and LocalBeat only removes files it downloaded itself.
  Albums put on by Phone Sync count as on the phone and are never removed.

**Opus made on demand, not stored** (from the user, 2026-11-08)
- The server keeps only FLAC. Computer players (existing or our own) play the
  FLAC directly. Opus is made when the phone asks for a song, with the same
  settings as `flac2phone.sh` today (160 kbps, tags and cover copied,
  ReplayGain stripped so the bogus +99.6 dB header gain can't come back).
- Encoding Opus is fast (a few seconds per song even on a small server), so
  this costs a short delay on the first request only.
- Keep a **size-limited cache of made Opus files on the server**, so repeat
  requests and seeking are instant. It can be deleted at any time; it's not a
  second copy of the library to manage.
- The catalogue only has the FLAC sizes, so the phone estimates Opus sizes
  from the duration (160 kbps) when planning space; close enough, and
  corrected once the real file arrives.
- Phone Sync would make its Opus copies the same way, so `~/Music/phone`
  could go too.

**Streaming while downloading**
- One download does both: Media3's `CacheDataSource` plays the bytes as they
  arrive and writes them to LocalBeat's storage at the same time.
- A skipped song leaves a partial download: finish it in the background or
  throw it away. Only complete files count as "on the phone".
- Seeking past what has arrived needs HTTP range requests, which work on a
  finished file but not on a song still being encoded: one more reason to
  encode the whole song first (a few seconds) and serve it from the cache.
- Losing signal mid-song stops playback when the buffer runs out; downloading
  the next songs in the queue ahead of time covers most of that.
- LocalBeat controls the cache's eviction itself (pins, size limit, least
  recently played), not Media3's default.

**Still to decide**
- Download over mobile data, or Wi-Fi only (FLAC on mobile data is expensive).
- What Android Auto shows: everything, or only what's on the phone.

## Decided (2026-11-08): two editions

The user chose option 3 below: the normal edition stays offline (no INTERNET
permission, no remote code); the **Connected edition** has a "Use remote
server" checkbox in Settings, off by default, which only reveals the server
settings and turns the remote features on. Unticked, the Connected edition
behaves like the normal one, though Android still lists network access.

## Background: LocalBeat's "no INTERNET permission" principle

Remote access needs the network, even if only to the user's own server.
Options:

1. Allow network access, but only ever to the user's configured server
   address (still no third parties; verifiable in the code, not in Android's
   permission screen).
2. A separate companion app does the syncing into the music folder, so
   LocalBeat itself keeps no INTERNET permission and stays provably offline.

**Android can't switch the INTERNET permission on and off.** It is granted at
install time to any app that declares it, and stock Android (Pixel included)
has no per-app switch to take it away. So a checkbox inside the app can only
stop LocalBeat's own code from using the network; Android's app info would
still list network access, and "provably offline" would be gone for everyone.
Option 1 with the user's checkbox has exactly that cost. The way to have the
checkbox and still keep a provably offline LocalBeat:

3. **Two editions from the same code** (recommended): the normal build has
   no INTERNET permission and no remote code in it at all; a "Connected"
   build adds both, with the checkbox off by default. Same package name and
   signing key, so installing one over the other keeps the library, playlists,
   queue and equaliser settings. Switching editions is a reinstall, not a tap.

Option 2 fits phase 2 (sync); option 1 or 3 is needed for phase 3 (on-demand
caching inside the player). Option 3 gives option 1's behaviour without
giving up the offline build.
