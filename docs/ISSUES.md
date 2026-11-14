# Issue forms and labels

Bug reports and feature requests accept English and Korean. Report security vulnerabilities through the [private security form](https://github.com/spear34000/Aeon-Agent/security/advisories/new), never a public issue. Do not attach API keys, pairing codes, prompts, memory contents or private files.

## Reporting a bug

Include expected and actual behaviour, steps to reproduce, the app version or commit, OS/device/browser version, and the execution environment. Node version is optional and applies to a separately installed server/CLI. Provider/model names and redacted logs or screenshots are optional. There is no required diagnostics export in Aeon.

## Label groups

The versioned source is [`.github/labels.json`](../.github/labels.json). Existing labels are retained; no labels are deleted or renamed by automation.

| Group | Labels | Who sets them |
|---|---|---|
| Type | `bug`, `enhancement`, `question`, `documentation`, `dependencies` | Forms, Dependabot or maintainer |
| Status | `status/needs-triage`, `status/needs-info`, `status/planned`, `status/in-progress`, `status/blocked`, `status/in-testing` | Initial triage is automatic; subsequent decisions are manual |
| Platform | `platform/android`, `platform/windows`, `platform/linux`, `platform/macos`, `platform/web`, `platform/cli` | Exact form selection or maintainer |
| Area | `area/core`, `area/apps`, `area/docs`, `area/providers`, `area/tools`, `area/memory`, `area/skills`, `area/connections`, `area/feed`, `area/routines`, `area/ci` | Exact form selection or maintainer |
| Priority | `priority/high`, `priority/normal`, `priority/low` | Maintainer only; not assigned automatically |
| Review/help | Existing `accessibility`, `security`, `decision-needed`, `needs-test`, `good first issue`, `help wanted`, etc. | Maintainer |

`platform/*` describes the reported environment; `area/*` can cover several components. macOS means CLI only. Priorities reflect a maintainer's assessment, not the reporter's wording. Use `decision-needed` for a new dependency, paid service, model provider, permission or external data transfer before implementation.

## Automatic triage

- On issue creation/reopening, or another supported event, an open issue without a workflow status receives `status/needs-triage`.
- Exact environment/area selections add the mapped labels. Free text and unknown or duplicated form sections do not drive classification. Old reports using the previous form still enter triage but need manual component labels.
- Form labels are **additive**. Changing a form answer can add a new component/platform; a maintainer removes obsolete labels. This preserves labels added by a person. Selecting "not sure" does not guess a component.
- When a maintainer adds a `status/*` label, other managed status labels are removed. Use one status at a time. Removing the only status manually leaves it unset until a supported event occurs.
- Closing removes managed workflow statuses while retaining type, platform, area and priority. GitHub's open/closed state is the completion marker; there is no redundant closed label. Reopening without a status returns to triage.
- Pull requests are excluded. The workflow never comments, locks, edits titles/bodies, closes issues, guesses priority, or restores deleted text. It checks current issue state before processing queued events.

The workflow serializes runs per issue. GitHub may replace pending runs during a burst of events; the next run reads current state. Bot-token changes do not trigger a second workflow run, so status normalization is performed within the same run.

## Maintaining the setup

Edit `.github/labels.json` and keep form headings/choices in sync with its `fields` mapping. Pushing the manifest or sync implementation to `main` runs **Sync repository labels**; it can also be started manually from Actions. It creates missing labels and updates managed colours/descriptions, retaining all unlisted labels. Triage also creates missing labels it needs.

The issue workflow loads trusted code from the default branch, with `contents: read` and `issues: write` only, and does not check out code from contributors' branches. No extra secrets or third-party services are needed. CI runs `node --test .github/scripts/issue-management.test.cjs` against a mocked GitHub API. Local tests cover classification and lifecycle behaviour; an actual issue event can only exercise the installed workflow after it reaches the default branch.

## 한국어 안내

버그 신고에는 재현 방법, 예상한 동작과 실제 동작, 버전, OS·기기를 적어 주세요. 실행 환경과 기능 영역을 선택하면 라벨이 붙습니다. 로그·화면 첨부는 선택이며, 비밀키·연결 코드·대화·메모리·개인 파일은 제외해 주세요.

관리자는 `status/*` 중 하나로 진행 상태를 정하고, `priority/*`로 우선순위를 정합니다. 신고 내용을 수정해도 사람이 붙인 라벨을 보존하기 위해 기존 영역·환경 라벨은 자동 삭제하지 않습니다. 이슈를 닫으면 진행 상태 라벨만 제거하고, 다시 열면 분류 대기로 돌아갑니다. 자동 댓글·잠금·본문 복원·스팸 판정은 하지 않습니다.
