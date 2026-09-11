#!/usr/bin/env bash
set -euo pipefail
JAR="${1:-target/PlexonTravel-2.0.0-rc.1.jar}"
[[ -f "$JAR" ]] || { echo "missing jar: $JAR" >&2; exit 1; }
LIST="$(mktemp)"
trap 'rm -f "$LIST"' EXIT
jar tf "$JAR" > "$LIST"
for required in plugin.yml config.yml gui.yml messages.yml migration.yml com/plexon/travel/PlexonTravel.class com/plexon/travel/api/PlexonTravelAPI.class com/plexon/travel/event/PlexonTravelStartEvent.class com/plexon/travel/internal/AttemptLedger.class com/plexon/travel/papi/PlexonTravelExpansion.class org/sqlite/JDBC.class; do grep -qx "$required" "$LIST" || { echo "missing $required" >&2; exit 1; }; done
for prefix in '^com/zpkdxgames/plexoncore/' '^org/bukkit/' '^io/papermc/' '^net/kyori/adventure/' '^me/clip/placeholderapi/' '^net/milkbowl/vault/'; do if grep -q "$prefix" "$LIST"; then echo "provided dependency shaded unexpectedly: $prefix" >&2; exit 1; fi; done
javap -verbose -classpath "$JAR" com.plexon.travel.PlexonTravel | grep -q 'major version: 69' || { echo 'expected Java 25 class major 69' >&2; exit 1; }
printf 'distribution verification passed: %s\n' "$JAR"
