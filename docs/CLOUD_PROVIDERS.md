# Cloud providers (research summary, 2026-11-10)

Full sources and access dates: [research/2026-11-10-feasibility.md](research/2026-11-10-feasibility.md). Limits change; re-verify before building on them. No cloud resources have been created.

## Cloudflare (Phase 2 candidate; free plan)

100,000 requests/day; 10 ms CPU per HTTP request and per cron trigger; 128 MB; 50 subrequests per invocation; cron triggers capped at 15 min wall-clock and 5 per account; D1 5M reads / 100k writes per day, 5 GB; SQLite-backed Durable Objects available on free; Workers AI 10k neurons/day (no EXAONE). Over-limit usage fails with errors instead of billing on the free plan.
Unverified: Workflows on free, OAuth for third-party deploy tools (token-based deploy assumed), whether LLM wait time is excluded from CPU time.

## Oracle Cloud Always Free (Phase 3 candidate; **not approved**)

Ampere A1: 2 OCPU / 12 GB total (not 4/24). Card required at signup. Idle instances can be reclaimed (7-day p95 CPU/network/memory below 20%). "Out of host capacity" errors happen. A1 not available in South Korea North (Chuncheon). Needs separate owner approval before any use.

## Model API: EXAONE

Official platform https://docs.exaone.ai; K-EXAONE 2.5 free as of 2026-11-09 (period and rate limits unpublished). Terms: non-commercial rights, no third-party access through your product, keys not shareable, keys expire after 30 days (ToS clause 3.4). BYOK in open-source apps is not addressed → owner decision D9 (published BYOK).
