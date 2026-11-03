# System One golden fixtures (decision-model wire shapes)

Representative System One (Jev-shaped) and OpenAI Decisions wire fixtures for
the decision codec replay tests. CI never touches the network (AGENTS.md).

## Provenance (honest)

- `decisions.request.json` — hand-authored Jev-shaped request (state object +
  one question of each primitive: `noul`, `choice`, `score`).
- `decisions.response.ollama-tev1.json` /
  `decisions.response.ollama-nimble.json` — **real captures** from a local
  Ollama 0.40.13 (`POST http://127.0.0.1:11434/v1/systemone`, models
  `tev1:0.8b` / `nimble:latest`), 2026-10-31. Probabilities are the models'
  actual outputs for the request above; re-capture may drift them.
  Note the Ollama-only `prompt_eval_cached_count` extra field.
- `openai.decisions.request.json` — the OpenAI Decisions translation of
  `decisions.request.json`, hand-authored from the documented OpenAI shape
  (`input`/`input_json`, `questions[]`, `predicate`, `choices`, `levels`).
  It pins Janus's documented lossy folds (noul criteria into instructions).
- `openai.decisions.response.json` — hand-authored from the documented
  OpenAI response shape (`answers[]`, per-option probability arrays,
  `refusal` answers, cache-token usage details).

Equality: System One request round-trips are semantic (tree equality);
OpenAI legs are semantic (map/array reshaping is the feature under test).
