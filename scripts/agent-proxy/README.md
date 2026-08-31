# Point coding agents at Janus

Config: `scripts/agent-proxy/config.toml` (port **18080**, auth on).

```bash
set -a && source .env && set +a
export JAVA_HOME="${JAVA_HOME:-$(mise where java 2>/dev/null)}"
./gradlew :janus-cli:run --args="--config $(pwd)/scripts/agent-proxy/config.toml"

# mint a virtual key (once per process — in-memory store)
KEY=$(curl -s -X POST http://127.0.0.1:18080/key/generate \
  -H "x-api-key: $JANUS_MASTER_KEY" -H 'Content-Type: application/json' \
  -d '{"name":"agent","models":["claude-sonnet-5","claude-haiku-4-5","gpt-5.6-luna","gpt-5.6"]}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["key"])')
```

## Claude Code (Anthropic face)

```bash
ANTHROPIC_BASE_URL=http://127.0.0.1:18080 \
ANTHROPIC_API_KEY="$KEY" ANTHROPIC_AUTH_TOKEN="$KEY" \
ANTHROPIC_MODEL=claude-sonnet-5 \
ANTHROPIC_DEFAULT_HAIKU_MODEL=claude-haiku-4-5 \
CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1 \
claude --bare --model claude-sonnet-5
```

`ANTHROPIC_DEFAULT_HAIKU_MODEL=claude-haiku-4-5` routes Claude Code's
haiku-tier background calls (titles, topic detection) through the proxy.

## Codex (Responses face)

Use an isolated `CODEX_HOME` so ChatGPT OAuth is not used:

```toml
# $CODEX_HOME/config.toml
model = "gpt-5.6"
model_provider = "janus"
model_reasoning_effort = "low"
[model_providers.janus]
name = "janus"
base_url = "http://127.0.0.1:18080/v1"
env_key = "JANUS_VIRTUAL_KEY"
wire_api = "responses"
```

```bash
export CODEX_HOME=... JANUS_VIRTUAL_KEY="$KEY"
codex exec "..."
```

`gpt-5.6-luna` rejects function tools unless `reasoning_effort` is `none`.

## OpenCode (OpenAI-compatible face)

```json
{
  "model": "janus/claude-sonnet-5",
  "provider": {
    "janus": {
      "npm": "@ai-sdk/openai-compatible",
      "options": { "baseURL": "http://127.0.0.1:18080/v1", "apiKey": "sk-janus-..." },
      "models": { "claude-sonnet-5": {}, "gpt-5.6-luna": {} }
    }
  }
}
```

Luna + tools needs `reasoning_effort=none` (OpenAI model rule, not Janus).
