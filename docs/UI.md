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

Visual system (changed 2026-11-11, mockup "Aeon 모바일" with light and dark boards): neutral surfaces with one blue accent. Page `#F4F4F6` / `#0B0B0D`, cards `#FFFFFF` / `#17171A`, soft fills `#E9E9ED` / `#242428`, accent `#2F5BEA` / `#86A2FF`. Amber means waiting for the owner, red means it cannot be undone, green means checked. Cards have a 20dp radius and no border, sheets 28dp, buttons are pills (48dp). Tokens live in `design/Theme.kt`, outline icons in `design/Icons.kt`. The theme follows the system and can be forced from 내 에이전트 › 화면 테마 (stored on the device).

둘러보기 lists connections from the server (`/v1/connections`, `/v1/catalog`, see [API.md](API.md)): kind filters (전체, 연동, MCP 서버, 스킬, 기본 도구), "연결됨", and "추가할 수 있음" with collection chips and a name search. Logos come from `/v1/icons`; a row without one shows its first letter. A bottom sheet shows the address, required key names and what adding does.

Built from the mockup so far: theme tokens, buttons, cards, the phone top bars, bottom bar, drawer, 피드, 둘러보기 and 내 에이전트. Not yet rebuilt to the mockup (they only pick up the new colours and shapes): the conversation (progress card, inline approval), the approval sheet, 작업 and task detail, pairing, and the wide layout.

Composer: `+` uploads a file (stored under `uploads/` in the workspace, the message names its path), a model button shows provider, model and reasoning effort and opens a sheet (providers from the server catalogue, key entry, effort, consent, model list), and the blue arrow sends. 내 에이전트 › 개인화 sets what the agent calls the owner, tone, length and language; 기기 lists every paired device, makes a pairing code for another one and sets who may change settings. Clients: Android (phone), Windows x64 and ARM64 and Linux (Compose Desktop, jpackage builds only for the machine it runs on), web. iOS is out of scope.

First run (all platforms, state in `state/Setup.kt`, screens in `screens/Setup.kt`, `Coach.kt`, `Legal.kt`): splash → 웰컴 (the title page: mark, slogan, 시작하기) → 앱 소개 (4 pages, swipe, skip) → 권한과 약관 → 어디서 실행할까요? (이 기기 / 내 컴퓨터) → 서버 연결 (a guide for another device first, then the pairing form) → AI 고르기 (only when the agent has no model) → 코치마크 on the real main screen → 프로필 (5 steps, every field optional) → main screen.

- It runs once. Progress, the choices and the profile being typed are written to the device's key-value store as they change, so closing the app resumes at the same step. A device paired before this flow existed goes straight to the main screen. If the store cannot be written the flow continues in memory and the terms screen says so.
- The splash is shown on every start for as long as restoring state takes (about a second at least). Android 12+ shows its own launch screen first with the same mark.
- 권한: Android asks for notifications (with its reason; after "don't ask again" the button opens the system settings). Desktop and web have no runtime permission and say so. Files are chosen with the system picker, which needs none.
- 약관: no 필수/선택 tags. A "전체 동의" row, then two groups: the two notices that must be accepted to continue (이용 안내, 데이터 처리 안내; "동의하고 계속" stays disabled until both are checked) and two consents with a real effect that may stay off: 온라인 목록·로고 조회 (server setting `onlineLookups`, applied by a device that may change settings) and 백그라운드 승인 확인 (Android only, per device: a JobScheduler job about every 15 minutes that posts "승인이 필요한 일 N건" and opens 피드 when tapped). Declining a required notice stops there; taking it back later in the settings returns to this screen without deleting anything.
- 코치마크: six steps that dim everything except the real control (composer, attach, model, 피드, 둘러보기, 내 에이전트). Controls register their position with `Modifier.coachTarget`; a control that is not on screen is skipped. Taps do not reach the screen underneath.
- 프로필 (five steps with a segmented progress bar, like a social app's sign-up): 이름 (what the agent calls the owner and the agent's name, with the agent's mark, name and greeting changing as they type), 관심사 (27 square tiles with an icon each, in four groups 일 / 생활 / 취미 / 지식, a counter, up to 12, plus free entries shown as pills; the label is what is saved; icons are Material outlined icons, Apache-2.0, copied into `design/InterestIcons.kt`), 말투 (language, 존댓말 or 반말 as two cards with a sample line, answer length, live preview), 알아 둘 것 (free notes up to 1000 characters with starter chips: 직업, 사는 곳, 쓰는 도구, 피하고 싶은 것) and a summary where each row has 수정 to jump back. On "시작하기" it is saved to the server (`config.profile`), which rebuilds the runtime so the next task's system prompt carries it. If the server cannot be reached the owner can retry or continue; the profile is then sent automatically after the next successful poll. A new kind of information is a new step in `ProfileSetupScreen` plus a new field in `Profile` (and in `config.profile` on the server); nothing else changes.
- Everything can be changed again: 내 에이전트 › 개인화 (profile) and › 약관·권한 (permissions, optional consents, withdraw, replay the intro or the coach marks).
- System back (Android) goes one step back inside the flow, closes the drawer, a sheet or the task detail on the main screen, and otherwise leaves the app. Light haptic ticks mark page changes and choices; a stronger one marks finishing a step.

The conversation body (greeting by name, suggestions, progress card with one line per action, inline approval card with 거부 as the strong button), 작업, task detail and the pairing screen follow the mockup. Still the old layout, with only the new colours and wording: the settings pages for usage and info, and the wide layout (Windows, web at 1100dp and up).

**Simplification pass (2026-11-13, from the owner's design feedback; mockup boards updated to match).** Nothing in the real risk judgement or verification was removed; only what is shown changed.

- First run: the splash is the mark and a progress bar only; the welcome screen is the mark, one line and one button (no "intro again" link) and comes first, before the intro pages, so the app does not open on a feature explanation; 권한과 약관 has no 필수/선택 tags; the coach mark for the model button names what it does and pulses a ring around it; the profile is one choice per row with a live preview.
- Wording: tasks are 진행 중, 승인 대기, 예약됨, 완료 (no more 끝남); "확인됨 / 확인 안 됨" appears only on the task detail, next to the report, not in lists. Lists show a dot or one word, not sentences. Technical terms (registry ids, key names, "서명") are gone from lists; detail views keep the facts a person needs (address, required key name).
- 작업 groups: 승인 대기, 진행 중, 예약됨, 지난 작업. The drawer rows are a status dot and the title. The detail shows the agent's report, then 확인한 것 (what was checked), then 한 일 with plain labels and 자세히 for the raw request.
- Approval card: a plain title ("파일 3개를 삭제할까요?"), where it came from and the time left, a box naming the target (file name and folder; the first few, "N개 더 보기"), "되돌릴 수 없어요" when it is irreversible, and two buttons: 거부 (strong, focused) and 허용 (red outline when irreversible, enabled after 1 s). For commands, network calls and external sends the exact tool and request are always shown; 자세히 reveals the rest for file actions. The risk grade and warning badge are gone; the server still decides what needs approval and marks irreversible actions.
- 모델 logos: provider rows and the provider page header show the provider's logo (see [API.md](API.md): fetched by the PC from models.dev, drawn in the text colour); providers without a usable logo show their first letter. "내 제공자" lists the one in use, those with a saved key and Aeon's local ones; the rest is behind "제공자 N개 더 보기".
- 모델: the list shows the model in use with its reasoning effort directly beneath it (자동 / 낮음 / 보통 / 높음, only for models that have effort), then the models of providers that have a key or run on this machine; "연결됨" is shown only for providers with a key. No key notes in the list.
- 기기: "새 기기 연결" shows a QR code (the pairing link, see [API.md](API.md)), the code in groups, a 5-minute countdown, 새 코드, and a switch for whether the new device may change settings. The device list shows the name, when it was last seen, and "대화·승인만" for devices that may not change settings.
- 둘러보기: search placeholder "도구, 연동, 스킬 검색"; a "자세히" button (was "사이트 보기"); the skills tab lists the skills the agent has made (name and description, "형식 오류" for a SKILL.md that does not follow the Agent Skills format). There is no create button for the owner: "에이전트에게 스킬 만들게 하기" opens a new conversation with the request started, the agent proposes the skill, and an approval card ("스킬을 만들까요?") shows its name, what it is for, its files and, behind "지침 보기", the full text. A row opens a page to read it, correct the description and instructions, or delete it (D17).
- 앱 소개 (2026-11-13, second round): the title and one sentence are at the top, left-aligned and large; under them a tinted panel fills the rest of the screen with a cut-off excerpt of the real screen (task detail, approval card, device list, model list), like a store screenshot. The new-device dialog's countdown is a draining bar as well as the numbers.
- 이 기기에서 실행 (the agent starting inside the app): five steps (파일 준비, 에이전트 켜기, 응답 기다리기, 연결 코드 받기, 이 기기와 연결) each shown as done, in progress, failed or waiting, and under them a log card with the seconds since the start, what Node printed, the reason Android gives when the agent's process ended, and a button that copies all of it. A stopped agent process is noticed within seconds, not after the 30 seconds of waiting.
- Where the agent runs (D19): 내 에이전트 says `이 기기에서 실행` or `다른 기기에서 실행` and the chat header `이 기기에서 실행 중` or `서버와 연결됨`, from whether the server address is this device's loopback. Phone settings pages are real pages (back button, centred title; system back returns to 내 에이전트), and 개인화 reuses the first-run profile parts.
- Closed by an error (Android): the next start opens a window "앱이 오류로 종료됐어요" with text the owner can select and copy with one button: the app and phone, the Java error saved by the handler, the reason Android gives for the last exit (this also covers native crashes, which no Java handler sees), a note when the app was on screen when it ended (a marker kept only while it is visible, so this works before Android 11 too), the agent's own error log, and the app's recent log lines. The same exit is reported once. An exit the owner asked for is not reported.
- 어디서 실행할까요? (2026-11-13, owner's requirement: each way is complete on its own). Two cards with what each means. "내 컴퓨터에 연결" leads to a guide: (1) start Aeon on the computer (Node.js 24 or later; `git clone`, `cd`, `node src/node/cli.ts serve --host 0.0.0.0`, one tap to copy), (2) the code and QR appear, valid 5 minutes, `pair` makes a new one, (3) scan the QR with this device's camera or type the address and code; "연결이 안 되나요?" answers same Wi-Fi, the address, the firewall and the code; then the address and code form. A scanned link skips the choice. "이 기기에서 바로 쓰기" is marked 준비 중 because the agent cannot run on the device yet (D19); its page says what works today and leads to the other way. When it can, the same path continues without a code or QR to the model step and the profile, all inside the app.
- AI 고르기: after connecting, if the agent has no working model, a page with the provider list (logos), key entry and model choice (the same sheet as 모델); it skips itself when the agent already has one. Not exercised against a server with no model yet.
- A pairing link scanned with the camera opens the app on the connect screen with the address and code filled in. It never connects by itself (see [API.md](API.md)).

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


## Copy rules (what the app says to an ordinary user)

Applied to old and new screens alike (2026-11-14):

1. Plain, friendly Korean (해요체). No developer words where an everyday one exists: "컴퓨터" instead of "서버", "외부 도구" instead of "MCP 서버", "격리된 상자 (Docker)" where the isolation matters.
2. One sentence says what happened or what will happen. A second line is kept only if the owner needs it to decide or to avoid a surprise (cost, privacy, what is deleted). Hints that restate the screen are removed.
3. **Text the owner decides on is not softened or shortened**: the exact command or request on an approval card, "격리 없음" and its warning, what leaves the computer, what a memory or skill will steer. Removing filler never removes decision information.
4. Errors say what the owner can do, not the status number. The server's own (English) message and "HTTP 403" are not shown; a task that stopped shows a Korean sentence for the known reasons (`friendlyError`) and, for an unknown one, a plain sentence followed by the original text.
5. Tests that match Korean strings verbatim are changed in the same commit as the string.

Not covered: the terms and data notices (Legal), which are written as agreements; the setup guide for the computer (`Where`), which has to name commands; the English summaries on approval cards, which are the exact request.
