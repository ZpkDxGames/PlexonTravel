# Changelog

## 3.0.0 — Full Travel Suite
- Replaces the former global-only destination model with cached per-world spawn and hub routes while preserving 2.x global fallback data.
- Claims the standard `/spawn`, `/hub`, `/back`, `/warp`, `/warps`, TPA and RTP travel surface by default, with `/ptravel` retained for staging/recovery.
- Adds `/travel`, a compact InventoryHolder-based player help/navigation GUI.
- Adds first-class `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny` and `/tpcancel` request flows with expiry, quit cleanup and shared teleport safety.
- Adds first-class `/rtp` for configured worlds, defaulting to `Survival_World` with a 2,000–10,000 block annulus around world spawn.
- RTP uses uniform-area annulus sampling, bounded attempts, world-border validation, asynchronous chunk acquisition and no forced terrain generation by default.
- Standardizes approximately five-second player warmups with bossbar countdown, MiniMessage messages, particles, movement/damage cancellation and completion effects.
- Refactors the former monolithic runtime into dedicated destination, storage, teleport, TPA, RTP, menu and command services.
- Retains exact attempt ownership, final destination revalidation, exactly-once fee/refund behavior and successful-only back/cooldown commits.
- Keeps SQLite persistence compatible and adds a WAL checkpoint before administrative database backup.
- Extends the public API with world-aware `spawn(UUID)` and `hub(UUID)` lookup while retaining the original accessors for existing integrations.
- Updates PlexonCore integration to the stable `2.0.5` release and advertises per-world destination, TPA, RTP and MiniMessage capabilities in the module registry.
- Moves the stable rollback baseline to `v2.0.0`.

## 2.0.0 — Stable
- Promotes the accepted Phase 2/Phase 3 travel runtime line without changing production Java behavior during stable closure.
- Preserves authoritative attempt identity from destination resolution through async teleport terminal state and duplicate-request rejection.
- Preserves exactly-once fee/refund handling and successful-only `/back` history/cooldown commit.
- Preserves bounded safe-destination validation and final pre-teleport revalidation.
- Preserves stable warp IDs, display-name-only rename semantics, and revision-bound destructive confirmation.
- Preserves candidate-first configuration validation and failed-reload rollback behavior.
- Preserves cached-only PlaceholderAPI state and the existing SQLite schema/data compatibility.
- Keeps standard command takeover and migration execution operator-gated; stable GitHub publication does not imply live cutover approval.
- Replaces RC-specific CI/release automation with version-dynamic Build verification and exact-current-`main` stable publication, including downloaded-release SHA-256/provenance verification.

## 2.0.0-rc.1
- Phase 2 authoritative attempt ownership, safe destructive warp administration, stable IDs, validated reloads, cached PlaceholderAPI expansion, stronger distribution provenance.
- Runtime certification pending; stable 2.0.0 was not published during the RC campaign.

## 1.0.0
- Initial Core-native travel runtime.
- Spawn, hub, back and warp command domains.
- Shared warmup/cooldown/safe-teleport pipeline.
- SQLite persistence and warp GUI.
- Public API/events, diagnostics, migration scan/import and reproducible release automation.
