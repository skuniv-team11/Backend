# ADR-0003 구조화 출력 스키마 규칙

- 상태: 확정 (2026-09-30)
- 결정: 모든 필드 required, null 없음(값 없으면 ""/0), `anyOf`·type 배열·`minLength`·`pattern`·`minimum` 금지. 숫자·날짜 정규화와 검증은 코드가 한다. Java enum은 한글 상수명(`채용연계형`)을 쓰고 `@JsonProperty`를 붙이지 않는다.
- 이유: structured outputs의 선택 속성 24개·유니언 16개 한도, 숫자·문자 제약 미지원(공식 문서 2026-09). Anthropic Java SDK 2.66.0의 스키마 생성기가 enum `@JsonProperty`를 무시해 모델에는 영문 상수가 전달되고 역직렬화는 한글 값을 기대하는 불일치를 테스트로 확인.
- **문법 크기 한도**(2026-09-30 추가): 컴파일된 문법에 크기 한도가 있어 넘으면 400 `The compiled grammar is too large`가 난다. 필드 수가 아니라 스키마 전체 크기가 기준이다(`institution`만·`jobs`만은 각각 통과하는데 둘을 합치면 실패). 같은 모양으로 반복되는 잎 객체(`{value, page, quote}`)는 `$defs`+`$ref`로 모은다 — `$ref` 옆에 `description`을 나란히 두는 형태를 API가 받아들인다.
  - 운영계획서 전체 스키마(기관 20 + 직무 26필드)는 `$ref`를 써도 한도를 넘는다. **추출은 PDF 1건당 호출 2번(① 기관 + 문서 내부 불일치 ② 직무 배열)으로 나눈다**(각각 3,793 / 6,902 토큰으로 통과 확인). lite 범위는 1회로 들어가지만 여유가 거의 없어 필드를 더 못 넣는다.
  - Java(E3, 2026-10-01 확인): SDK 2.66.0의 스키마 생성기는 **반복되는 레코드를 자동으로 `$defs`+`$ref`로 묶는다**. 다만 모양이 아니라 타입 기준이라, 같은 값 집합을 쓰는 선택형은 레코드를 하나로 합쳐야 묶인다(포트폴리오·자격증 → `Requirement` 하나). 나눠 만든 두 스키마는 압축 기준 기관 3,862 / 직무 5,875바이트로, 이미 통과하던 파이썬 lite(7,169)보다 작다. `OperationPlanRequestsTest`가 `$defs` 생성 여부를 지킨다.
- 시스템 프롬프트는 파이썬(`pipeline/e1_operation_plan/prompt_system.md`)과 Java(`src/main/resources/prompts/operation-plan-system.md`)에 같은 내용으로 둔다. 한쪽만 고치면 E1과 E3가 달라진다.
- 강제: `scripts/check_pipeline.py`(스키마 규칙 + 프롬프트 동기화), `StructuredOutputRulesTest`, `PromptSyncTest`, `OperationPlanRequestsTest`(두 파트 스키마 + `$defs` 생성). 문법 크기 한도 자체는 코드로 못 막는다 — 스키마를 늘렸으면 실제로 한 번 호출해 본다.
- 파트를 알리는 문구는 시스템 프롬프트가 아니라 **사용자 메시지**에 둔다. 시스템 프롬프트는 파이썬과 바이트 단위로 같아야 해서(위 항목) 파트별로 다르게 쓸 수 없다.
- 다시 볼 때: SDK·API 제약이 바뀔 때.
