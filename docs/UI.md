# User interfaces (decision D13: Kotlin / Compose Multiplatform)

All UIs share one Kotlin codebase (`ui/composeApp`): API client, request signing, state, and screens. Each platform has its own shell and security storage. They talk to the [local API](API.md); no UI contains agent logic.

| Platform | Shell | Device key storage | Status |
|---|---|---|---|
| **Windows** (Compose Desktop) | permanent sidebar, task list + detail side by side, window title shows how many approvals are waiting | ECDSA P-256 key encrypted with Windows DPAPI (current user) | **Built; launched and run on Windows 11.** Not yet packaged as an installer |
| **Web** (Compose for Web, Kotlin/Wasm) | adaptive: wide = sidebar layout, narrow = bottom navigation + single pane | non-extractable WebCrypto key in IndexedDB | **Built and exercised in a real browser against the real server** (pairing, creating a task, wide/narrow layouts) |
| **Android** (Compose) | bottom navigation, single pane | Android Keystore (planned) | **Not built.** Needs the Android SDK and the owner's acceptance of Google's SDK licence (see below) |
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
