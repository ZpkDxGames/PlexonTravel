#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:?usage: paper-startup-smoke.sh <version> [candidate-jar]}"
CANDIDATE="${2:-target/PlexonTravel-${VERSION}.jar}"
PAPER_VERSION="26.2"
PAPER_BUILD="121"
CORE_VERSION="2.0.5"
CORE_SHA256="bf4df796e83571e76c06b296c75c5e73ddc8053cb2c587d74c08cd3922e0b4d4"
USER_AGENT="PlexonTravel-CI/${VERSION} (https://github.com/ZpkDxGames/PlexonTravel)"
ROOT="$(pwd)"
WORK="$(mktemp -d)"
PID=""

cleanup() {
  if [[ -n "${PID}" ]] && kill -0 "${PID}" 2>/dev/null; then
    kill "${PID}" 2>/dev/null || true
    wait "${PID}" 2>/dev/null || true
  fi
  rm -rf "${WORK}"
}
trap cleanup EXIT

test -f "${CANDIDATE}"
test -f "src/test/resources/legacy-2.0.0-config.yml"
mkdir -p "${WORK}/plugins/PlexonTravel"
cp "${CANDIDATE}" "${WORK}/plugins/PlexonTravel-${VERSION}.jar"
cp src/test/resources/legacy-2.0.0-config.yml "${WORK}/plugins/PlexonTravel/config.yml"

curl -L --fail --retry 3 \
  -H "User-Agent: ${USER_AGENT}" \
  -o "${WORK}/plugins/PlexonCore-${CORE_VERSION}.jar" \
  "https://github.com/ZpkDxGames/PlexonCore/releases/download/v${CORE_VERSION}/PlexonCore-${CORE_VERSION}.jar"
echo "${CORE_SHA256}  ${WORK}/plugins/PlexonCore-${CORE_VERSION}.jar" | sha256sum -c -

builds_json="$(curl -L --fail --retry 3 -H "User-Agent: ${USER_AGENT}" \
  "https://fill.papermc.io/v3/projects/paper/versions/${PAPER_VERSION}/builds")"
paper_url="$(jq -r --argjson build "${PAPER_BUILD}" \
  'first(.[] | select(.id == $build and .channel == "STABLE") | .downloads."server:default".url) // empty' \
  <<<"${builds_json}")"
test -n "${paper_url}"
curl -L --fail --retry 3 -H "User-Agent: ${USER_AGENT}" -o "${WORK}/paper.jar" "${paper_url}"

cat > "${WORK}/eula.txt" <<'EOF'
eula=true
EOF
cat > "${WORK}/server.properties" <<'EOF'
online-mode=false
level-name=smoke_world
max-players=1
view-distance=2
simulation-distance=2
spawn-protection=0
motd=PlexonTravel CI smoke
EOF

mkfifo "${WORK}/console.in"
exec 3<>"${WORK}/console.in"
(
  cd "${WORK}"
  java -Xms256M -Xmx768M -jar paper.jar --nogui <&3 >startup-console.log 2>&1
) &
PID=$!

ready=0
for _ in $(seq 1 180); do
  log="${WORK}/logs/latest.log"
  if [[ -f "${log}" ]] && grep -Fq "[PlexonTravel] STARTUP_READY version=${VERSION} core=${CORE_VERSION}" "${log}"; then
    ready=1
    break
  fi
  if ! kill -0 "${PID}" 2>/dev/null; then
    break
  fi
  sleep 1
done

printf 'stop\n' >&3 || true
for _ in $(seq 1 30); do
  if ! kill -0 "${PID}" 2>/dev/null; then break; fi
  sleep 1
done
if kill -0 "${PID}" 2>/dev/null; then
  kill "${PID}" 2>/dev/null || true
fi
wait "${PID}" 2>/dev/null || true
PID=""

log="${WORK}/logs/latest.log"
if [[ "${ready}" != 1 || ! -f "${log}" ]]; then
  echo "PlexonTravel did not reach STARTUP_READY during Paper startup" >&2
  [[ -f "${log}" ]] && cat "${log}" >&2
  cat "${WORK}/startup-console.log" >&2 || true
  exit 1
fi

grep -Fq "[PlexonCore] ${CORE_VERSION} enabled" "${log}"
grep -Fq "[PlexonTravel] STARTUP_READY version=${VERSION} core=${CORE_VERSION}" "${log}"
grep -Fq "[PlexonTravel] PlexonTravel ${VERSION} enabled against PlexonCore ${CORE_VERSION}" "${log}"
world_settings="${WORK}/plugins/PlexonTravel/world-settings.yml"
if [[ ! -f "${world_settings}" ]] || ! grep -Eq '^schema-version:[[:space:]]*1[[:space:]]*$' "${world_settings}"; then
  echo "PlexonTravel 3.1 world-settings.yml was not created/loaded with schema-version 1" >&2
  [[ -f "${world_settings}" ]] && cat "${world_settings}" >&2
  exit 1
fi
if [[ -f "${WORK}/plugins/PlexonTravel/startup-failure.txt" ]]; then
  echo "PlexonTravel left startup-failure.txt after successful startup" >&2
  cat "${WORK}/plugins/PlexonTravel/startup-failure.txt" >&2
  exit 1
fi
if grep -Eiq 'Error occurred while enabling PlexonTravel|Cannot execute command .*PlexonTravel.*plugin is disabled|NoClassDefFoundError:.*plexon|NoSuchMethodError:.*plexon|LinkageError:.*plexon' "${log}"; then
  echo "PlexonTravel startup smoke found a fatal plugin error" >&2
  grep -Ein 'PlexonTravel|Error occurred while enabling|NoClassDefFoundError|NoSuchMethodError|LinkageError' "${log}" >&2 || true
  exit 1
fi

paper_line="$(grep -m1 -E 'This server is running Paper|Starting minecraft server version|Running Java' "${log}" || true)"
travel_line="$(grep -m1 -F "[PlexonTravel] STARTUP_READY version=${VERSION} core=${CORE_VERSION}" "${log}")"
core_line="$(grep -m1 -F "[PlexonCore] ${CORE_VERSION} enabled" "${log}")"
cat > "${ROOT}/PAPER_STARTUP_SMOKE.txt" <<EOF
result=PASS
paper_version=${PAPER_VERSION}
paper_build=${PAPER_BUILD}
java_version=$(java -version 2>&1 | head -n1)
plexoncore_version=${CORE_VERSION}
plexoncore_sha256=${CORE_SHA256}
plexontravel_version=${VERSION}
regression_fixture=legacy-2.0.0-config-with-inherited-3.x-defaults
world_settings_schema=1
core_evidence=${core_line}
travel_evidence=${travel_line}
paper_evidence=${paper_line}
live_plexoncraft=NOT_TESTED_BY_CI
EOF
cat "${ROOT}/PAPER_STARTUP_SMOKE.txt"
