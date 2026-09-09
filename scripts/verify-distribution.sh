#!/usr/bin/env bash
set -euo pipefail
JAR="${1:-target/PlexonTravel-1.0.0.jar}"
[[ -f "$JAR" ]] || { echo "missing jar: $JAR" >&2; exit 1; }
LIST="$(mktemp)"
trap 'rm -f "$LIST"' EXIT
jar tf "$JAR" > "$LIST"
grep -qx 'plugin.yml' "$LIST"
grep -qx 'com/plexon/travel/PlexonTravel.class' "$LIST"
grep -qx 'com/plexon/travel/api/PlexonTravelAPI.class' "$LIST"
grep -qx 'com/plexon/travel/event/PlexonTravelStartEvent.class' "$LIST"
grep -qx 'org/sqlite/JDBC.class' "$LIST"
if grep -q '^com/zpkdxgames/plexoncore/' "$LIST"; then echo 'PlexonCore classes must remain provided' >&2; exit 1; fi
if grep -q '^org/bukkit/' "$LIST"; then echo 'Paper/Bukkit API classes must remain provided' >&2; exit 1; fi
printf 'distribution verification passed: %s\n' "$JAR"
