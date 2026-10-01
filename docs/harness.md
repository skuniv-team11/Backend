# 하네스

사람이든 AI 코딩 도구든 **같은 규칙을 같은 방식으로 지키게** 만드는 장치들이다. 원칙은 세 가지다.
1. 글로 쓴 규칙보다 **코드로 막는 규칙**을 우선한다(훅·테스트·CI).
2. 막을 때는 **어떻게 고치는지**를 같이 출력한다.
3. 규칙은 실제로 겪은 문제에서 만들고, 각 규칙이 **왜 있는지**를 여기 남긴다. 이유가 사라지면 지운다.

## 구성
| 장치 | 위치 | 역할 |
|---|---|---|
| 목차 | `AGENTS.md`(루트·`pipeline/`) | 어디를 봐야 하는지, Never / Ask first / Always. 100줄 안쪽 유지 |
| 단일 검증 | `scripts/verify.sh` | 바뀐 영역만 골라 검사. 사람·도구·CI가 같은 명령을 쓴다 |
| 커밋 전 검사 | `.githooks/pre-commit` → `scripts/check-secrets.sh` | 키·원본 문서·.env, 루트 숨김 파일·문서(허용 목록 밖) 차단 |
| 커밋 메시지 검사 | `.githooks/commit-msg` → `scripts/check-commit-msg.sh` | 공동 작성자·링크 트레일러, 생성 도구 표기 줄 차단 |
| 커밋 작성자 검사 | CI `commits` → `scripts/check-commits.sh`, `.github/authors` | PR 커밋 작성자가 GitHub 계정에 연결된 팀원인지 |
| 스키마 검사 | `scripts/check_pipeline.py` | 구조화 출력 스키마 제약 위반 차단 |
| API 계약 스펙 | `scripts/build_openapi.py --check`(verify.sh) | Swagger 계약 스펙이 `docs/api`와 같은지, 예시 JSON이 스키마(타입·null·코드값·범위)에 맞는지 |
| 규칙 테스트 | `src/test/.../StructuredOutputRulesTest` | 구조화 출력 enum에 `@JsonProperty` 금지 |
| 계약 대조 테스트 | `src/test/.../contract/ContractTest`, `Contract.assertSameShape` | 구현한 `/api/**`가 계약에 있고 권한(`@PublicApi`·`@RequireRole`)이 같은지, 오류 코드·HTTP 상태가 같은지, 응답이 계약 예시와 같은 모양인지 |
| 하네스 자체 테스트 | `scripts/test-harness.sh` | 막아야 할 것은 막고(양성), 멀쩡한 것은 통과(음성)하는지 |
| CI | `.github/workflows/ci.yml` | 위 검사 전부. 브랜치 보호에는 `ci-ok` 하나만 필수 |
| 프론트 저장소 | `skuniv-team11/Frontend` | 같은 비밀·커밋 검사·하네스 테스트 + lint·build |
| 결정 기록 | `docs/decisions/` | 규칙·구조의 이유와 다시 볼 시점 |

## 규칙별 이유
| 규칙 | 겪은 문제·근거 | 다시 볼 시점 |
|---|---|---|
| 원본 문서·키 커밋 금지 | 공개 저장소. 드라이브 공유 범위는 서경대 도메인 한정, 매칭 엑셀에 개인정보 | 저장소를 비공개로 바꾸면 완화 검토 |
| 구조화 출력 enum은 한글 상수 | SDK 2.66.0 스키마 생성기가 `@JsonProperty`를 무시 → 모델 값과 역직렬화 값 불일치 (ADR-0003) | SDK가 `@JsonProperty`를 지원하면 |
| 스키마에 null·anyOf·pattern 금지 | 선택 속성 24개·유니언 16개 한도, 숫자·문자 제약 미지원 (ADR-0003) | API 제약이 바뀌면 |
| 외부 API 실행 중 호출은 Claude·임베딩·카카오 대중교통만 | 시연 중 외부 장애 방지, ODsay 결과 저장에 사전 동의 필요 (ADR-0002) | 운영 전환 시 |
| 카카오 결과 저장 금지 | 카카오 운영정책이 결과·가공 데이터의 DB·캐시 저장과 미리 조회 보관을 금지 (ADR-0007) | 카카오 정책이 바뀌면 |
| `main`만 배포 | Render 빌드 월 500분 | 유료 플랜 전환 시 |
| `/api/**`는 기본이 로그인 필요, 권한은 계약과 대조 | 공개 표시를 빠뜨리면 막히고(안전한 쪽), 잘못 붙이면 `ContractTest`가 잡는다. 인증을 직접 짠 대가로 테스트가 지킨다 (ADR-0013) | Spring Security로 옮길 때 |
| Swagger 계약 스펙은 생성·검사 | README·예시 JSON·Swagger 셋을 손으로 맞추면 갈라진다. 프론트는 구현 전에 24개 전부를 봐야 한다 (ADR-0011) | 컨트롤러가 다 구현되면 |
| 커밋 작성자 1명·도구 표기 금지 | 기여 내역이 팀원별 역할의 근거. 연결 안 된 이메일은 기여로 안 잡힘. 도구가 기본으로 붙이는 줄은 사람이 놓치기 쉬움 (ADR-0006) | 공모전 종료 후 |
| 루트 숨김 파일·문서 허용 목록 | 개인 편집기·도구 설정, 개인 메모가 공개 저장소에 섞임 방지 (ADR-0006) | 팀 공용 설정이 필요해지면 목록에 추가 |

## 규칙을 추가하는 법
1. 같은 실수가 두 번 나오면 규칙 후보다. 한 번 나온 것은 PR 코멘트로 끝낸다.
2. 가능하면 스크립트·테스트로 만든다. 글로만 남길 수밖에 없으면 해당 폴더 `AGENTS.md`에 한 줄로 적는다.
3. `scripts/test-harness.sh`에 막아야 할 예와 통과해야 할 예를 하나씩 추가한다.
4. 위 표에 이유와 다시 볼 시점을 적는다.
