# Architecture (Phase 1: Agent Core)

Approved decisions: see [DECISIONS.md](DECISIONS.md). This document describes what **exists** and marks what is planned.

## Layout

```
src/core/   runtime-neutral logic (must run on Cloudflare Workers: no Node-only APIs; enforced by test/core-purity.test.ts)
src/node/   Node adapters: SQLite store, file/HTTP/Docker tools, guards, CLI
test/       node:test suites (+ test/live for opt-in real-service tests)
```

## Components

| Component | File(s) | State |
|---|---|---|
| Persistent Workflow Engine | `core/workflow.ts`, `core/task-ops.ts` | Implemented: lease + fencing, checkpoints, retry/backoff, cancel, scheduled start (`runAfter`), approval resume/expiry, background lease renewal. Recurring/cron schedules: Phase 2 |
| Store | `core/store.ts`, `core/memory-store.ts`, `node/sqlite-store.ts` | Interface + in-memory + SQLite, both pass one conformance suite. D1 / Durable Objects / server DB: later phases |
| Agent Orchestrator | `core/agent.ts` | Plan → act loop. Sub-agents, parallel steps: later |
| Model Provider Layer | `core/models/*` | OpenAI-compatible client + presets (EXAONE, Ollama, llama.cpp, generic), router with consent, owner-approved fallback, local daily budgets, circuit blocking |
| Tool Runtime | `core/tools.ts`, `node/tools/*` | fs (read/write/delete/list), http (get/post), sandbox.exec (Docker). Browser automation, MCP: later |
| Policy Engine | `core/policy.ts` | Deny-by-default, risk classes, tool-reported scope violations always denied. Per-task permissions, cost limits: later |
| Verification Engine | `core/verify.ts`, `node/verifier-env.ts` | file_exists / file_contains / file_sha256 / http_status. Re-verification and user confirmation supported |
| Secret storage | `core/secrets.ts`, `node/secret-backends.ts` | `SecretStore` contract; env (wins), Windows DPAPI, Linux Secret Service / 0600 file; macOS env-only. Android Keystore store: Phase 6 |
| Settings | `core/config.ts`, `node/config-file.ts` | Strict, secret-free `config.json` in the platform config dir (what the UI will edit) |
| Secret redaction | `core/redact.ts` | Exact + pattern redaction on everything persisted or sent back to the model |
| Local API server | `node/server/*`, `node/serve.ts` | HTTP + SSE, device-key pairing and request signing, static web hosting. See [API.md](API.md). Cloud/HTTPS: Phase 2 |
| Memory Engine, Runtime Coordinator, cloud runtimes, deploy tooling, UI, Android app | – | **Not started** |

## Task lifecycle

`PENDING → RUNNING → COMPLETED | FAILED | CANCELLED`, with side states `RETRY_WAIT`, `WAITING_FOR_APPROVAL`, `WAITING_FOR_QUOTA` (and `WAITING_FOR_DEVICE`, reserved for Phase 4).
`COMPLETED` carries a verification status; `COMPLETED + unverified` means nothing objective was checked.

## Execution guarantees (stated honestly)

- **Inside the Store:** a claim yields a strictly increasing fencing token; writes with a stale token or an expired lease are rejected (`StaleLeaseError`). Tested with in-process races and two real OS processes on one SQLite file.
- **Outside the Store (files, HTTP, Docker):** **at-least-once**, not exactly-once. Mitigations: (1) the chosen action is persisted before anything runs (`pendingAction`), so approval/crash/restart resumes the *same* action instead of re-asking a non-deterministic model; (2) an effect journal returns the recorded result if the action already ran; (3) tools receive an idempotency key. A crash between the real side effect and the journal write can still repeat that one effect.
- A runner whose lease is lost stops writing; the new holder continues from the last checkpoint.

## Agent loop and untrusted content

The model returns one JSON action per turn; it never calls tools itself. Code validates the action, persists it, asks the Policy Engine, obtains approval if required, then runs the tool. Tool output (files, web pages, command output) is wrapped as `<untrusted_data>` and cannot close its own wrapper. Permissions are decided in code, so injected text cannot widen them. Heuristic injection detection is only an audit flag, never a safety control.

## Models

K-EXAONE 2.5 (`k-exaone_v2.5`, `https://api.exaone.ai/v1`, OpenAI-compatible, Bearer auth) is the default per D3. Tool calling and structured output are **undocumented** by the API, so the agent uses plain JSON replies and capabilities are `unknown`. `reasoning_effort` defaults to `low` because the API default (`max`) spends the token budget on reasoning; truncated replies are retried with a doubled budget. The integration is published bring-your-own-key (D9); the EXAONE terms still bind each user.

## Android secret contract (planned, Phase 6; decision D11)

The APK is public, so no secret can live inside it. Keys are entered at runtime in the UI and stored in the app's private storage, encrypted with an AES-GCM key held in the Android Keystore (hardware-backed where the device supports it). Nothing in `assets/`, `BuildConfig`, resources or logs; backups excluded (`allowBackup=false` / data-extraction rules); screenshots and clipboard avoided on the key field. This protects against other apps and offline APK analysis; it does not protect against a rooted device or malware running as the app. Implemented later behind the same `SecretStore` contract.

## Known limits in Phase 1

Single machine; no browser tool; no MCP; secrets: DPAPI verified on Windows, Linux backends unverified on a real keyring, macOS env-only; approvals are answered via CLI; `fs.read` redaction can mangle config files containing secrets (open decision D10).
