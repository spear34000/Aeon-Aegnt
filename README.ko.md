# Aeon Agent (한국어)

연속성(재시작·장애 후에도 작업 유지), 소유권(데이터·키·모델·실행환경은 사용자 소유), 검증 가능성(실제 결과로 완료를 확인), 접근성(클라우드 지식 없이 사용)을 목표로 하는 오픈소스 개인 AI 에이전트입니다.

> **현재 상태: Phase 1(Agent Core), Mock 검증 단계.** 단일 PC 런타임만 있습니다. 클라우드 런타임, 기기 간 이어가기, 기억 관리, UI, 안드로이드 앱은 아직 없습니다. 자동 테스트의 모델 응답은 모두 Mock이며, 실제 모델 검증은 아직 끝나지 않았습니다.

## 지금 되는 것

- SQLite에 작업 상태 저장, lease + fencing token. 작업 도중 프로세스가 죽어도 새 프로세스가 마지막 체크포인트부터 이어서 실행하고, 두 프로세스가 같은 작업을 동시에 실행하지 못합니다.
- 계획 → 실행 반복, 단계마다 체크포인트, 지수 백오프 재시도, 취소, 예약 시작(`--at`).
- 기본 거부 정책. 파일 쓰기는 작업 폴더 안으로 제한, 삭제·네트워크 쓰기·명령 실행은 사용자 승인 필요(승인은 24시간 후 만료, 화면에 보여준 그 동작에만 유효).
- 명령 실행은 네트워크가 없는 Docker 컨테이너 안에서만 가능.
- 완료 여부는 실제 파일·HTTP 상태로 확인합니다. 결과는 `verified` / `failed` / `unverified` / `user_confirmed`로 구분됩니다.
- 모델: 공식 EXAONE API Platform의 K-EXAONE 2.5, Ollama, llama.cpp 서버, OpenAI 호환 API. 호출 제한에 걸리면 실패 처리하지 않고 대기합니다.
- API 키는 환경 변수로만 받고, 로그·오류·저장 상태·프롬프트에서 가려집니다.

## 설치와 테스트

Node.js 24 이상이 필요합니다. Docker는 `sandbox.exec`를 쓸 때만 필요합니다.

```bash
npm install
npm test
```

## EXAONE 사용

```bash
export EXAONE_API_KEY=...   # 저장소에 커밋하지 마세요. 약관상 키는 발급 30일 후 만료됩니다.
node src/node/cli.ts run "README.md를 요약해서 summary.txt로 저장" --providers exaone --consent exaone --check file_exists:summary.txt
```

`--consent exaone`은 프롬프트(에이전트가 읽은 파일 일부 포함)가 LG AI연구원 서비스로 전송되는 것을 허용한다는 뜻입니다. 이 옵션이 없으면 외부 제공업체를 호출하지 않습니다.

> **EXAONE 약관은 사용자에게 적용됩니다.** Aeon은 키 없이 EXAONE 클라이언트만 배포합니다(각자 키 사용). EXAONE API 약관은 비상업 이용만 허용하고, 키 공유와 제3자가 제품을 통해 모델을 호출하는 것을 금지하며, 오픈소스 BYOK 앱은 따로 다루지 않습니다(소유자 결정 D9: 그대로 공개). 사용 전에 [약관](https://docs.exaone.ai/ko/terms/terms-of-service)을 읽고 상업적으로 쓰지 마세요.

## 제거

작업 폴더의 `.aeon/`과 저장소 폴더를 삭제하면 됩니다. Phase 1은 그 밖의 곳에 아무것도 만들지 않습니다.

자세한 내용: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/TESTING.md](docs/TESTING.md), [docs/DECISIONS.md](docs/DECISIONS.md)
