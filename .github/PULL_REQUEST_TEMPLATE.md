## What and why

<!-- One topic per pull request. What changes, and why it is worth changing. -->

## How it was checked

<!-- Name what you actually ran. Keep real runs and mocked evidence apart. -->

- [ ] `npm run typecheck` and `npm test`
- [ ] `./gradlew :composeApp:desktopTest` (if the apps or the API changed)
- [ ] Looked at by hand (say where: emulator, desktop, browser) — or: not looked at

**Not verified:** <!-- What you did not check, and what could still be wrong. Say "nothing" only if it is true. -->

## Touches

- [ ] Behaviour that needs a test — and I added it
- [ ] A decision (`docs/DECISIONS.md`), the API (`docs/API.md`) or a risk (`docs/THREAT_MODEL.md`) — and I updated it
- [ ] What the app says to users — it follows the copy rules in `docs/UI.md`
- [ ] A new dependency, a paid or rate-limited service, a new model provider, or a license question — **the maintainer must approve first**
- [ ] None of the above

By contributing I agree my work is licensed under Apache-2.0. No secrets, keys or `.env` files are included.
