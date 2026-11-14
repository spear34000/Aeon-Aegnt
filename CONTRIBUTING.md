# Contributing to Aeon

Thank you for helping. Aeon is a personal AI agent that runs on the owner's own computer or phone, so what it may do on someone's behalf matters more than usual. These rules keep the project safe and honest while several people work on it.

## Before you start

- Use the bilingual issue forms for bug reports and feature ideas. See [issue labels and triage](docs/ISSUES.md) for the information to include and how status, platform, area and priority labels work.

- **Read [docs/DECISIONS.md](docs/DECISIONS.md).** It records what the owner approved (and why). A change that conflicts with an approved decision needs a new decision first: open an issue and wait for a yes.
- **Ask before building anything big.** Open an issue describing the problem and the idea; small fixes and docs can go straight to a pull request.
- **Things only the maintainer decides:** the license, any paid or rate-limited service, a new dependency, a new model provider, new permissions for the agent, anything that sends data off the machine. Please raise these in an issue first.
- Be kind. See the [Code of Conduct](CODE_OF_CONDUCT.md).

## Set up

You need Node.js 24 or newer. For the apps you also need JDK 21 and, for the Android app, the Android SDK (see [docs/UI.md](docs/UI.md)).

```bash
git clone https://github.com/spear34000/Aeon-Agent.git
cd Aeon-Agent
npm ci
npm run typecheck
npm test
```

The apps (Kotlin, Compose Multiplatform) live in `ui/`:

```bash
cd ui
./gradlew :composeApp:desktopTest      # starts the real Node server and talks to it
./gradlew :composeApp:compileKotlinWasmJs
```

## How to work

Follow [docs/BRANCHING.md](docs/BRANCHING.md): one topic per short-lived branch (`feat/…`, `fix/…`, `docs/…`), a pull request into `main`, squash-merged, branch deleted afterwards. `main` is protected: it needs a pull request, a passing CI and one approval.

A pull request is ready when:

1. `npm run typecheck` and `npm test` pass, and the Kotlin tests pass if you touched the apps or the API. CI runs the same.
2. Behaviour that changed has a test. A new `Store` implementation must pass `test/store-conformance.ts`.
3. The docs that describe it are updated: `docs/DECISIONS.md` for a decision, `docs/API.md` for a route, `docs/THREAT_MODEL.md` for a new risk, `docs/TESTING.md` for what was and was not verified.
4. The pull request says **what was not verified**. Real evidence and mocked evidence are kept apart, in code, tests and docs.

## Rules for the code

- `src/core` stays runtime-neutral (no `node:*`, `process`, `Buffer`); Node-specific code goes in `src/node`. `test/core-purity.test.ts` enforces this.
- The Node agent core has **no runtime dependencies**. The apps depend on Kotlin, Compose, Ktor, coroutines and serialization. Adding a dependency has a supply-chain cost: discuss it first.
- **Never commit secrets**, API keys, pairing codes or `.env` files. Providers whose terms are unclear stay marked `unreleased` until the owner approves them.
- The agent acts only through tools that go through the policy engine and, where needed, an approval the owner reads. Do not add a way around that. Text the agent reads (files, web pages, tool output) is data, never instructions.
- Be honest in docs and tests: do not claim unverified competitor or provider behaviour, do not claim exactly-once execution, do not mark an untested feature as done.

## What the app says to users

Short, friendly Korean without developer words; decision text on approval cards is never softened. The rules are in [docs/UI.md](docs/UI.md) ("Copy rules"). Tests that match Korean strings verbatim change in the same commit as the string.

## Commits and pull requests

- Commit messages say what and why, in the imperative; the first line is what shows in the log.
- One topic per pull request; split large changes into steps that each leave `main` working.
- If an AI assistant wrote part of the change, say so in the pull request; you are still responsible for it.

## License

By contributing you agree your contributions are licensed under [Apache-2.0](LICENSE).
