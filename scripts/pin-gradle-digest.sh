#!/usr/bin/env bash
# =============================================================================
# pin-gradle-digest.sh — Gradle wrapper distribution integrity pin.
#
# gradle/wrapper/gradle-wrapper.properties ships no hardcoded
# distributionSha256Sum: the value must come from Gradle's official
# gradle-<v>-bin.zip.sha256 (never guessed — a wrong digest fails every fresh
#./gradlew download). This script fetches that official checksum and pins it
# into the LOCAL wrapper properties (a working-copy edit, never committed by
# the script) so the wrapper verifies every fresh distribution download and
# fails fast on a tampered/corrupted zip.
#
# scripts/pin-gradle-digest.sh # pin (or cross-check) in place
# scripts/pin-gradle-digest.sh --print # print the official digest only
#
# Behavior:
# * no pin present → append distributionSha256Sum=<official digest>;
# * pin present → cross-check against the official digest; a mismatch is
# exit 1 (a stale pin after a Gradle bump fails HERE with
# a clear message, not as a cryptic wrapper download
# error);
# * fetch failure → exit 1 — callers that REQUIRE verification (CI steps in
# ci.yml / release.yml / docker.yml) fail loudly and never
# silently degrade to "distribution unchecked".
#
# CI runs this right after checkout, before the first./gradlew invocation
# (fresh runners download the distribution); dev machines can run it the same
# way. Needs curl + network — exactly what a distribution download needs anyway.
# =============================================================================
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROPS="$ROOT/gradle/wrapper/gradle-wrapper.properties"

PRINT_ONLY=0
if [[ "${1:-}" == "--print" ]]; then
  PRINT_ONLY=1
elif [[ -n "${1:-}" ]]; then
  echo "usage: scripts/pin-gradle-digest.sh [--print]" >&2
  exit 2
fi

command -v curl >/dev/null 2>&1 || { echo "pin-gradle-digest: FAIL: curl not found" >&2; exit 1; }
[[ -f "$PROPS" ]] || { echo "pin-gradle-digest: FAIL: missing $PROPS" >&2; exit 1; }

# distributionUrl is properties-escaped (https\://…): unescape, trim, and keep
# the last definition (java.util.Properties semantics).
url="$(sed -n 's/^distributionUrl=//p' "$PROPS" | tail -1 | tr -d '[:space:]' | sed 's/\\:/:/g')"
if [[ "$url" != https://* ]]; then
  echo "pin-gradle-digest: FAIL: cannot parse distributionUrl from $PROPS (got '${url:-empty}')" >&2
  exit 1
fi

sha="$(curl -fsSL --retry 3 "${url}.sha256" | awk 'NR==1 {print tolower($1)}')" || {
  # not a bare assignment: under `set -e` a failed fetch would otherwise exit 1
  # here SILENTLY — CI would show no reason for the failure.
  echo "pin-gradle-digest: FAIL: cannot fetch ${url}.sha256 (exit $?)" >&2
  exit 1
}
if [[ ! "$sha" =~ ^[0-9a-f]{64}$ ]]; then
  echo "pin-gradle-digest: FAIL: ${url}.sha256 is not a 64-hex sha256 (got '${sha:-empty}')" >&2
  exit 1
fi

if [[ "$PRINT_ONLY" -eq 1 ]]; then
  echo "$sha"
  exit 0
fi

current="$(sed -n 's/^distributionSha256Sum=//p' "$PROPS" | tail -1 | tr -d '[:space:]')"
if [[ -n "$current" ]]; then
  if [[ "$current" == "$sha" ]]; then
    echo "pin-gradle-digest: distributionSha256Sum already pinned and matches the official ${url}.sha256"
    exit 0
  fi
  echo "pin-gradle-digest: FAIL: pinned distributionSha256Sum=${current}" \
    "does not match the official ${sha} (${url}.sha256) — the Gradle version" \
    "moved without updating the pin" >&2
  exit 1
fi

tmp="$(mktemp)"
grep -v '^distributionSha256Sum=' "$PROPS" > "$tmp" || true
printf '\n# Pinned by scripts/pin-gradle-digest.sh from %s.sha256 (local integrity pin).\ndistributionSha256Sum=%s\n' \
  "$url" "$sha" >> "$tmp"
mv "$tmp" "$PROPS"
echo "pin-gradle-digest: pinned distributionSha256Sum=$sha (from ${url}.sha256)"
