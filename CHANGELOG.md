# Changelog

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
