# Roadmap

Full scope of the master directive is retained (D4). Order is sequential.

| Phase | Scope | State |
|---|---|---|
| 0 Design & decisions | Decisions D1–D8 approved; D9 decided (publish, BYOK); D10 proposed | Done (see DECISIONS.md) |
| 1 Agent Core | Model layer, EXAONE adapter, plan/act loop, tools, persistent state, basic verification | **Implemented. Mock-verified in the suite; real EXAONE API call and two real end-to-end runs succeeded manually (2026-11-10).** Pending: automated real-model regression tests, token-cost tuning, real Docker run (postponed, D12) |
| 2 Cloud Lite (Cloudflare) | Workers/D1/Durable Objects runtime, cron, free-limit tracking, deploy prototype | Not started. Needs: confirm Workers Workflows/OAuth availability, verify LLM wait is not CPU time |
| 3 Full Runtime | Docker deployment, long jobs, browser automation, Oracle (**requires separate approval, card**) | Not started |
| 4 Cross-runtime continuity | Local runtime, device presence, task transfer, WAITING_FOR_DEVICE | Not started |
| 5 Memory & security | Provenance memory, policy UI, audit log views, secret store | Not started |
| 6 UX | Mobile-first UI / PWA, wizard, dashboard, Kotlin Android app (needs Android Studio, JDK); UI for keys/settings; Android Keystore `SecretStore`; macOS UI is explicitly out of scope for now (CLI only) | Not started |
| 7 Release readiness | Integration/security/perf tests, benchmarks vs Hermes/OpenClaw, packaging | Not started |

## Remaining directive docs (not yet written)

`DEPLOYMENT.md`, `COMPETITOR_ANALYSIS.md`, `DIFFERENTIATION.md`. They are deferred rather than filled with unverified claims: only Hermes' MIT license, built-in cron, Docker/SSH backends, persistent memory and skills were checked against its official docs so far.
