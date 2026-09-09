# PlexonTravel

PlexonTravel is the focused Core-native travel module for PlexonCraft.

## Platform

- Paper `26.2.build.121-stable`
- Java `25`
- PlexonCore `2.0.4` / Core API `2.0`
- Maven reproducible build

## Scope

PlexonTravel owns `/spawn`, `/hub`, `/back`, `/warp`, `/warps`, safe teleport validation, warmups, cooldowns, optional Vault fees, bounded teleport history, warp management/GUI, migration tooling, public API/events and configurable respawn behavior.

It intentionally does **not** own homes, economy, ranks, kits, moderation or general utility commands.

## Staging-safe rollout

Standard travel commands are registered but runtime takeover defaults to disabled through `migration.claim-standard-commands: false`. Use `/ptravel spawn`, `/ptravel hub`, `/ptravel back`, `/ptravel warp <name>` and `/ptravel warps` while auditing the existing Essentials/WorldSpawn command owners. `/traveladmin migrate scan` is read-only; migration execution is separately gated.

## Build

CI provisions the exact released `PlexonCore-2.0.4.jar`, verifies its pinned SHA-256, runs `mvn clean verify`, verifies the shaded distribution, computes `SHA256SUMS.txt`, and uploads the installable JAR.

The stable maintenance artifact is `PlexonTravel-1.0.1.jar`.

Runtime command takeover, observed Core READY state, restart/soak and Spark validation remain deployment gates and are not claimed by source/CI certification.

See `docs/STAGING.md` and `docs/TRAVEL_SOURCE_AUDIT.md` before enabling standard-command takeover.
