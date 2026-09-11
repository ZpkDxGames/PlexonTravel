# PlexonTravel 2.0.0 Phase 2 Execution Specification

## Baseline and release boundary

- Repository: `ZpkDxGames/PlexonTravel`
- Rollback release: `v1.0.1`
- Rollback commit: `772188d55c694deea0bc5ae0bdce0898513499e5`
- Phase 2 branch: `phase2/2.0.0-premium-travel`
- Candidate: `v2.0.0-rc.1`
- Stable publication: forbidden until PlexonCraft runtime certification
- Phase 2 PR: must remain open, draft, and unmerged

## Repository audit

The v1.0.1 baseline is already a focused Core-native travel product rather than an Essentials clone. It owns `/spawn`, `/hub`, `/back`, `/warp`, `/warps`, destination administration, a staging-safe `/ptravel` namespace, SQLite/WAL persistence, one shared warmup ticker, bounded safe-destination search with Paper async chunk preparation, Vault/TheosisEconomy fee/refund behavior, immutable public API views, public travel/warp events, and PlexonCore module lifecycle registration.

Phase 2 must preserve those mature boundaries. No unrelated utility commands are in scope.

### Defects/gaps found at the baseline

1. Attempt ownership begins only after safe resolution. Two requests can race before `pending` is installed and the later request replaces an earlier warmup instead of rejecting duplicates.
2. `cancel()` can remove an executing attempt. If a fee was already withdrawn and the async teleport later resolves, the stale callback sees that ownership is gone and can return without the required refund/commit path.
3. `/delwarp` is immediately destructive and has no actor/action/warp/revision/expiry/one-shot confirmation.
4. `/renamewarp` changes the persistent warp ID. Phase 2 requires stable IDs; rename must update display metadata without changing identity.
5. `/traveladmin reload` calls Bukkit `reloadConfig()` before any candidate validation and therefore cannot retain the last known-good configuration on malformed input.
6. The warp browser hides locked warps and does not present fee/permission context clearly enough for travel discovery.
7. PlaceholderAPI is declared as a soft dependency but v1.0.1 provides no expansion.
8. CI/release output is limited to the JAR and checksum; Phase 2 requires test summary and provenance assets and stronger distribution checks.

## SemVer decision

Target `2.0.0`.

Rationale: Phase 2 intentionally changes externally visible administration semantics (`renamewarp` becomes display-name mutation with stable ID), adds a supported PlaceholderAPI contract, strengthens reload behavior, and changes request concurrency semantics from replacement to duplicate rejection. Those product/API behavior changes justify a major release boundary while preserving the core command set.

## Implementation contract

### Authoritative travel attempts

Create attempt identity before destination resolution. A player may own at most one active attempt across resolving, warmup, final revalidation, fee charge, async teleport, and terminal commit/compensation. Keep the identity until the async teleport reaches a terminal state. Reject duplicates during warmup and in-flight execution. Every async callback must verify attempt identity plus runtime epoch before mutating history, cooldowns, accounting, or UI.

Executing attempts are not removed by movement/damage replacement paths after the fee/teleport transaction has started. Terminal success commits back history and cooldown once. Terminal failure refunds once when a charge occurred.

### Safe teleport

Preserve the unified safe resolver and async chunk preparation. Validate finite destinations, world availability, world border, two-block body clearance, solid support, and bounded nearby search. Revalidate immediately before charge/teleport. Never silently substitute an unrelated world.

### Spawn/hub/back

Preserve command compatibility and distinct hub modes. Only successful committed travel updates `/back`; failed/cancelled attempts never overwrite the previous valid entry. Continue one-level A↔B behavior rather than introducing an unbounded stack.

### Warps

Keep normalized stable IDs as persistence identity. `renamewarp <id> <display name...>` changes only display metadata and increments revision. Deletion requires a second matching invocation within a bounded confirmation window and is invalidated by revision/state changes. The browser may show locked entries, description/context available from current schema, fee preview, and explicit interaction state without using GUI slot as identity.

### Economy

Vault/TheosisEconomy remains authoritative. No Plexon balance system. Charge at most once after final destination validation; refund at most once on defined terminal failure. No charge on warmup cancellation or insufficient balance.

### Configuration/reload

Parse a candidate YAML file independently, validate all Phase 2 scalar/enumerated policy fields and finite numeric values, then activate it with `reloadConfig()` only after validation succeeds. Failed validation retains the previous runtime configuration and increments no runtime epoch.

### PlaceholderAPI

When PlaceholderAPI is installed, register a small non-persistent expansion exposing cached/runtime-only values. Placeholder lookup must not perform disk or database queries.

### Persistence/migration

Preserve the existing SQLite schema for destinations, warps, back locations, and migration metadata. No destructive schema migration is required for 1.0.1 → 2.0.0. Existing IDs and state remain valid. Essentials import remains read-only-first and explicitly gated.

### Performance

Idle cost remains one shared coordinator tick and event fast-gates. No task per player/teleport, no movement/damage database queries, no per-tick destination reconstruction beyond active attempts, and no synchronous persistence writes from movement paths.

## Automated certification

Exact-head CI must run Java 25 and verify:

- PlexonCore 2.0.4 exact provenance
- source/model regression tests
- Phase 2 attempt ownership contracts
- stable warp identity and destructive confirmation contracts
- configuration validation/rollback contracts
- PlaceholderAPI cached-only contract
- `PlexonTravel-2.0.0-rc.1.jar`
- Java class major 69
- PlexonCore, PlaceholderAPI, Paper/Bukkit/Adventure, and Vault are not shaded
- SQLite is bundled as the required persistence runtime
- required resources exist
- `SHA256SUMS.txt`
- `TEST_SUMMARY.txt`
- `PROVENANCE.txt`

## Closure

After implementation: freeze exact branch SHA, require source and draft-PR CI green, keep the PR open/draft/unmerged, tag that exact SHA `v2.0.0-rc.1`, rebuild/test the tag, publish a GitHub prerelease with JAR + checksum + test summary + provenance, independently verify release/tag/assets/digest/PR state, and stop work at `RC RELEASED / RUNTIME PENDING`.

Runtime certification remains not executed without actual PlexonCraft runtime access. Remaining live gates include representative v1.0.1 migration, spawn/hub/back, warp browser/admin, safe destination behavior, cancellation paths, duplicate/in-flight rejection, Vault/Theosis accounting, persistence/restart, failed reload rollback, PlaceholderAPI/API/events/Core interoperability, Spark/MSPT comparison, and a >=30-minute soak with zero HIGH/CRITICAL defects.
