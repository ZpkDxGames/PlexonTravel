# Travel Source Audit

This repository cannot prove command ownership on the live PlexonCraft server. The following audit must be run before standard commands become PRIMARY:

1. `/traveladmin migrate scan` with Essentials, WorldSpawn and Multiverse-Core still installed.
2. Record the current owner/behavior of `/spawn`, `/hub`, `/back`, `/warp`, `/warps` and respawn.
3. Confirm the intended spawn/hub locations and unresolved worlds.
4. Keep `migration.claim-standard-commands: false` during staging; use `/ptravel` for behavior tests.
5. Back up `travel.db` before any import and leave source provider files untouched.
6. Enable `migration.allow-execute` only for the staging migration execution.
7. Once source parity, rollback and staging tests pass, disable only the overlapping old-provider commands and set `migration.claim-standard-commands: true`.

The codebase intentionally does not fabricate this production evidence.
