# Security policy

Aeon is pre-release software (Phase 1). Do not run it on data you cannot afford to lose, and review the [threat model](docs/THREAT_MODEL.md) first.

## Reporting a vulnerability

Please do **not** open a public issue for security problems. Until a private contact channel is published here (the maintainer has not provided one yet), report by contacting the maintainer directly through the repository host's private vulnerability reporting feature once the repository is public.

## Operating safely

- Keep API keys in environment variables; never commit `.env` files. Aeon never writes keys to disk.
- Rotate keys if you suspect exposure; the EXAONE API terms say keys expire 30 days after issue.
- Approve actions only after reading the summary and resource shown; approvals apply to that exact action.
- Prompts and file excerpts sent to an external provider leave your machine. Use `--consent` only for providers you trust, or use a local provider (Ollama / llama.cpp).
- Free-tier services can change limits or end; Aeon's local budgets are not a billing guarantee.
