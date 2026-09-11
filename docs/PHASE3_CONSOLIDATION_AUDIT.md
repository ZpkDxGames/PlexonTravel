# Phase 3 — Travel Consolidation Audit

Status: AUDIT CHECKPOINT — NO STABLE PROMOTION

Baseline: `phase2/2.0.0-premium-travel` @ `b811091418ba630caeaddfa54f6bf67cdcb5294a`

## Canonical target ownership

PlexonTravel is the intended canonical owner of:

- `/spawn`
- `/hub`
- `/back`
- `/warp`
- `/warps`
- `/setwarp`
- `/delwarp`

The current Phase 2 candidate already declares these commands plus staging/admin namespaces.

TPA remains **DEFERRED**. No available PlexonCraft evidence establishes that `/tpa`, `/tpaccept`, `/tpdeny` or `/tpahere` are currently required. Do not add them for Essentials parity.

RTP remains outside PlexonTravel. BetterRTP is active production infrastructure and must remain installed unless a separate, deliberate RTP replacement project is approved and runtime-certified.

AdvancedPortals also remains outside this consolidation scope.

## Confirmed command collision

The production GUIPlus survival-warp menu declares `commandAlias: warps`. Current startup evidence reports `warps` as already registered and GUIPlus falls back to `/guiplus:warps`.

Canonical ownership decision:

`/warps` -> PlexonTravel

The GUIPlus menu buttons currently dispatch commands shaped like:

`warp <destination> %player_name%`

PlexonTravel resolves the first argument as the warp identifier, so the existing button command shape is compatible with the current Travel parser. The menu may therefore remain as a UI during migration if desired, but GUIPlus should not retain canonical ownership of `/warps` once the Travel cutover is approved.

Do not remove GUIPlus itself as part of this repository change.

## Standard-command cutover gate

Keep `migration.claim-standard-commands: false` while production ownership is being audited.

Before switching it on, an operator must capture:

- `/help` ownership for each target command;
- Paper command map / namespaced registrations;
- MyCommand aliases;
- GUIPlus aliases;
- any `commands.yml` aliases;
- any portal/scripts that dispatch those commands.

After aliases are cleaned and staging behavior passes, Travel may become PRIMARY for the standard command names.

## Existing migration support

PlexonTravel already contains a read-only Essentials warp importer and migration scan/plan/execute/status workflow. Execution is separately gated by `migration.allow-execute`.

Do not enable migration execution in production until:

1. source and target data are backed up;
2. scan/plan output is reviewed;
3. duplicate names are reviewed;
4. unresolved/invalid world destinations are quarantined or explicitly mapped;
5. repeated-import idempotency is tested;
6. rollback files are retained.

Historical Essentials source data is never to be deleted by the importer.

## Permission migration

Only migrate nodes found in the live LuckPerms export.

| Historical/source node | Target |
|---|---|
| `essentials.spawn` | `plexontravel.spawn` |
| `essentials.back` | `plexontravel.back` |
| `essentials.warp` | `plexontravel.warp` |
| Essentials warp-list permission | `plexontravel.warps` after exact source-node confirmation |
| historical teleport warmup/cooldown bypasses | map to `plexontravel.warmup.bypass` / `plexontravel.cooldown.bypass` only after semantics are compared |

Do not blindly translate `essentials.warps.*` into a wildcard. The current PlexonTravel manifest exposes generic `plexontravel.warp` and `plexontravel.warps`; any per-warp access policy must be confirmed from runtime/config before migration.

## Placeholder/consumer migration

Before removal of the stale Essentials PlaceholderAPI expansion, scan all current scoreboard/chat/menu/Skript/MyCommand consumers for `%essentials_*%` and replace only those whose semantics match a Plexon placeholder.

PlexonTravel already registers PlaceholderAPI support at runtime, but exact placeholder-by-placeholder substitutions must be derived from the current consumer list rather than guessed.

## Production cutover sequence

1. backup Travel data/config and relevant GUIPlus/MyCommand configs;
2. export LuckPerms;
3. inventory current command owners and placeholder consumers;
4. run migration scan/plan if legacy warp data exists;
5. migrate and validate only with operator approval;
6. remove/rename GUIPlus `warps` alias while preserving the GUI if desired;
7. enable Travel standard command claiming;
8. test spawn/hub/back/warp/warps/setwarp/delwarp, warmup cancellation, exactly-once fee/refund, world restrictions and restart persistence;
9. retain BetterRTP and AdvancedPortals.

## Rollback

- set Travel standard command claiming back to false;
- restore backed-up GUIPlus/MyCommand aliases;
- restore target Travel data snapshot if target persistence was modified;
- source legacy data remains untouched;
- BetterRTP and AdvancedPortals remain unaffected.

No production changes are performed by this Phase 3 audit commit.
