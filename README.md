# Aeon Agent

An open-source personal AI agent built around four ideas: **continuity** (work survives restarts and, later, moves between your cloud and your devices), **ownership** (your data, keys, models, and runtime), **verifiability** (a task is only "verified" if the real world confirms it), and **accessibility** (no cloud expertise required).

> **Status: Phase 1 (Agent Core).** Automated tests use mocks; real EXAONE calls and two real end-to-end tasks were also run manually and succeeded. Single-machine runtime only. Cloud runtimes, device continuity, memory, UI and the Android app are planned (see [ROADMAP](docs/ROADMAP.md)) and **do not exist yet**.
> Every model interaction in the automated tests is mocked; see [TESTING](docs/TESTING.md) for exactly what was verified for real.

## What works today

- Persistent tasks in SQLite with leases + fencing tokens: a task killed mid-run is picked up again from its last checkpoint by a new process; two processes cannot both run it.
- Plan → act loop driven by a model, one checkpoint per step, retries with backoff, cancel, scheduled start time (`--at`).
- Deny-by-default policy; writes confined to the workspace; deletes, network writes and command execution need your approval (approvals expire after 24 h and only authorise the exact action shown).
- Command execution only inside a network-less Docker container.
- Completion is checked against reality (files, HTTP) – a model saying "done" is not evidence. Results are reported as `verified`, `failed`, `unverified` or `user_confirmed`.
- Model providers: K-EXAONE 2.5 via the official EXAONE API Platform, Ollama, llama.cpp server, any OpenAI-compatible API. Rate limits / quotas put the task into a waiting state instead of failing it.
- API keys live in the platform secret store (or environment variables) and are redacted from logs, errors, state and prompts.

## Requirements

Node.js 24+ (uses built-in `node:sqlite` and TypeScript type stripping). Docker is only needed for `sandbox.exec`, which is postponed and unverified against a real daemon.

```bash
npm install
npm test
```

## Quick start (local model, no key)

```bash
# Ollama must be running with a model that can follow JSON instructions
OLLAMA_MODEL=<your-model> node src/node/cli.ts run "create notes.txt containing hello" \
  --providers ollama --check file_contains:notes.txt:hello
```

Very small models (for example a 135M-parameter test model) cannot drive the agent loop; expect the task to end in `RETRY_WAIT`/`FAILED`.

## Keys and settings (decision D11)

Keys are never stored in the repository, the package or the task database. Put them in the platform store:

```bash
node src/node/cli.ts secrets set EXAONE_API_KEY      # hidden prompt, or pipe the value on stdin; never use argv
node src/node/cli.ts config set providers=exaone consent=exaone
node src/node/cli.ts secrets list                    # names only
```

| Platform | Where secrets live | Notes |
|---|---|---|
| Windows | `%APPDATA%\Aeon\secrets`, encrypted with DPAPI for your Windows user | verified on a real Windows 11 machine |
| Linux | Secret Service (`secret-tool`), else a `0600` file in `~/.config/aeon` (not encrypted) | verified with a fake runner only |
| macOS | environment variables only (CLI only, no UI yet) | no keychain integration |
| Android (planned, Phase 6) | app-private storage + Android Keystore key; nothing in the APK | not built yet |

Environment variables always override stored values. Settings (providers, consent, fallback) live in `config.json` beside them and are edited by the future UI.

## Quick start (EXAONE)

```bash
node src/node/cli.ts secrets set EXAONE_API_KEY   # keys expire every 30 days per the API terms
node src/node/cli.ts run "summarise README.md into summary.txt" \
  --providers exaone --consent exaone --check file_exists:summary.txt
```

If you keep the key in a `.env` file (git-ignored), run the CLI with Node's loader: `node --env-file=.env src/node/cli.ts ...` (Aeon does not read `.env` by itself).

`--consent exaone` states that you allow your prompts (and file excerpts the agent reads) to be sent to LG AI Research's service. Without it Aeon refuses to call any external provider.

> **EXAONE terms apply to you.** Aeon ships the EXAONE client without any key (bring your own). The EXAONE API terms grant non-commercial rights, forbid sharing keys and letting third parties call the model through your product, and do not specifically address open-source BYOK apps (owner decision D9: published anyway). Read the [terms](https://docs.exaone.ai/ko/terms/terms-of-service) before use; do not use it commercially.

## User interfaces (Kotlin)

Windows (Compose Desktop) and web (Compose for Web) UIs are built; Android is waiting for the Android SDK. See [docs/UI.md](docs/UI.md).

## Local API for the UIs

`node src/node/cli.ts serve` starts the signed, pairing-based [local API](docs/API.md) on `127.0.0.1:7421` (the Windows, web and later Android UIs use it). The server starts even before a key is configured.

## Android app with the agent inside (decision D19)

The Android app can run the agent on the phone itself (Node.js for Mobile runs the compiled core). It is built only when the runtime has been fetched:

```bash
node tools/fetch-nodejs-mobile.mjs      # downloads nodejs-mobile v18.20.4 (about 57 MB) from its GitHub release
cd ui && ./gradlew :androidApp:assembleDebug
```

This needs the Android NDK (29.0.14206865) and Node 24 on the build machine. Without the fetch step the app is the same remote control as before and offers only "내 컴퓨터에 연결". Verified on the x86_64 emulator, not yet on a real ARM phone.

## Skills (decision D20)

Skills follow the Agent Skills specification. The agent makes them (you approve each), or you install one from GitHub or a folder through the API (`POST /v1/skill-sources/inspect`, then `/install`; see docs/API.md). Skills live per owner (`<config dir>/skills`) or per project (`<workspace>/.agents/skills`), and the agent only uses ones you approved (`skills-lock.json`). Scripts run only after you read them and approve each run: in a Docker container by default, or on the computer itself when asked (JavaScript under Node's permission model; Python and shell with no isolation, and the approval card says so).

## Commands

`run`, `tick`, `list`, `show <id>`, `approvals`, `approve <id>`, `deny <id>`, `cancel <id>`, `verify <id>`, `confirm <id>`, `usage` – see `node src/node/cli.ts --help`. State lives in `<workspace>/.aeon/aeon.db`.

## Uninstall

Delete the workspace's `.aeon/` folder and this repository folder. Phase 1 creates nothing outside them (no cloud resources, no system settings).

## Docs

[ARCHITECTURE](docs/ARCHITECTURE.md) · [DECISIONS](docs/DECISIONS.md) · [TESTING](docs/TESTING.md) · [THREAT_MODEL](docs/THREAT_MODEL.md) · [CLOUD_PROVIDERS](docs/CLOUD_PROVIDERS.md) · [ROADMAP](docs/ROADMAP.md) · [SECURITY](SECURITY.md) · [CONTRIBUTING](CONTRIBUTING.md) · [한국어 README](README.ko.md)

License: Apache-2.0.
