---
status: accepted
---

# TV app: a separate React Native app for Vega OS, LAN only, original files

The TV app (decided 2026-11-26) is for the Fire TV Stick 4K Select, which runs
Vega OS, not Android. So it is its own codebase in React Native and TypeScript,
in `~/Development/LocalBeatTV`, rather than a flavour of this Kotlin app. It
reaches the music server directly on the home LAN, with no WireGuard; it joins
through the server's existing code-and-Approve join flow as a device of one
user. It streams each song's original file (FLAC for CD rips, MP3/AAC as they
are for older copies, never Opus) and keeps no music on the device.

## Considered options

- **An Android TV / Fire OS build from this repo** (shared Kotlin, Media3,
  Compose): it can't run on the 4K Select. It may come later for the older
  Fire OS sticks.
- **Grouping on the server** (a LocalBeat extension that returns albums,
  genres and decades): it would need a server change. Instead the TV ports the
  needed rules of `library/Library.kt` to TypeScript, and shared test cases (a
  JSON sample catalogue and the expected grouping) check both copies. Revisit
  if a third copy appears.
- **"Only FLAC" as hiding older copies:** rejected. Everything is shown;
  albums that aren't CD rips are marked.

## Consequences

- Two implementations of the grouping rules: change both together, with the
  shared test cases.
- The server must approve a device that has no tunnel key (LAN only).
- The TV app talks only to the music server, at a private address: no
  analytics, no internet. Amazon's own telemetry on the stick is beyond our
  control.
- Visualisation (step 2) may not be able to tap audio on Vega; if so, Studio
  writes per-song analysis files that the TV reads.
- Installation: sideload with Developer Mode over USB while building; move to
  Appstore Live App Testing if sideloaded apps prove unreliable.
