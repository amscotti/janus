#!/usr/bin/env bash
# =============================================================================
# test_verify_artifacts.sh — fixture-based tests for verify-artifacts.sh.
#
# verify-artifacts.sh (the C18 deploy/docs/smoke guard, wired into./gradlew
# build and ci.yml) shipped regressions that nothing tested :
# * the guard itself was untracked by git, so a fresh clone / CI failed at it;
# * §(b) ran its `bash -n` loop in a pipeline subshell, so a corrupted
# scripts/**/*.sh never failed the guard (the FAILURES counter died in the
# subshell and the script still exited 0).
#
# This test covers every leg of the guard with a throwaway fixture tree:
# * fresh-clone gate: the guard is tracked by git and executable;
# * one broken.sh /.py /.yml / prose-corruption fixture → guard exits 1;
# * §(d) compose leg: no compose file + VERIFY_ARTIFACTS_REQUIRE_DOCKER=1 →
# guard exits 1 (docker-daemon-free — the exact contract ci.yml relies on);
# * §(a)/(c) degradation: missing PyYAML (python3=false on PATH) and absent
# python3 (minimal PATH) → skip + hint when optional, exit 1 when required;
# * a clean fixture tree → guard exits 0.
#
# Fixtures live under a temp `git init`-ed dir (the guard enumerates artifacts
# via `git ls-files`, so only tracked files are validated) — fully offline.
# Run via `./gradlew verifyArtifactsTest` (wired into./gradlew build) or
# `bash scripts/test_verify_artifacts.sh` directly.
# =============================================================================
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
GUARD="$REPO/scripts/verify-artifacts.sh"

PASS=0
FAIL=0
pass() { PASS=$((PASS + 1)); printf 'PASS  %s\n' "$*"; }
fail() { FAIL=$((FAIL + 1)); printf 'FAIL  %s\n' "$*" >&2; }

# ---------------------------------------------------------------- fresh-clone gate
# CRITICAL : the guard must be present in a fresh clone — i.e. tracked by
# git and executable. An untracked-but-present guard is "green locally, red in
# CI" (CI checks out a clean tree and the build job fails at the guard step).
if [ ! -f "$GUARD" ]; then
  echo "FAIL: $GUARD missing" >&2
  exit 1
fi
if [ -x "$GUARD" ]; then
  pass "guard present and executable"
else
  fail "guard not executable (chmod +x)"
fi
if git -C "$REPO" ls-files --error-unmatch scripts/verify-artifacts.sh >/dev/null 2>&1; then
  pass "guard tracked by git (fresh-clone gate)"
else
  fail "guard untracked — a fresh clone / CI breaks at the verify-artifacts step"
fi

# The §(a) YAML leg (and the yaml fixture below) need PyYAML in the interpreter
# python3 resolves to. Skip the yaml-dependent assertions when it is absent so
# the guard's own test stays green on minimal machines.
YAML_OK=0
python3 -c 'import yaml' >/dev/null 2>&1 && YAML_OK=1

# ---------------------------------------------------------------- fixture harness
# The guard enumerates artifacts with `git ls-files`, so each fixture tree is a
# real (empty) git repo; `track` stages the files written per case.
FIX="$(mktemp -d)"
trap 'rm -rf "$FIX"' EXIT

new_fixture() {
  rm -rf "$FIX"
  mkdir -p "$FIX/scripts"
  git -C "$FIX" init -q
}

track() { git -C "$FIX" add -A; }

guard_exit() {  # run the guard against the fixture tree, print its exit code
  local got=0
  # CI= (empty) pins the guard to its dev-mode degradation rules so the fixture
  # cases below are deterministic even when the harness itself runs under CI=true.
  CI= VERIFY_ARTIFACTS_REPO="$FIX" "$GUARD" >/dev/null 2>&1 || got=$?
  echo "$got"
}

assert_guard() {  # assert_guard <desc> <expected-exit>
  local desc="$1" want="$2"
  local got
  got="$(guard_exit)"
  if [ "$got" -eq "$want" ]; then
    pass "$desc (exit $got)"
  else
    fail "$desc (exit $got, want $want)"
  fi
}

# ---------------------------------------------------------------- clean tree → 0
# Runs ungated on purpose: with the guard's PyYAML probe in place, a machine
# without PyYAML exercises the (a) skip path here (still exit 0), a machine
# with it exercises the real YAML parse. Both worlds must stay green — this
# assertion is exactly what failed before the guard degraded gracefully.
new_fixture
printf '#!/usr/bin/env bash\nexit 0\n' > "$FIX/scripts/ok.sh"
printf 'def ok():\n    pass\n' > "$FIX/scripts/ok.py"
printf 'name: ok\n' > "$FIX/scripts/ok.yml"
track
assert_guard "clean fixture tree exits 0" 0

# ---------------------------------------------------------------- broken.sh → 1
# HIGH : was silently green — the §(b) subshell bug swallowed the FAILURES
# counter, so a corrupted shell script shipped with green CI.
new_fixture
printf '#!/usr/bin/env bash\nif true; then echo hi\n' > "$FIX/scripts/broken.sh"
track
assert_guard "broken .sh exits 1 (§(b) subshell regression)" 1

# ---------------------------------------------------------------- broken.py → 1
new_fixture
printf 'def broken(\n' > "$FIX/scripts/broken.py"
track
assert_guard "broken .py exits 1 (§(c))" 1

# ---------------------------------------------------------------- broken.yml → 1
if [ "$YAML_OK" -eq 1 ]; then
  new_fixture
  printf 'a: [unclosed\n' > "$FIX/scripts/broken.yml"
  track
  assert_guard "broken .yml exits 1 (§(a))" 1
else
  echo "SKIP  broken .yml exits 1 (§(a)) — PyYAML not importable"
fi

# ------------------------------------------- missing PyYAML → skip/require
# Deterministically simulate "python3 without PyYAML" on every machine: a
# python3 symlink to false(1) on PATH makes the guard's `import yaml` probe
# fail, so both halves of the probe contract are covered:
# * not required → the (a) leg is skipped with an install hint (WARN) and a
# clean tree still exits 0;
# * required (VERIFY_ARTIFACTS_REQUIRE_YAML=1, or CI=true as on the runner) →
# exit 1 with the same hint, so CI can never degrade to "YAML unchecked".
# The fixture ships no.py file on purpose — with python3=false leg (c) would
# fail for an unrelated reason (leg (c) has its own broken-.py case above).
# A symlink rather than a generated wrapper script: exec'ing a freshly written
# script can stall for minutes under some macOS security agents, while a
# symlink to an existing binary execs immediately.
FALSE_BIN=""
for c in /usr/bin/false /bin/false; do
  [ -x "$c" ] && FALSE_BIN="$c" && break
done
if [ -n "$FALSE_BIN" ]; then
  new_fixture
  printf '#!/usr/bin/env bash\nexit 0\n' > "$FIX/scripts/ok.sh"
  printf 'name: ok\n' > "$FIX/scripts/ok.yml"
  track
  BIN_DIR="$FIX/bin"
  mkdir -p "$BIN_DIR"
  ln -s "$FALSE_BIN" "$BIN_DIR/python3"

  got=0
  OUT="$(mktemp)"
  CI= PATH="$BIN_DIR:$PATH" VERIFY_ARTIFACTS_REPO="$FIX" "$GUARD" >"$OUT" 2>&1 || got=$?
  if [ "$got" -eq 0 ] && grep -q 'pip install pyyaml' "$OUT"; then
    pass "missing PyYAML skips §(a) with an install hint (exit 0)"
  else
    fail "missing PyYAML skips §(a) (exit $got, want 0 + hint)"
  fi

  got=0
  CI= PATH="$BIN_DIR:$PATH" VERIFY_ARTIFACTS_REQUIRE_YAML=1 VERIFY_ARTIFACTS_REPO="$FIX" "$GUARD" >"$OUT" 2>&1 || got=$?
  if [ "$got" -eq 1 ] && grep -q 'pip install pyyaml' "$OUT"; then
    pass "missing PyYAML fails when required (VERIFY_ARTIFACTS_REQUIRE_YAML=1, exit 1)"
  else
    fail "missing PyYAML fails when required (exit $got, want 1 + hint)"
  fi

  got=0
  CI=true PATH="$BIN_DIR:$PATH" VERIFY_ARTIFACTS_REPO="$FIX" "$GUARD" >"$OUT" 2>&1 || got=$?
  if [ "$got" -eq 1 ] && grep -q 'pip install pyyaml' "$OUT"; then
    pass "missing PyYAML fails under CI=true (exit 1)"
  else
    fail "missing PyYAML fails under CI=true (exit $got, want 1)"
  fi
  rm -f "$OUT"
else
  echo "SKIP  missing-PyYAML cases — no false(1) binary found"
fi

# ------------------------------------------------ missing python3 → skip/require
# §(c) probe regression: with python3 absent from PATH the py_compile leg used
# to fail per.py file (hard-failing./gradlew build) while §(a) degraded with
# a WARN for missing PyYAML. Now both python-dependent legs degrade alike:
# skip + hint when optional, exit 1 when required. Simulated with a minimal
# PATH holding every binary the guard invokes except python3 (symlinks to the
# system binaries — same macOS exec-stall rationale as the false(1) trick).
new_fixture
printf '#!/usr/bin/env bash\nexit 0\n' > "$FIX/scripts/ok.sh"
printf 'def ok():\n    pass\n' > "$FIX/scripts/ok.py"
track
MIN_BIN="$FIX/minbin"
mkdir -p "$MIN_BIN"
for c in bash git grep sort tr xargs mktemp rm cat; do
  b="$(command -v "$c" 2>/dev/null || true)"
  [ -n "$b" ] && ln -s "$b" "$MIN_BIN/$c"
done
OUT="$(mktemp)"

got=0
CI= PATH="$MIN_BIN" VERIFY_ARTIFACTS_REPO="$FIX" "$GUARD" >"$OUT" 2>&1 || got=$?
if [ "$got" -eq 0 ] && grep -q 'install python3' "$OUT"; then
  pass "absent python3 skips §(c) with an install hint (exit 0)"
else
  fail "absent python3 skips §(c) (exit $got, want 0 + hint)"
fi

got=0
CI= PATH="$MIN_BIN" VERIFY_ARTIFACTS_REQUIRE_YAML=1 VERIFY_ARTIFACTS_REPO="$FIX" "$GUARD" >"$OUT" 2>&1 || got=$?
if [ "$got" -eq 1 ] && grep -q 'install python3' "$OUT"; then
  pass "absent python3 fails when required (VERIFY_ARTIFACTS_REQUIRE_YAML=1, exit 1)"
else
  fail "absent python3 fails when required (exit $got, want 1 + hint)"
fi

# ------------------------------------- §(d) compose required, file missing → 1
# §(d) regression: the docker-compose leg had no fixture at all, so an
# inverted condition or a subshell there would ship silently. Docker-daemon-
# free by construction: a fixture tree with NO compose file must still fail
# under VERIFY_ARTIFACTS_REQUIRE_DOCKER=1 — the contract ci.yml pins. (The
# not-required half is exercised by every other fixture tree, which ships no
# compose file and still exits 0.)
new_fixture
printf '#!/usr/bin/env bash\nexit 0\n' > "$FIX/scripts/ok.sh"
track
got=0
CI= VERIFY_ARTIFACTS_REQUIRE_DOCKER=1 VERIFY_ARTIFACTS_REPO="$FIX" "$GUARD" >/dev/null 2>&1 || got=$?
if [ "$got" -eq 1 ]; then
  pass "no compose file + VERIFY_ARTIFACTS_REQUIRE_DOCKER=1 exits 1 (§(d))"
else
  fail "no compose file + VERIFY_ARTIFACTS_REQUIRE_DOCKER=1 (exit $got, want 1)"
fi

rm -f "$OUT"

# ------------------------------------------------- corruption signature → 1
# §(e) regression : the prose-corruption grep used ERE with `\|`
# alternation separators, which is a literal pipe in grep -E — the leg was a
# silent no-op. This fixture is the "corruption signatures found" case.
new_fixture
printf 'corrupted prose: the/ store\n' > "$FIX/scripts/broken.md"
track
assert_guard "prose corruption signature exits 1 (§(e))" 1

# ---------------------------------------------------------------- summary
echo
if [ "$FAIL" -eq 0 ]; then
  echo "[test-verify-artifacts] OK: $PASS passed, 0 failed"
  exit 0
fi
echo "[test-verify-artifacts] FAILED: $FAIL of $((PASS + FAIL)) failed" >&2
exit 1
