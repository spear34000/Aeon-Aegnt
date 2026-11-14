# Security policy

Aeon is pre-release software. Do not run it on data you cannot afford to lose, and read the [threat model](docs/THREAT_MODEL.md) first: it lists what each feature can do wrong and what is not protected.

## Reporting a vulnerability

Please do **not** open a public issue for security problems.

Report privately through GitHub: **Security tab → "Report a vulnerability"** ([direct link](https://github.com/spear34000/Aeon-Agent/security/advisories/new)). Say what you found, how to reproduce it, and what you think the impact is. Do not include real keys, pairing codes or private files.

This is a one-person-maintained open-source project: reports are read and answered on a best-effort basis, with no guaranteed response time. Fixes go to `main`; only `main` is supported.

## What counts

In scope: bypassing the approval or policy engine, escaping the workspace or the skill sandbox, getting a task to act on text it should treat as data, leaking keys or pairing codes, authenticating as a device without a valid pairing, reaching internal addresses through the server (SSRF).

Out of scope: things the threat model already lists as accepted limits (for example, a plain-HTTP connection to a LAN address is not encrypted, and the owner approving an action they did not read).

## Operating safely

- Keep API keys in environment variables or the app's secure storage; never commit `.env` files.
- Rotate keys if you suspect exposure; the EXAONE API terms say keys expire 30 days after issue.
- Approve actions only after reading the summary and resource shown; approvals apply to that exact action.
- Prompts and file excerpts sent to an external provider leave your machine. Use `--consent` only for providers you trust, or use a local provider (Ollama / llama.cpp).
- Skills and memory steer every later task: read what you accept.
- Free-tier services can change limits or end; Aeon's local budgets are not a billing guarantee.
