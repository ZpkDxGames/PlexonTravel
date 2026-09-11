# Travel Source Audit

Stable repository/source/release certification does not prove or authorize live PlexonCraft command takeover. The following audit remains an **operational deployment follow-up** and does not block the verified GitHub stable artifact.

Before standard commands become PRIMARY on PlexonCraft:

1. Run `/traveladmin migrate scan` with Essentials, WorldSpawn and Multiverse-Core still installed where applicable.
2. Record the current owner/behavior of `/spawn`, `/hub`, `/back`, `/warp`, `/warps` and respawn.
3. Confirm the intended spawn/hub locations and unresolved worlds.
4. Keep `migration.claim-standard-commands: false` during staging; use `/ptravel` for behavior tests.
5. Back up `travel.db` before any import and leave source provider files untouched.
6. Enable `migration.allow-execute` only for the reviewed staging/production migration execution.
7. Once source parity, rollback and staging tests pass, disable only the overlapping old-provider commands and set `migration.claim-standard-commands: true`.

The stable codebase intentionally does not fabricate this production evidence. Release provenance records standard command takeover and migration execution as operator-gated until these steps are completed.
