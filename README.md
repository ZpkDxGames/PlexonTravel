# PlexonTravel

**PlexonTravel 3.0.1** is the PlexonCraft Core-native travel suite for Paper 26.2 / Java 25. It replaces the legacy Essentials warp surface and WorldSpawn-style spawn/hub routing with one cached, per-world travel runtime built around PlexonCore 2.0.5.

## Platform

- Paper `26.2.build.121-stable`
- Java `25` / class major 69
- PlexonCore `2.0.5` / Core API 2.0
- SQLite JDBC bundled in the plugin JAR
- Optional PlaceholderAPI and Vault integrations
- Existing PlexonTravel 1.x/2.x/3.0 SQLite destinations, warps and back history remain readable

## Player travel surface

PlexonTravel owns:

- `/spawn` — current-world spawn route
- `/hub` — current-world hub route with configurable fallback
- `/back` — previous meaningful location
- `/warp [name]` and `/warps` — cached warp travel and browser
- `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpcancel`
- `/rtp` — safe random teleport with a player GUI
- `/travel` — compact player help/navigation GUI

Normal travel shares one presentation and safety pipeline: MiniMessage messages, approximately five-second configurable warmup, bossbar countdown, warmup particles, movement/damage cancellation, final destination revalidation, asynchronous teleport and completion particles/sound.

## Per-world spawn and hub

`/setspawn` and `/sethub` configure the player's current world by default. `/setspawn global` and `/sethub global` retain global compatibility fallbacks for upgraded databases.

Destination lookup is cached in memory. SQLite writes remain serialized off the server thread. The public `PlexonTravelAPI` keeps the original global `spawn()` / `hub()` accessors and adds world-aware `spawn(UUID)` / `hub(UUID)` lookups for Plexon-family integrations.

Default hub mode is `PER_WORLD`. A missing world hub can fall back to the global hub, that world's spawn, or nothing. Legacy 2.x `SEPARATE` mode is interpreted as per-world lookup with the configured compatibility fallback.

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

Candidates are sampled uniformly by area inside the configured annulus, checked against the world border, and resolved through bounded asynchronous chunk lookup. With `generate-chunks: false`, RTP skips terrain that has not already been generated, avoiding surprise world generation under player load. Searches are invalidated when the player quits, dies, changes worlds, or the plugin shuts down.

## TPA

TPA requests are in-memory, bounded by an expiry timer, cleaned on quit, and share the same warmup/cancellation/effects engine as spawn, hub, warps and RTP. Multiple incoming requests require an explicit player name when accepting or denying. Accepted requests report a terminal failure if the shared travel engine cannot actually complete the teleport.

## Performance and reliability

- one shared five-tick warmup ticker rather than one repeating task per teleport;
- asynchronous chunk acquisition and `teleportAsync` for destination travel;
- normal safety resolution does not generate terrain by default;
- nearby safety checks never synchronously inspect unloaded chunks;
- bounded safe-location and RTP searches;
- exact per-player attempt identity across resolution, warmup, fee handling and terminal completion;
- successful travel commits back history/cooldown once; failed charged travel refunds once;
- expired cooldown state is pruned periodically;
- cached destinations/warps/back state with serialized SQLite writes and WAL mode;
- malformed individual persisted rows are isolated and logged rather than disabling the entire plugin;
- WAL checkpoint before administrative database backup;
- InventoryHolder-based GUIs with Adventure titles and centralized click/drag cancellation;
- MiniMessage customization fails safely to literal text if malformed;
- PlaceholderAPI reads cached/API state only and uses an O(1) cached warp count;
- startup failures identify their exact phase and remain visible as `FAILED` in PlexonCore diagnostics.

## Command cutover

3.x is intended to be the active command owner, so new installs default to:

```yaml
commands:
  takeover-enabled: true
```

`plugin.yml` declares load-before relationships for Essentials, WorldSpawn and BetterRTP to make migration safer when legacy plugins are temporarily present. For a clean production cutover, remove or disable overlapping legacy commands/modules after verification rather than relying indefinitely on command-registration order.

`/ptravel` remains available as a namespaced recovery/staging surface. Service-layer permission checks also apply to `/ptravel` and GUI routes, so those surfaces cannot bypass individual travel permissions.

## Migration

`/traveladmin migrate scan` is read-only. Essentials warp import remains separately gated by:

```yaml
migration:
  allow-execute: false
```

Run a database backup and review the scan before temporarily enabling migration execution. Source plugin files are never modified. WorldSpawn formats vary, so per-world spawn/hub cutover is deliberately explicit with `/setspawn` and `/sethub` rather than guessing ambiguous legacy coordinates.

Older partial `config.yml` files may omit keys added by newer PlexonTravel versions; missing values inherit the embedded defaults while explicitly invalid configured values are rejected.

## Build and release verification

CI provisions the exact released `PlexonCore-2.0.5.jar` with a pinned SHA-256, runs the Maven test/package suite, verifies the installable distribution, records checksum/provenance/test evidence, and rejects a release unless all tests pass without failures, errors or skips.

Stable output:

```text
target/PlexonTravel-3.0.1.jar
```

The stable publisher runs only from `release/stable`, requires that commit to equal current `main`, rebuilds and retests exact source, publishes the JAR plus `SHA256SUMS.txt`, `TEST_SUMMARY.txt` and `PROVENANCE.txt`, then downloads and verifies the published assets again.

Rollback baseline: `v3.0.0` / `1bf92f4ef93ba150e61df524c78799703b5d4ce4`.
