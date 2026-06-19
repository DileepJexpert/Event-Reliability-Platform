# Operations Assistant ("Ask Brod")

A read-only, self-hostable genAI assistant over Brod's own failure data — a "Davis CoPilot" scoped to
the failure/reliability domain. Operators ask natural-language questions ("why are payments failing?",
"summarise the active incidents") and get **cited, grounded** answers. It is part of the
[platform architecture](PLATFORM_ARCHITECTURE.md) "Intelligence" layer.

## Principles

1. **Read-only.** The assistant answers, summarises and drafts. It never takes a control-plane action
   (no replay/quarantine/approve). Recovery stays behind maker-checker.
2. **Self-hosted / in-network.** It targets the bank's own OpenAI-compatible model endpoint, so failed-
   event data never leaves the bank's network. No third-party LLM is called.
3. **Grounded, not generative-from-thin-air.** Every answer is built from retrieved Brod records (RAG)
   and cites the incident/failure ids it used. The system prompt forbids inventing ids, numbers or causes.
4. **PII-masked.** The retrieved context is run through the same PII masking as the rest of the platform
   *before* it reaches the model — defence in depth even with a local model.
5. **Audited.** Every query is written to the audit log with the acting user.

## Architecture

```
operator question ─▶ AssistantController (POST /api/assistant/ask)
                  ─▶ AssistantService
                        1. retrieve: active incidents + recent failures (ReadModels)
                        2. mask:     PayloadProtectionService.maskText(context)
                        3. prompt:   system instruction + question + masked context
                        4. call:     AssistantModel.chat(...)  ── self-hosted model
                        5. cite:     collect incident/failure ids placed in context
                        6. audit:    AuditService (actor + question)
                  ◀── AssistantAnswerDto { answer, citations[], contextSize, grounded }
```

- **`AssistantModel`** (interface) — provider-agnostic. Default impl
  **`OpenAiCompatibleAssistantModel`** POSTs to `{base-url}/chat/completions` (vLLM, TGI, Ollama,
  LocalAI, or an internal gateway). Swap the impl to back it with anything; Brod never depends on a vendor.
- **`AssistantService`** — the RAG orchestration (retrieve → mask → prompt → call → cite → audit).
- Retrieval is bounded (latest incidents + recent failures) and computed on demand from the read models;
  there is no extra store.

## Configuration

Disabled by default. Point it at your in-network model:

```yaml
reliability:
  assistant:
    enabled: true
    base-url: http://llm.internal.bank:8000/v1   # OpenAI-compatible endpoint
    model: your-bank-model
    api-key: ${BANK_LLM_TOKEN:}                   # optional, for an internal gateway
    timeout: 30s
```

Env equivalents: `RELIABILITY_ASSISTANT_ENABLED`, `RELIABILITY_ASSISTANT_URL`,
`RELIABILITY_ASSISTANT_MODEL`, `RELIABILITY_ASSISTANT_API_KEY`.

When `enabled=false` or no `base-url` is set, the endpoint returns a friendly "not configured" reply
(`grounded:false`) instead of erroring — so the **Ask Brod** console tab never breaks in a demo.

## API

```
POST /api/assistant/ask
  { "question": "why are payments failing?" }

200 OK
  {
    "answer": "Most failures on payments.events are schema-drift … [inc-7] [corr-123]",
    "citations": ["inc-7", "corr-123", ...],
    "contextSize": 24,
    "grounded": true
  }
```

`citations` are the incident/failure ids placed in the model's context (the console renders them as
source chips). `contextSize` is how many records were grounded on. `grounded` is false when the
assistant is unconfigured or had no context.

## Security & governance

- **Data residency:** with a self-hosted model, payload-derived context stays in-network. Do **not**
  point `base-url` at an external API for a regulated deployment.
- **Least privilege:** the context is PII-masked regardless of where the model runs.
- **Auditability:** each query is an audit event (`ASSISTANT_QUERY`) attributing the asker.
- **RBAC:** the endpoint is a read (`VIEWER`/`OPERATOR`) under the `secure` profile.

## Testing

`AssistantServiceTest` uses a stub `AssistantModel` (no live LLM), so it runs in CI. It proves the
context is assembled with ids, **PII is masked before reaching the model**, the answer is cited, the
query is audited, and the disabled-path returns the friendly message.

## Limitations & roadmap

- **Single-turn.** No conversation memory yet — each question is independent. (Roadmap: short context
  window of prior turns.)
- **Heuristic retrieval.** Recent incidents + failures, not semantic search. (Roadmap: rank retrieval by
  relevance to the question / embeddings over a self-hosted embedder.)
- **No tool use.** It reads; it cannot act. This is intentional — actions stay behind maker-checker.
