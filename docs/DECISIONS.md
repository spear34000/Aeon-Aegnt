# Decision Log

Only decisions explicitly approved by the project owner are recorded as **Approved**. Everything else is **Open** or **Deferred**.
Evidence for facts behind decisions: `docs/research/2026-11-10-feasibility.md`.

## Approved (2026-11-10)

| ID | Decision | Notes |
|---|---|---|
| D1 | **TypeScript** core and runtimes; **Kotlin** for the Android app. | Core must stay free of Node-only APIs so it can run on Cloudflare Workers; enforced by a test. Android build tooling (Android Studio/SDK, JDK) is a Phase 6 prerequisite and is not installed or checked yet. |
| D2 | **Cloud-authoritative state with lease + fencing tokens.** Local-only execution must also work. | Phase 1 ships a SQLite store and an in-memory store behind one `Store` interface. D1/Durable Objects/server DB implementations come later. |
| D3 | **K-EXAONE 2.5 (`k-exaone_v2.5`) is the initial default model**, via the official EXAONE API Platform (`https://api.exaone.ai/v1`). The OpenAI-compatible provider interface stays. Other free APIs and Ollama are supported as fallback providers. | The hosted API terms and the open-weight license are separate documents; do not conflate. See D9 for the unresolved BYOK question. Tool calling and structured output are **undocumented** for this API, so Aeon treats them as unsupported until tested. |
| D4 | **Sequential implementation starting at Phase 1.** The full roadmap is kept. | No feature from the master directive is removed. |
| D5 | **Apache-2.0** | |
| D6 | **One owner per deployment; devices pair with key pairs.** | |
| D7 | **Deny-by-default tool policy; command execution only inside a Docker sandbox.** | Host commands are not available in Phase 1 unless a later decision allows them. |
| D8 | **English primary documentation + Korean translation.** | Code identifiers and comments in English. |

| D11 | **Secrets and agent settings are entered later through the UI and stored per platform, never inside the package.** Android: app-private storage (not readable by other apps) with a hardware-backed Android Keystore key wrapping the data; nothing in the APK, assets or BuildConfig, backups excluded. Windows / Linux: platform convention. Windows: user-scoped DPAPI-protected file under `%APPDATA%\Aeon`. Linux: Secret Service (`secret-tool`) when available, otherwise a `0600` file under `$XDG_CONFIG_HOME/aeon`. macOS: **no UI support yet; CLI only**, secrets from environment variables (no keychain integration until decided). Non-secret settings live in `config.json` next to them. | Approved 2026-11-10. Specific mechanisms (DPAPI file, `secret-tool`) are my reading of "convention"; the owner can redirect (e.g. Windows Credential Manager). Environment variables always take precedence. |
| D9 | **Publish the EXAONE integration publicly, bring-your-own-key, with no key in the repository.** | Approved 2026-11-10 ("push everything except the key"). The EXAONE API terms (non-commercial rights; no third-party calls through your product; no key sharing; keys expire in 30 days) still bind each user and the ambiguity for open-source BYOK apps was not resolved by LG; the owner accepted it. Written clarification from LG AI Research is still advisable. |
| D12 | **Docker work is postponed.** `sandbox.exec` stays in the code but is unverified against a real daemon, and Docker live tests stay opt-in. | Approved 2026-11-10. |

Verified fact (not a decision): Oracle Always Free Ampere A1 is 2 OCPU / 12 GB. **Oracle use and card registration are NOT approved**; ask again in Phase 3.

## Open

| ID | Question | Status |
|---|---|---|
| D10 | Secret redaction on tool output is generic (token/password patterns). A config file containing `password: ...` is returned to the model as `[REDACTED]`; if the model writes that file back it is corrupted. Disabling redaction instead sends such secrets to the (possibly external) model. Which do you prefer: (A) keep redaction and block rewriting files that contained redactions, (B) keep redaction as-is and document it, (C) per-path allow/deny lists for redaction? | **Open.** Current behaviour is B. Recommendation: A. |

## Deferred (asked again before the relevant phase)
Cloudflare deploy auth method; Oracle use; UI stack (PWA vs native, Kotlin app scope); secret storage beyond environment variables; memory retention/deletion policy; notification channel; messaging integrations; backup format; competitor install/benchmark downloads.
