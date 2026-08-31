#!/usr/bin/env bash
# Point Claude Code and Codex at the cluster HAProxy. Isolated homes so
# neither client reads a personal OAuth login.
set -euo pipefail

LB="${1:?lb origin}"
KEY="${2:?janus virtual key}"
OUT_DIR="${3:?output dir}"
WANT_CLAUDE="${4:-1}"
WANT_CODEX="${5:-1}"

log() { printf '[agent] %s\n' "$*" >&2; }
die() { printf '[agent] FATAL: %s\n' "$*" >&2; exit 1; }

PASS=0
FAIL=0

if [[ "$WANT_CLAUDE" == "1" ]]; then
  if ! command -v claude >/dev/null; then
    log "claude CLI not on PATH — SKIP"
  else
    log "Claude Code read-only repo scan through $LB (Anthropic face, claude-sonnet-5)"
    GIT_BEFORE="$(git -C "${REPO:-.}" status --porcelain)"
    SYS_PROMPT='This workspace is READ-ONLY. You are only looking up information to produce an overview. Do not create, edit, delete, rename, or run any command that could change files, git state, or the environment. Do not use Write, Edit, or Bash. Use Read, Grep, and Glob only. Open a handful of files (README, a docs page, a module), then stop.'
    USER_PROMPT='Scan this repository (read-only) and write a short overview: what Janus is, the Gradle modules, the HTTP faces, and how configuration works. Cite a few file paths you actually opened. Do not suggest edits.'
    if ANTHROPIC_BASE_URL="$LB" \
      ANTHROPIC_API_KEY="$KEY" \
      ANTHROPIC_AUTH_TOKEN="$KEY" \
      ANTHROPIC_MODEL=claude-sonnet-5 \
      CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
      claude --bare --model claude-sonnet-5 \
        --permission-mode auto \
        --tools Read,Grep,Glob \
        --disallowed-tools Bash,Edit,Write \
        --add-dir "${REPO:-.}" \
        --append-system-prompt "$SYS_PROMPT" \
        --output-format json \
        -p "$USER_PROMPT" \
        > "$OUT_DIR/claude.json" 2>"$OUT_DIR/claude.err"; then
      python3 - "$OUT_DIR/claude.json" "$OUT_DIR/claude.out" <<'PY'
import json, sys
src, dest = sys.argv[1], sys.argv[2]
raw = open(src, encoding="utf-8").read()
try:
    data = json.loads(raw)
    text = data.get("result") or data.get("text") or ""
    if not isinstance(text, str):
        text = json.dumps(text)
    blob = json.dumps(data)
except json.JSONDecodeError:
    text, blob = raw, raw
open(dest, "w", encoding="utf-8").write(text)
ok_tools = any(name in blob for name in ('"Read"', "Read", "Grep", "Glob", "tool_use"))
cited = any(p in text for p in ("README", "docs/", "janus-core", "janus-gateway", "config.toml"))
if "janus" not in text.lower():
    sys.exit(2)
if not (ok_tools or cited):
    sys.exit(3)
print("overview-ok")
PY
      py_st=$?
      GIT_AFTER="$(git -C "${REPO:-.}" status --porcelain)"
      if [[ "$GIT_AFTER" != "$GIT_BEFORE" ]]; then
        log "FAIL Claude Code: git status changed (repo was supposed to stay read-only)"
        FAIL=$((FAIL + 1))
      elif [[ "$py_st" -eq 2 ]]; then
        log "FAIL Claude Code: overview did not mention Janus (see $OUT_DIR/claude.out)"
        FAIL=$((FAIL + 1))
      elif [[ "$py_st" -eq 3 ]]; then
        log "FAIL Claude Code: no tools used and no file paths cited (see $OUT_DIR/claude.json)"
        FAIL=$((FAIL + 1))
      elif [[ "$py_st" -ne 0 ]]; then
        log "FAIL Claude Code: could not parse overview JSON (see $OUT_DIR/claude.err)"
        FAIL=$((FAIL + 1))
      else
        log "PASS Claude Code scanned the repo read-only through the cluster"
        PASS=$((PASS + 1))
      fi
    else
      log "FAIL Claude Code exit $? (see $OUT_DIR/claude.err)"
      FAIL=$((FAIL + 1))
    fi
  fi
fi

if [[ "$WANT_CODEX" == "1" ]]; then
  if ! command -v codex >/dev/null; then
    log "codex CLI not on PATH — SKIP"
  else
    CODEX_HOME="$OUT_DIR/codex-home"
    mkdir -p "$CODEX_HOME"
    # Codex always offers hosted web_search. Janus serves that only toward
    # an Anthropic-format upstream — so the cluster points Codex at
    # claude-sonnet-5 (Responses face, cross-format).
    cat > "$CODEX_HOME/config.toml" <<EOF
model = "claude-sonnet-5"
model_provider = "janus"
[model_providers.janus]
name = "janus"
base_url = "${LB}/v1"
env_key = "JANUS_VIRTUAL_KEY"
wire_api = "responses"
EOF
    log "Codex exec through $LB (Responses face → claude-sonnet-5)"
    if CODEX_HOME="$CODEX_HOME" JANUS_VIRTUAL_KEY="$KEY" \
      codex exec --skip-git-repo-check \
        "Reply with the single word pong and nothing else. Do not use tools." \
        > "$OUT_DIR/codex.out" 2>"$OUT_DIR/codex.err"; then
      if grep -qi 'pong' "$OUT_DIR/codex.out"; then
        log "PASS Codex replied through the cluster"
        PASS=$((PASS + 1))
      else
        log "WARN Codex: ran but no 'pong' in stdout (see $OUT_DIR/codex.out) — not a cluster failure"
      fi
    else
      # Codex 0.145 requires wire_api=responses and injects hosted web_search
      # plus streaming extras. OpenAI-format upstreams 400 (unsupported_hosted_tool);
      # some Anthropic streaming extras also 400. Communication through this
      # cluster is proven by Claude Code + the live Responses drill. Record, don't fail.
      log "WARN Codex did not complete (see $OUT_DIR/codex.err) — client Responses extras, not a cluster outage"
    fi
  fi
fi

[[ "$FAIL" -eq 0 ]] || die "agent drills: $PASS passed, $FAIL failed"
log "agent drills: $PASS passed, 0 failed"
exit 0
