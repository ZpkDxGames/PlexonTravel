# PlexonTravel

**PlexonTravel 3.0.0** is the PlexonCraft Core-native travel suite for Paper 26.2 / Java 25. It replaces the legacy Essentials warp surface and WorldSpawn-style spawn/hub routing with one cached, per-world travel runtime built around PlexonCore 2.0.5.

## Platform

- Paper `26.2.build.121-stable`
- Java `25` / class major 69
- PlexonCore `2.0.5` / Core API 2.0
- SQLite JDBC bundled in the plugin JAR
- Optional PlaceholderAPI and Vault integrations
- Existing PlexonTravel 2.x SQLite destinations, warps and back history remain readable

## Player travel surface

PlexonTravel now owns:

- `/spawn` — current-world spawn route
- `/hub` — current-world hub route with configurable fallback
- `/back` — previous meaningful location
- `/warp [name]` and `/warps` — cached warp travel and browser
- `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpcancel`
- `/rtp` — safe random teleport with a player GUI
- `/travel` — compact player help/navigation GUI

Normal travel shares one presentation and safety pipeline: MiniMessage messages, approximately five-second configurable warmup, bossbar countdown, warmup particles, movement/damage cancellation, final destination revalidation, asynchronous teleport and completion particles/sound.

## Per-world spawn and hub

`/setspawn` and `/sethub` now configure the player's current world by default. `/setspawn global` and `/sethub global` retain a global compatibility fallback for upgraded 2.x databases.

Destination lookup is cached in memory. SQLite writes remain serialized off the server thread. The public `PlexonTravelAPI` keeps the original global `spawn()` / `hub()` accessors and adds world-aware `spawn(UUID)` / `hub(UUID)` lookups for Plexon-family integrations.

Default hub mode is `PER_WORLD`. A missing world hub can fall back to the global hub, that world's spawn, or nothing.

## RTP

RTP is enabled by default only for `Survival_World`:

```yaml
rtp:
  allowed-worlds:
    - Survival_World
  center:
    mode: WORLD_SPAWN
  min-radius: 2000.0
  max-radius: 10000.0
  max-attempts: 24
  generate-chunks: false
```

Candidates are sampled uniformly by area inside the configured annulus, checked against the world border, and resolved through bounded asynchronous chunk lookup. With `generate-chunks: false`, RTP skips terrain that has not already been generated, avoiding surprise world generation under player load. This is the recommended setting for a Chunky-pregenerated Survival world.

## TPA

TPA requests are in-memory, bounded by an expiry timer, cleaned on quit, and share the same warmup/cancellation/effects engine as spawn, hub, warps and RTP. Multiple incoming requests require an explicit player name when accepting or denying.

## Performance and reliability

- one shared five-tick warmup ticker rather than one repeating task per teleport;
- asynchronous chunk acquisition and `teleportAsync` for destination travel;
- bounded safe-location and RTP searches;
- exact per-player attempt identity across resolution, warmup, fee handling and terminal completion;
- successful travel commits back history/cooldown once; failed charged travel refunds once;
- cached destinations/warps/back state with serialized SQLite writes and WAL mode;
- WAL checkpoint before administrative database backup;
- InventoryHolder-based GUIs with centralized click/drag cancellation;
- PlaceholderAPI reads cached/API state only.

## Command cutover

3.0 is intended to be the active command owner, so new installs default to:

```yaml
commands:
  takeover-enabled: true
```

`plugin.yml` declares load-before relationships for Essentials, WorldSpawn and BetterRTP to make migration safer when legacy plugins are temporarily present. For a clean production cutover, remove or disable the overlapping legacy commands/modules after verification rather than relying indefinitely on command-registration order.

`/ptravel` remains available as a namespaced recovery/staging surface. Set `commands.takeover-enabled: false` if overlapping providers must remain active during a staged migration.

## Migration

`/traveladmin migrate scan` is read-only. Essentials warp import remains separately gated by:

```yaml
migration:
  allow-execute: false
```

Run a database backup and review the scan before temporarily enabling migration execution. Source plugin files are never modified. WorldSpawn formats vary, so per-world spawn/hub cutover is deliberately explicit with `/setspawn` and `/sethub` rather than guessing ambiguous legacy coordinates.

## Build and release verification

CI provisions the exact released `PlexonCore-2.0.5.jar` with a pinned SHA-256, runs the Maven test/package suite, verifies the installable distribution, records checksum/provenance/test evidence, and rejects a release unless all tests pass without failures, errors or skips.

Stable output:

```text
target/PlexonTravel-3.0.0.jar
```

The stable publisher runs only from `release/stable`, requires that commit to equal current `main`, rebuilds and retests exact source, publishes the JAR plus `SHA256SUMS.txt`, `TEST_SUMMARY.txt` and `PROVENANCE.txt`, then downloads and verifies the published assets again.

Rollback baseline: `v2.0.0` / `2d29d559e08a98f1e3577fbd3a574e34154c4b71`.
