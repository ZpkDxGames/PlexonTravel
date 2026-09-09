# Recovery

PlexonTravel migration is non-destructive. Keep old provider configuration/data untouched until primary-mode validation completes.

Rollback procedure:
1. Stop the server.
2. Disable/remove PlexonTravel or set standard command takeover false.
3. Restore the pre-migration command ownership/provider configuration.
4. Restore a `travel.db` backup only if PlexonTravel state itself must be rolled back.
5. Start the server and verify the old `/spawn`, `/hub`, `/back` and `/warp` paths.

No Essentials/WorldSpawn source restore is required because migration never edits source files.
