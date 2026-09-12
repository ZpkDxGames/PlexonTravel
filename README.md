# PlexonTravel

**PlexonTravel 3.1.0** is the PlexonCraft Core-native travel suite for Paper 26.2 / Java 25. It provides per-world spawn/hub routing, warps, `/back`, TPA, administrator-controlled RTP boundaries, and optional per-world void rescue on PlexonCore 2.0.5.

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
- `/rtp` — opens the existing safe random-teleport GUI
- `/rtp now` — begins an RTP search immediately
- `/travel` — compact player help/navigation GUI

Normal travel shares MiniMessage feedback, configurable warmup/cooldown, bossbar countdown, movement/damage cancellation, asynchronous destination resolution, final safety validation, `teleportAsync`, Vault transaction/refund semantics, and completion effects.

## Per-world spawn and hub

`/setspawn` and `/sethub` configure the player's current world by default. `/setspawn global` and `/sethub global` retain global compatibility fallbacks for upgraded databases.

Destination lookup is cached in memory. SQLite writes remain serialized off the server thread. The public `PlexonTravelAPI` preserves the original global accessors and world-aware `spawn(UUID)` / `hub(UUID)` lookups.

## RTP in 3.1

The player-facing commands remain unchanged:

```text
/rtp
/rtp now
```

PlexonTravel 3.1 adds immutable, cached **per-world RTP profiles** in `world-settings.yml`. Supported boundary modes are:

- `ANNULUS` — uniform-area radial sampling around world spawn or a configured X/Z center;
- `RECTANGLE` — uniform sampling inside administrator-defined X/Z bounds;
- `WORLD_BORDER` — samples the live Minecraft world border with configurable inward padding.

Every mode is intersected with and rechecked against the actual Minecraft world border. Searches remain bounded and cancellable, use asynchronous chunk acquisition, do not generate new terrain unless explicitly enabled, and pass the final location through the existing travel safety checks.

### 3.0.x compatibility

Existing `config.yml` RTP settings are not deleted or reinterpreted. If a world has no explicit 3.1 profile, the effective profile is inherited from the existing:

```yaml
rtp:
  enabled: true
  allowed-worlds:
    - Survival_World
  center:
    mode: WORLD_SPAWN
    x: 0.0
    z: 0.0
  min-radius: 2000.0
  max-radius: 10000.0
  max-attempts: 24
  generate-chunks: false
```

That inherited profile is equivalent to the 3.0.x annulus behavior. Creating or resetting a per-world profile does not rewrite those legacy/default keys.

### RTP administration

Canonical commands are console-compatible unless the operation explicitly requires the player's location:

```text
/traveladmin rtp status [world]
/traveladmin rtp enable <world>
/traveladmin rtp disable <world>
/traveladmin rtp mode <world> <annulus|rectangle|world-border>
/traveladmin rtp center <world> spawn
/traveladmin rtp center <world> here
/traveladmin rtp center <world> <x> <z>
/traveladmin rtp radius <world> <min> <max>
/traveladmin rtp bounds <world> <minX> <maxX> <minZ> <maxZ>
/traveladmin rtp pos1 [world]
/traveladmin rtp pos2 [world]
/traveladmin rtp padding <world> <blocks>
/traveladmin rtp attempts <world> <count>
/traveladmin rtp generate <world> <true|false>
/traveladmin rtp reset <world>
/traveladmin rtp test [world] [samples]
```

`pos1`/`pos2` deliberately normalize corner order and activate rectangle mode. `test` is bounded and non-teleporting; it reports sample validity, rejections, sampled X/Z ranges, and candidates blocked because terrain is ungenerated.

Permission: `plexontravel.admin.rtp` (child of `plexontravel.admin`).

## Void rescue

Void rescue is a first-class PlexonTravel safety feature and is **disabled by default**. Each world can independently define whether rescue is enabled, its trigger Y, creative-mode behavior, and destination.

Supported destination modes:

- `PLEXON_SPAWN`
- `PLEXON_HUB`
- `WORLD_SPAWN`
- `FIXED`

Administration:

```text
/traveladmin void status [world]
/traveladmin void enable <world>
/traveladmin void disable <world>
/traveladmin void threshold <world> <y>
/traveladmin void destination <world> <spawn|hub|worldspawn>
/traveladmin void destination <world> here
/traveladmin void creative <world> <true|false>
/traveladmin void reset <world>
/traveladmin void test [world]
/traveladmin void simulate <world> <y>
```

Automatic rescue has no warmup, fee, ordinary cooldown, or spawn/hub use-permission requirement. It uses a dedicated system-teleport path, resets fall distance after success, deduplicates in-flight attempts, backs off briefly after failures, ignores spectators, and does not rescue creative players unless that world opts in.

Most importantly, rescue is marked as an internal teleport so the dangerous void coordinate is **not** captured as the player's new `/back` location. A useful existing back entry is preserved. A player who dies while rescue is in flight is cleaned up without being unexpectedly teleported afterward.

Permissions:

- `plexontravel.admin.void` — administer void rescue;
- `plexontravel.voidrescue.bypass` — bypass automatic rescue, default `op`.

## Transactional world settings

`world-settings.yml` is separate from `config.yml` so administrator mutations do not destroy comments in the main configuration. It is keyed by durable world UUID and stores the last-known human-readable world name.

Runtime policy is parsed into immutable in-memory objects. Movement and RTP hot paths do not parse YAML, access SQLite, or perform filesystem I/O. Admin mutations validate an in-memory candidate, write through a temporary file with atomic replacement where supported, and only then activate the new snapshot. `/traveladmin reload` validates both main and world settings before activation.

## TPA

TPA requests are in-memory, bounded by an expiry timer, cleaned on quit, and share the same warmup/cancellation/effects engine as spawn, hub, warps and RTP. Multiple incoming requests require an explicit player name when accepting or denying.

## Performance and reliability

- cached immutable per-world RTP/void policies;
- no YAML, disk, or SQL work in movement listeners;
- one shared five-tick warmup ticker rather than one repeating task per teleport;
- asynchronous chunk acquisition and `teleportAsync`;
- no synchronous terrain generation for RTP;
- bounded RTP attempts and rescue-operation deduplication;
- normal safety resolution does not generate terrain by default;
- exact per-player attempt identity across travel resolution and completion;
- successful ordinary travel commits back history/cooldown once; failed charged travel refunds once;
- cached destinations/warps/back state with serialized SQLite writes and WAL mode;
- malformed persisted rows are isolated and logged rather than disabling the entire plugin;
- InventoryHolder-based GUIs with Adventure titles and centralized click/drag cancellation;
- startup failures identify their exact phase and remain visible as `FAILED` in PlexonCore diagnostics.

## Command cutover and migration

3.x remains the active command owner by default with `commands.takeover-enabled: true`. `/ptravel` remains the namespaced staging/recovery surface.

`/traveladmin migrate scan` is read-only. Essentials warp import remains gated by `migration.allow-execute: false`; source plugin files are never modified. Older partial `config.yml` files may omit keys added by later versions: missing values inherit embedded defaults while explicitly invalid configured values are rejected.

### Upgrade: 3.0.2 → 3.1.0

1. Back up `plugins/PlexonTravel/` and the server before replacing the JAR.
2. Replace `PlexonTravel-3.0.2.jar` with the verified `PlexonTravel-3.1.0.jar`.
3. Keep the existing `config.yml` and `travel.db`.
4. On first startup, `world-settings.yml` is created if absent; no per-world RTP profile is required.
5. Existing RTP behavior continues through legacy inheritance until an administrator explicitly creates a 3.1 profile.
6. Void rescue remains disabled until enabled per world.
7. Use `/traveladmin diagnostics`, `/traveladmin rtp status <world>`, and `/traveladmin void status <world>` to verify effective state.

Rollback remains non-destructive: 3.0.2 ignores `world-settings.yml`, and 3.1.0 does not require a SQLite format migration.

## Build and release verification

CI provisions the exact released `PlexonCore-2.0.5.jar` with a pinned SHA-256, runs the Maven suite, verifies the installable distribution, runs the real Paper startup smoke, and records checksum/provenance/test evidence. Stable publication rebuilds from exact final `main`, publishes the JAR plus `SHA256SUMS.txt`, `TEST_SUMMARY.txt`, `PROVENANCE.txt`, and `PAPER_STARTUP_SMOKE.txt`, then downloads and verifies the published assets again.

Stable output:

```text
target/PlexonTravel-3.1.0.jar
```

Rollback baseline: `v3.0.2` / `66c8e1baff74bc307162d0164c5e3e432297d651`.
