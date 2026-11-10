# Contributing

1. Read [docs/DECISIONS.md](docs/DECISIONS.md). Architectural changes that conflict with an approved decision need a new decision first.
2. Node.js 24+. `npm install && npm test && npm run typecheck` must pass.
3. `src/core` must stay runtime-neutral (no `node:*`, `process`, `Buffer`); Node-specific code goes in `src/node`. `test/core-purity.test.ts` enforces this.
4. Every behaviour change needs a test. A new `Store` implementation must pass `test/store-conformance.ts`.
5. Be honest in docs and tests: label mocked evidence as mocked; do not claim unverified competitor or provider behaviour; do not claim exactly-once execution.
6. Never commit secrets. Do not add dependencies without discussing the supply-chain cost (the Node agent core has no runtime dependencies; the Kotlin UIs depend on Kotlin, Compose, Ktor, coroutines and serialization).
7. Never commit API keys or `.env` files. Providers whose terms are unclear must be marked `unreleased` until the owner approves them.

By contributing you agree your contributions are licensed under Apache-2.0.
