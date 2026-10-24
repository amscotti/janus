#!/usr/bin/env bash
# Verify a v* release tag matches every packaged version source before any
# expensive build or publication runs.
#
# Usage:
#   scripts/verify-release-version.sh [tag]
#   GITHUB_REF_NAME=v0.2.0 scripts/verify-release-version.sh
#
# Optional JANUS_RELEASE_ROOT points at a fixture checkout (used by the local
# verification drill). Exits 1 with a GitHub ::error:: annotation on mismatch.
set -euo pipefail

ROOT="${JANUS_RELEASE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
TAG="${1:-${GITHUB_REF_NAME:-}}"
[[ -n "$TAG" ]] || {
  echo "usage: $0 <v-tag> (or set GITHUB_REF_NAME)" >&2
  exit 2
}
[[ "$TAG" == v* ]] || {
  echo "::error::release tag must start with v: $TAG"
  exit 1
}
VERSION="${TAG#v}"
GRADLE_VERSION="$(sed -n 's/^[[:space:]]*version[[:space:]]*=//p' "$ROOT/gradle.properties" | tr -d '[:space:]')"
PACKAGED_VERSION="$(awk '/^\[janus\]/{f=1; next} /^\[/{f=0} f && $1=="version"{gsub(/[ "]/, "", $3); print $3; exit}' \
  "$ROOT/janus-gateway/src/main/resources/application.toml")"
CONFIG_VERSION="$(awk '/^\[janus\]/{f=1; next} /^\[/{f=0} f && $1=="version"{gsub(/[ "]/, "", $3); print $3; exit}' \
  "$ROOT/config.toml")"

echo "tag=${TAG} version=${VERSION} gradle.properties=${GRADLE_VERSION} packaged=[${PACKAGED_VERSION}] config=[${CONFIG_VERSION}]"

fail=0
if [[ -z "$GRADLE_VERSION" || "$VERSION" != "$GRADLE_VERSION" ]]; then
  echo "::error::tag ${TAG} (${VERSION}) does not match gradle.properties version (${GRADLE_VERSION:-unset}) — bump versions and re-tag"
  fail=1
fi
if [[ "$VERSION" != "$PACKAGED_VERSION" ]]; then
  echo "::error::tag ${TAG} (${VERSION}) does not match the packaged [janus] version in janus-gateway/src/main/resources/application.toml (${PACKAGED_VERSION:-unset}) — bump all version sources and re-tag"
  fail=1
fi
if [[ "$VERSION" != "$CONFIG_VERSION" ]]; then
  echo "::error::tag ${TAG} (${VERSION}) does not match config.toml [janus] version (${CONFIG_VERSION:-unset}) — bump all version sources and re-tag"
  fail=1
fi
exit "$fail"
