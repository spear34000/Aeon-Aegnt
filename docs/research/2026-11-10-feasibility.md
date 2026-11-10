# Aeon Feasibility Research — 2026-11-10

Status: **research notes, not decisions.** Nothing here is approved; approved decisions go to `docs/DECISIONS.md`.
Access date for all sources: 2026-11-10. "Official" = vendor docs/changelog. "Third-party" = blogs, aggregators, press.
Re-verify before relying on any number: vendor limits change.

## 1. EXAONE access

| Finding | Source | Type |
|---|---|---|
| Friendli Model APIs changelog: `EXAONE-4.0.1-32B` added 2025-07-14, deprecated 2026-02-28. `K-EXAONE-236B-A23B` added/deprecated repeatedly, last deprecated 2026-08-20. `K-EXAONE-2.0-750B-A37B` added 2026-08-06, deprecated 2026-09-06. | https://friendli.ai/docs/changelog.md | Official |
| Friendli serverless pricing page lists no EXAONE model (GLM-5.x, gemma-4-31B only); no free tier/credits mentioned. | https://friendli.ai/pricing/serverless-endpoints | Official |
| Free K-EXAONE promotion ran until 2026-01-28 and ended. | https://www.digitaltoday.co.kr/en/view/1587/friendlyai-offers-free-api-access-to-k-exaone-model-after-top-1st-evaluation | Third-party |
| **Conclusion (this row only):** as of 2026-11-10 no EXAONE model is listed on *Friendli* serverless. **The earlier broader conclusion "no hosted EXAONE API exists" was WRONG** — see section 1a. | — | — |
| EXAONE 3.5 and EXAONE Deep (2.4B/7.8B/32B) exist in the Ollama library; no official EXAONE 4.0 entry (community GGUF only). | https://registry.ollama.ai/library/exaone3.5 , https://registry.ollama.ai/library/exaone-deep | Official (Ollama registry) |

### 1a. CORRECTION — official EXAONE API Platform (verified 2026-11-10)

An earlier version of this file implied that no hosted EXAONE API was available, based only on Friendli's catalog. That inference was wrong. LG AI Research opened its own **EXAONE API Platform** with **K-EXAONE 2.5** on 2026-11-09 (free; "API key is the only requirement").

| Finding | Source | Type |
|---|---|---|
| K-EXAONE 2.5 API opened 2026-11-09, no separate cost, open to individuals, developers, startups, enterprises, research institutions; free period not specified; limits not specified in the announcement. | https://www.lgresearch.ai/news/view?seq=754 , https://www.lg.co.kr/media/release/30760 | Official |
| Endpoint `POST https://api.exaone.ai/v1/chat/completions`, `Authorization: Bearer <key>`, model ID `k-exaone_v2.5`, OpenAI-compatible Chat Completions (incl. SSE streaming), context 524,288 tokens, text-only, `reasoning_effort` low/high/max (default max), reasoning in `reasoning_content`. | https://docs.exaone.ai/ko/docs/k-exaone-2.5 | Official |
| **Tool/function calling and structured output are NOT documented** (response examples show empty `tool_calls`). Aeon must treat both as unsupported until tested. | same | Official (absence) |
| Rate limits: per-second/minute/day windows, HTTP 429, headers `x-{second,minute,day}-rate-limit[-remaining]`, `retry-after`, `retry-after-ms` (may be absent). Concrete quotas NOT published; consenting to usage-record analysis in the profile raises the daily limit. | https://docs.exaone.ai/ko/docs/rate-limits | Official |
| Error codes 400/401/404/422/429/500; bodies carry `result_code` and `description`; no retry guidance. | https://docs.exaone.ai/ko/docs/error-codes | Official |
| Privacy policy: inputs/outputs stored and used for training only with opt-in consent; usage metadata always collected; data processed on Google Cloud; cross-border transfer not addressed. | https://docs.exaone.ai/ko/terms/privacy-policy | Official |

#### Terms of Service points that affect Aeon (read via fetch tool; Korean version prevails per clause 12.2 — have a human confirm)
https://docs.exaone.ai/ko/terms/terms-of-service
- 2.1: rights are non-commercial, non-transferable, revocable; **third parties may not call the model through your products/services (including integrations)**.
- 2.5: no sublicensing, resale, or re-providing the API to third parties.
- 3.3: keys must not be shared/lent; use under a key counts as the key holder's use.
- 3.4: keys expire 30 days after issue (from ToS clause as summarized; not stated on the Authentication page).
- 4.2(g): no developing/improving competing AI models; 4.2(i): no multiple accounts/keys to evade limits.
- 2.3: training license on inputs/outputs with opt-out — **this appears to conflict with the privacy policy's opt-in wording**; unresolved.
- 9.1: model versions/availability may change or end at any time; 3.5: keys of accounts inactive for 90 days are deactivated.
- **Question (decided as D9 by the owner):** the ToS does not address an open-source app where each end user brings their own key. Clause 3.3 treats BYOK use as the key holder's use, but 2.1/2.5 restrict third-party access via a product. (Resolved by owner decision D9, 2026-11-10: published BYOK anyway; the ambiguity itself is not resolved by LG.)

### EXAONE open-weight licenses (separate from the hosted API terms — do not conflate)
- EXAONE 4.0 (32B) uses *EXAONE AI Model License 1.2-NC*: research/education only, commercial use prohibited, outputs may not be used to build competing models. Source: https://huggingface.co/LGAI-EXAONE/EXAONE-4.0-32B/blob/main/LICENSE (official; summarized by a fetch tool, not read verbatim).
- K-EXAONE-236B-A23B: custom license ("k-exaone"); text not read. Commercial limits reported by press only. https://huggingface.co/LGAI-EXAONE/K-EXAONE-236B-A23B
- K-EXAONE 2.0 (750B-A37B): reported as Apache 2.0 by multiple press articles (third-party); LICENSE file not opened. Example: https://dev.to/breachprotocol/lg-shipped-a-750-billion-parameter-model-and-quietly-dropped-its-restrictive-licence-39b1
- **Implication:** Aeon may ship an EXAONE *adapter* but must not bundle weights. Users with commercial use cannot use NC-licensed EXAONE models. 750B is not self-hostable on free infrastructure.

## 2. Cloudflare (free plan)

| Item | Value | Source |
|---|---|---|
| Requests | 100,000/day; over limit returns Error 1027 | https://developers.cloudflare.com/workers/platform/limits/ (official) |
| CPU time | 10 ms per HTTP request and per Cron Trigger | same |
| Memory | 128 MB per isolate | same |
| Subrequests | 50 per invocation (1,000 to internal services) | same |
| Wall-clock | no limit for HTTP; Cron Triggers capped at 15 min | same |
| Cron Triggers | 5 per account | same |
| D1 | 5M rows read/day, 100k rows written/day, 5 GB, 10 DBs; over limit = errors, not billing | https://developers.cloudflare.com/d1/platform/pricing/ (official) |
| Durable Objects | SQLite-backed only on Free; 100k requests/day, 13,000 GB-s/day, 5M reads, 100k writes/day, 5 GB; over limit = errors | https://developers.cloudflare.com/durable-objects/platform/pricing/ (official) |
| Workers AI | 10,000 Neurons/day free; beyond that requires paid plan; no EXAONE in model list; some models paid-only | https://developers.cloudflare.com/workers-ai/platform/pricing/ (official) |

Notes: the 10 ms limit is CPU time; awaiting an LLM response is not CPU time (to be confirmed by test). Practical constraints are the 15 min cron cap, 50 subrequests, 5 crons, daily write caps.
**Unverified:** Workflows on free plan; any OAuth path for third-party deploy tools. Third-party sources (not official) suggest an API token with `Account / Workers Scripts / Edit` and `Account / D1 / Edit` is the minimal set for Worker + D1 deploys: https://ivanli-cn.github.io/KaisouMail/cloudflare-token-permissions.html . Verify against official token docs in Phase 2.

## 3. Oracle Cloud Always Free

| Item | Value | Source |
|---|---|---|
| Ampere A1 | **2 OCPU / 12 GB total** across up to 2 instances (not the 4/24 sometimes quoted) | https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm (official) |
| AMD micro | up to 2 × (1/8 OCPU, 1 GB) | same |
| Storage | 200 GB combined block/boot; home region only | same |
| Idle reclamation | instance may be reclaimed if over 7 days p95 CPU <20%, network <20%, (A1) memory <20% | same |
| A1 availability | not in South Korea North (Chuncheon) | same |
| Capacity | "out of host capacity" errors possible | same |
| Signup | official sign-up guide: payment verification card required (credit card; PIN debit/prepaid/virtual not accepted); temporary hold; not charged unless upgraded | https://docs.cloud.oracle.com/iaas/Content/GSG/Tasks/signingup_topic-Sign_Up_for_Free_Oracle_Cloud_Promotion.htm , https://www.oracle.com/cloud/free/faq/ (official, via search summary) |

**Implication:** Oracle needs a card (pre-approval category under the directive), is at risk of idle reclamation for a mostly idle personal agent, and may hit capacity errors. Report these risks; do not simulate load to avoid reclamation.

## 4. Hermes Agent (competitor, minimal check)
Official docs and secondary sources indicate: MIT license, built-in cron with delivery to platforms, local/Docker/SSH terminal backends, persistent memory and session search, skills (agentskills.io). Source: https://hermes-agent.nousresearch.com/docs (official). Full feature comparison deferred to `docs/COMPETITOR_ANALYSIS.md`; claims there must be verified individually.

## 5. Open items (not yet verified)
- Any currently available hosted EXAONE endpoint (and price); whether K-EXAONE 2.0 Apache-2.0 applies as reported.
- Cloudflare Workflows on free plan; Cloudflare third-party OAuth for deploys.
- Free hosted LLM APIs (e.g. Google, OpenRouter free variants): terms and data-use policy.
- Whether LLM wait time is excluded from the 10 ms CPU limit in practice (needs a real or workerd test).
