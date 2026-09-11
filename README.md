# PlexonTravel

**PlexonTravel 2.0.0** is the stable focused Core-native travel module for PlexonCraft.

Repository/source/release stability is separate from live production command cutover. The published stable artifact is safe to build and verify without claiming that PlexonCraft has already transferred `/spawn`, `/hub`, `/back`, `/warp`, or `/warps` ownership. Runtime certification and command-owner migration may remain `NOT_EXECUTED` / operator-gated in release provenance.

## Platform

- Paper `26.2.build.121-stable`
- Java `25` / class major 69
- PlexonCore `2.0.4` / Core API 2.0
- SQLite JDBC bundled in the plugin JAR
- PlaceholderAPI and Vault remain provided/optional integrations

## Product scope

PlexonTravel owns the focused spawn, hub, back and warp product: `/spawn`, `/hub`, `/back`, `/warp`, `/warps`, `/setwarp`, `/delwarp`, safe teleport validation, warmups, cooldowns, optional Vault/TheosisEconomy fees, bounded back history, warp management/GUI, migration tooling, public API/events, PlaceholderAPI state and configurable respawn behavior.

It intentionally does **not** absorb homes, economy, ranks, kits, moderation, general utility commands, TPA, BetterRTP, or AdvancedPortals.

## 2.0.0 reliability boundary

Stable 2.0.0 preserves the accepted Phase 2/Phase 3 runtime line:

- per-player attempt ownership begins before destination resolution and remains authoritative through warmup, final validation, fee handling and async teleport completion;
- duplicate warmup/in-flight requests are rejected instead of replacing an active attempt;
- executing attempts cannot be cancelled out from under their terminal async callback;
- a charged failed teleport uses one compensation/refund path, while successful travel commits `/back` history and cooldown exactly once;
- safe teleport resolution validates finite destinations, world availability, border/body/support constraints and bounded nearby search, then revalidates before charge/teleport;
- warp IDs remain stable when display names are changed;
- warp deletion uses actor/action/resource/revision-bound, expiring, one-shot confirmation;
- malformed candidate configuration is rejected before Bukkit `reloadConfig()`, preserving the previous known-good runtime;
- PlaceholderAPI expansion reads cached/API state only and does not query disk or SQLite;
- existing SQLite destination/warp/back and migration metadata remain compatible with 1.0.1.

## Staging-safe rollout

The default remains deliberately conservative:

```yaml
migration:
  claim-standard-commands: false
  allow-execute: false
```

Use `/ptravel spawn`, `/ptravel hub`, `/ptravel back`, `/ptravel warp <name>` and `/ptravel warps` while auditing existing command owners. `/traveladmin migrate scan` is read-only; migration execution is separately gated.

Before enabling standard command takeover on PlexonCraft, inventory command aliases/owners, GUIPlus/MyCommand/commands.yml consumers, permissions, legacy warp data and placeholder consumers. BetterRTP and AdvancedPortals remain outside this cutover.

See `docs/STAGING.md`, `docs/TRAVEL_SOURCE_AUDIT.md`, and `docs/PHASE3_CONSOLIDATION_AUDIT.md` for the operational migration checklist.

## Build and release verification

CI provisions the exact released `PlexonCore-2.0.4.jar` and verifies its pinned SHA-256, runs the full Maven test/package suite, requires a non-empty all-green test result, verifies Java 25/class-major 69, checks required plugin resources/API/events/SQLite runtime, and rejects shaded Paper/Bukkit/Adventure/Core/PlaceholderAPI/Vault classes.

Stable output:

```text
target/PlexonTravel-2.0.0.jar
```

The stable publisher runs only from `release/stable`, requires that commit to equal current `main`, proves accepted `v2.0.0-rc.1` ancestry, rebuilds exact source, publishes JAR + checksum + test summary + provenance, downloads the published release again, and verifies its SHA-256 and exact source commit before the workflow can finish green.

Rollback baseline: `v1.0.1` / `772188d55c694deea0bc5ae0bdce0898513499e5`.
