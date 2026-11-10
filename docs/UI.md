# User interfaces (decision D13: Kotlin / Compose Multiplatform)

## Second implementation (design v0.1 applied, 2026-11-10)

The UI now follows the UX Pilot design export (two revisions, the second with a "design ↔ implementation" alignment page). Verified in this session:

| Target | How it was checked |
|---|---|
| Android phone (API 37 emulator) | Installed and run. Pairing with the real `serve` (16-character code, typed raw, grouped on screen), task creation, approval card (risk band, tomorrow expiry, irreversible line, allow after 1 s), approval, file really deleted, result labelled "에이전트 보고: 완료" with "확인 안 됨 · 검사 기준 없음", settings (models with save bar, consent, fallback). Screenshots taken. |
| Web (Compose for Web) | Built and served by `serve --web`, loaded in the browser pane: pairing, layouts at phone width, 1024 and 1280 (sidebar 220 + list 380 + conversation). |
| Windows (Compose Desktop) | Compiles; `desktopTest` passes (8 tests against the real server). Not re-launched after the redesign. |

Design items implemented: 4-4-4-4 pairing input (80-bit code), HTTP acknowledgement, web block screen off-localhost, five tool names, irreversible line on deletes, absolute expiry over one hour and countdown under, approval card rules (deny by default focus, allow after 1 s, same-size buttons), verification badges with words, agent report labelled as the agent's words, save bar for model settings, device list with revoke confirmation, Pretendard (Regular and SemiBold), light and dark tokens, Material icons instead of text glyphs (the web font lacks the symbols).

Phone navigation (changed 2026-11-11 at the owner's request, modelled on the Pickle app): five icon-only bottom tabs in the order 대화, 피드, 둘러보기, 작업, 내 에이전트 (spoken labels for screen readers). A hamburger at the top left slides the chat list in from the left over 70% of the width; the rest is dimmed and tapping it closes the drawer. Approvals are reached from 피드, a badge on its icon and the "승인 N" chip in the top bar. 피드 shows pending approvals and recent task activity, built from existing data (the server has no feed concept). 둘러보기 offers four static example goals that fill the composer. 내 에이전트 holds the settings. The wide layout (Windows, web at 1100dp and up) still uses the sidebar. Checked on the Android emulator; not yet on a physical phone.

Deviations (what the design asks that is not built, or was built differently):

- A conversation is one task. Multi-turn chat is not implemented; the "new conversation" composer creates a task.
- The approval card has no one-sentence reason: the server does not store the model's reasoning for an action.
- Per-call approval for every cloud model call is not implemented. Cloud use is governed by the consent switch (with a confirmation dialog). Per-call approval would block every step.
- Verification evidence uses plain labels ("파일이 있음"); the checks read the files directly, not through `fs.list` / `fs.read`.
- Provider order changes with up and down buttons, not drag.
- Anthropic and Google key rows are not shown (no connector exists for them).
- Not built: the "leave with unsaved changes" dialog, the "different model" notice toggle, approval tap-through from notifications, the "approval rules" menu (removed in the design), automatic device revocation after 30 days (removed in the design).
- The trash/undo feature was not added; the design marks deletes as irreversible, which matches the code.
- Pairing codes are 80 bits (16 characters) to match the 4-4-4-4 display, down from 95 bits.
- The Korean font is the full Pretendard OTF (about 1.6 MB per weight), not the KS X 1001 subset the design suggests; the APK grows accordingly.
- Web: Compose's fallback-font download is blocked by the page's CSP (console error only); the icons no longer need it.


All UIs share one Kotlin codebase (`ui/composeApp`): API client, request signing, state, and screens. Each platform has its own shell and security storage. They talk to the [local API](API.md); no UI contains agent logic.

| Platform | Shell | Device key storage | Status |
|---|---|---|---|
| **Windows** (Compose Desktop) | sidebar 220 + list 380 + conversation, window title shows how many approvals are waiting | ECDSA P-256 key encrypted with Windows DPAPI (current user) | **Built; launched and run on Windows 11.** Not yet packaged as an installer |
| **Web** (Compose for Web, Kotlin/Wasm) | adaptive: 1100dp and up = wide layout, below = bottom tabs | non-extractable WebCrypto key in IndexedDB | **Built and exercised in a real browser against the real server** (pairing, creating a task, wide and phone layouts) |
| **Android** (Compose) | icon-only bottom tabs 대화 · 피드 · 둘러보기 · 작업 · 내 에이전트, hamburger chat drawer | Android Keystore (hardware-backed where available) | **Built and run on an API 37 emulator** (second implementation). Not signed or published |
| macOS | – | – | CLI only (D11) |

## Build and run

Requirements: JDK 17–27 (tested with 25). The Gradle wrapper (9.9.0, checksum-pinned) downloads Gradle, Kotlin and the libraries from Maven Central on first use (several hundred MB).

```bash
cd ui
./gradlew :composeApp:run                                  # Windows/desktop app
./gradlew :composeApp:wasmJsBrowserDistribution            # web build -> composeApp/build/dist/wasmJs/productionExecutable
./gradlew :composeApp:desktopTest                          # Kotlin tests (starts the real Node server)
cd .. && node src/node/cli.ts serve --web ui/composeApp/build/dist/wasmJs/productionExecutable
```

Then open `http://127.0.0.1:7421/` (web) or start the desktop app, and enter the pairing code printed by `serve`.

## Versions (chosen 2026-11-10 from Maven Central / Gradle metadata)

Kotlin 2.4.21 · Compose Multiplatform 1.12.2 · Gradle 9.9.0 · Ktor 3.6.1 · kotlinx.coroutines 1.11.0 · kotlinx.serialization 1.12.0.

## Android prerequisites (waiting on the owner)

1. Install the Android SDK command-line tools and platform (several GB).
2. Accept Google's SDK licences yourself (`sdkmanager --licenses`); this cannot be done on your behalf.
3. Then the `androidApp` module and `androidMain` actuals (Keystore key, SharedPreferences, network security config limited to the paired host) are added and verified on an emulator or device. Until then, no Android code exists in the repository.

## Limits you should know about

- **Web over a LAN address cannot sign requests.** Browsers expose `crypto.subtle` only in secure contexts (`https`, `http://127.0.0.1`, `http://localhost`). Opening Aeon's web UI from a phone via `http://192.168.x.x` therefore fails with a clear message. Phones should use the Android app; remote web access arrives with HTTPS in Phase 2.
- **The web UI is canvas-rendered.** Compose for Web exposes no DOM accessibility tree today, so screen readers cannot read it and browser find-in-page does not work. This is a real accessibility cost of choosing Compose for Web (D13).
- **Korean (and other CJK) text needs a bundled font.** The default Compose font has no CJK glyphs; the Korean translation (D8) will require shipping a Korean font (several MB). Dates are formatted numerically for the same reason.
- **Bundle size:** the web build is about 15 MB (two Wasm files plus a source map); first load is slower than a typical web page.
- **Desktop secrets/keys:** on non-Windows desktops the device key is a file created with owner-only permissions (not encrypted).
- UI dependencies (Kotlin, Compose, Ktor, coroutines, serialization) are third-party and increase the supply-chain surface; the Node agent core still has zero runtime dependencies.
