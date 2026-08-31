#!/usr/bin/env bash
# =============================================================================
# verify-artifacts.sh — the C18 deploy/docs/smoke artifact guard.
#
# The Java build gate (`./gradlew build`) parses no YAML, bash or Python, so a
# corruption of the deploy/docs/smoke layer (e.g. the 4b5d2c3 indentation
# flattening + `'`→`/` character corruption) shipped while `build` stayed green.
# This guard parses every repo artifact that the build does not:
#
# (a) yaml.safe_load_all on every `*.{yml,yaml}` under.github/ deploy/ docs/
# scripts/,
# (b) `bash -n` on every `scripts/**/*.sh`,
# (c) py_compile on every harness Python file under scripts/,
# (d) `docker compose -f deploy/docker-compose.yml config` (when docker is
# available; REQUIRED when VERIFY_ARTIFACTS_REQUIRE_DOCKER=1, e.g. in CI),
# (e) grep guard for the known 4b5d2c3 prose-corruption signatures
# (`the/ `, `// blocks`, `sed/`, `nohup./`, `if !./`,...), tracked files
# only (untracked leftovers in.run/ etc. are not repo artifacts).
#
# The k8s manifests are covered by the YAML parse in (a); when `kubeconform` is on
# the PATH it additionally schema-validates deploy/k8s/ (report's C18 suggestion —
# optional, so CI needs no new tool).
#
# Exit 0 = everything parses. Exit 1 = at least one artifact is broken.
# Wired into CI (ci.yml) and into the root Gradle `check` task, so a corruption
# of this class can never again pass the gate. The guard's own legs are
# exercised by scripts/test_verify_artifacts.sh (fixture-based, wired into the
# same `verifyArtifacts` Gradle task) — an untracked guard, a subshell bug or a
# silent no-op leg now fails `./gradlew build`.
#
# PyYAML (`import yaml`) is the guard's only non-stdlib dependency. Without it
# the (a) YAML leg prints an install hint and is skipped on a dev machine, but
# stays mandatory wherever CI=true or VERIFY_ARTIFACTS_REQUIRE_YAML=1 (ci.yml,
# release.yml) — CI must never degrade to "YAML unchecked".
#
# An absent python3 degrades the same way for both python-dependent legs: (a)
# YAML and (c) py_compile are skipped with an install hint on a dev machine but
# stay mandatory wherever CI=true or VERIFY_ARTIFACTS_REQUIRE_YAML=1 — the
# guard must not hard-fail a python-less machine that a WARN describes.
#
# VERIFY_ARTIFACTS_REPO overrides the repo root (used by the guard's own test to
# point the guard at a throwaway fixture tree; defaults to the repo the script
# lives in).
# =============================================================================
set -u

REPO="${VERIFY_ARTIFACTS_REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$REPO"

FAILURES=0
WARNINGS=0

note() { printf '[verify-artifacts] %s\n' "$*" >&2; }
fail() { printf '[verify-artifacts] FAIL: %s\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }
warn() { printf '[verify-artifacts] WARN: %s\n' "$*" >&2; WARNINGS=$((WARNINGS + 1)); }

# ---------------------------------------------------------------- (a) YAML
note "(a) parsing YAML under .github/ deploy/ docs/ scripts/"

# PyYAML is the only non-stdlib dependency of this guard. When python3 cannot
# import it the leg degrades explicitly instead of crashing with a traceback
# (the heredoc below exits 1 for a perfectly clean tree and hard-fails
# `./gradlew build`): outside CI the leg is skipped with an install hint; CI
# (GitHub sets CI=true) and VERIFY_ARTIFACTS_REQUIRE_YAML=1 make it mandatory —
# a runner without PyYAML must fail, not silently skip YAML validation.
YAML_OK=0
if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import yaml' >/dev/null 2>&1 && YAML_OK=1
fi
if [ "$YAML_OK" -ne 1 ]; then
    if [ "${VERIFY_ARTIFACTS_REQUIRE_YAML:-0}" = "1" ] || [ -n "${CI:-}" ]; then
        fail "python3 cannot import yaml (PyYAML) — the (a) YAML leg is mandatory here (install: python3 -m pip install pyyaml)"
    else
        warn "PyYAML not importable — skipping the (a) YAML leg (install: python3 -m pip install pyyaml)"
    fi
fi

YAML_FILES="$(mktemp)"
# Tracked files only — untracked leftovers in gitignored dirs (e.g. *.run/ run
# artifacts) are not repo artifacts and are not the guard's concern.
git ls-files -- .github deploy docs scripts | grep -E '\.(yml|yaml)$' \
  | sort -u > "$YAML_FILES"

if [ "$YAML_OK" -eq 1 ]; then
    python3 - "$YAML_FILES" <<'PY'
import sys
import yaml

files = [l.strip() for l in open(sys.argv[1]) if l.strip()]
bad = 0
for path in files:
    try:
        with open(path, encoding="utf-8") as fh:
            for _ in yaml.safe_load_all(fh):
                pass
    except Exception as exc:  # noqa: BLE001 — report every broken file
        print(f"[verify-artifacts] FAIL: YAML parse error: {path}: {exc}", file=sys.stderr)
        bad += 1
sys.exit(1 if bad else 0)
PY
    [ $? -ne 0 ] && FAILURES=$((FAILURES + 1))
fi

# ---------------------------------------------------------------- (a2) kubeconform (optional)
if command -v kubeconform >/dev/null 2>&1; then
    if [ -d deploy/k8s ]; then
        note "(a2) kubeconform -strict deploy/k8s/"
        kubeconform -strict deploy/k8s/ || fail "kubeconform -strict deploy/k8s/"
    else
        note "(a2) deploy/k8s not present; skipping kubeconform"
    fi
else
    note "(a2) kubeconform not on PATH; k8s covered by the YAML parse in (a)"
fi

# ---------------------------------------------------------------- (b) bash -n
note "(b) bash -n on every scripts/**/*.sh"

# Process substitution (not a pipeline): a `while... done < <(git ls-files)`
# keeps the loop in the current shell, so `fail` increments the parent's
# FAILURES counter. As a pipeline tail the loop ran in a subshell and a broken
# shell script silently passed the guard.
while IFS= read -r sh; do
    bash -n "$sh" || fail "bash syntax: $sh"
done < <(git ls-files -- scripts | grep '\.sh$' | sort -u)

# ---------------------------------------------------------------- (c) py_compile
note "(c) py_compile on every scripts/**/*.py"

PY_FILES="$(mktemp)"
git ls-files -- scripts | grep '\.py$' | sort -u > "$PY_FILES"

# python3 itself is this leg's only dependency. Without it on PATH the leg must
# degrade exactly like a missing PyYAML in (a) — a WARN + install hint on a dev
# machine, mandatory wherever CI=true or VERIFY_ARTIFACTS_REQUIRE_YAML=1 — not
# fail per.py file and take `./gradlew build` down with it.
if ! command -v python3 >/dev/null 2>&1; then
    if [ "${VERIFY_ARTIFACTS_REQUIRE_YAML:-0}" = "1" ] || [ -n "${CI:-}" ]; then
        fail "python3 not on PATH — the (c) py_compile leg is mandatory here (install python3)"
    else
        warn "python3 not on PATH — skipping the (c) py_compile leg (install python3)"
    fi
else
    while IFS= read -r py; do
        python3 -m py_compile "$py" 2>/dev/null || fail "python compile: $py"
    done < "$PY_FILES"
fi

# ---------------------------------------------------------------- (d) docker compose
note "(d) docker compose config (deploy/docker-compose.yml)"

if [ ! -f deploy/docker-compose.yml ]; then
    # No compose file (e.g. the guard's own fixture tree) — nothing to validate.
    note "(d) deploy/docker-compose.yml not present; skipping compose config (required in CI)"
    if [ "${VERIFY_ARTIFACTS_REQUIRE_DOCKER:-0}" = "1" ]; then
        fail "deploy/docker-compose.yml missing but VERIFY_ARTIFACTS_REQUIRE_DOCKER=1"
    fi
elif command -v docker >/dev/null 2>&1; then
    if docker compose -f deploy/docker-compose.yml config --quiet; then
        note "(d) docker compose config: OK"
    else
        fail "docker compose -f deploy/docker-compose.yml config"
    fi
elif [ "${VERIFY_ARTIFACTS_REQUIRE_DOCKER:-0}" = "1" ]; then
    fail "docker unavailable but VERIFY_ARTIFACTS_REQUIRE_DOCKER=1"
else
    warn "(d) docker not available; skipping docker compose config (required in CI)"
fi

# ---------------------------------------------------------------- (e) prose guard
note "(e) grepping tracked prose for known 4b5d2c3 corruption signatures"

# Tracked files only (like §(a)): untracked leftovers (e.g. *.run/) are not repo
# artifacts. Single-pipe ERE alternation — `\|` under grep -E is a literal pipe,
# which silently disabled this leg. The guard and its test document the
# signatures, so they self-exclude.
CORRUPTION_GREP="the/ |the//|// blocks|/// lesson|serving the/|and the//| sed/|nohup\./|if !\./|--chown=builder\.\.|the/ store|the/ mapper"
CORRUPT_FILES="$(mktemp)"
CORRUPT_HITS="$(mktemp)"
git ls-files -- .github deploy docs scripts README.md AGENTS.md CHANGELOG.md \
  | grep -E '\.(md|sh|yml|yaml|toml|py)$|(^|/)Dockerfile$|\.service$' \
  | grep -v -E 'scripts/verify-artifacts\.sh|scripts/test_verify_artifacts\.sh' \
  > "$CORRUPT_FILES"

if [ -s "$CORRUPT_FILES" ]; then
    tr '\n' '\0' < "$CORRUPT_FILES" \
      | xargs -0 grep -HnE "$CORRUPTION_GREP" 2>/dev/null > "$CORRUPT_HITS" || true
fi

if [ -s "$CORRUPT_HITS" ]; then
    cat "$CORRUPT_HITS" >&2
    fail "corruption signatures found (see lines above)"
else
    note "(e) no corruption signatures"
fi

rm -f "$YAML_FILES" "$PY_FILES" "$CORRUPT_FILES" "$CORRUPT_HITS"

# ---------------------------------------------------------------- summary
if [ "$FAILURES" -gt 0 ]; then
    echo "[verify-artifacts] FAILED: $FAILURES problem(s), $WARNINGS warning(s)" >&2
    exit 1
fi
echo "[verify-artifacts] OK: all artifacts parse ($WARNINGS warning(s))"
exit 0
