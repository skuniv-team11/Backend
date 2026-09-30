# ADR-0003 구조화 출력 스키마 규칙

- 상태: 확정 (2026-09-30)
- 결정: 모든 필드 required, null 없음(값 없으면 ""/0), `anyOf`·type 배열·`minLength`·`pattern`·`minimum` 금지. 숫자·날짜 정규화와 검증은 코드가 한다. Java enum은 한글 상수명(`채용연계형`)을 쓰고 `@JsonProperty`를 붙이지 않는다.
- 이유: structured outputs의 선택 속성 24개·유니언 16개 한도, 숫자·문자 제약 미지원(공식 문서 2026-09). Anthropic Java SDK 2.66.0의 스키마 생성기가 enum `@JsonProperty`를 무시해 모델에는 영문 상수가 전달되고 역직렬화는 한글 값을 기대하는 불일치를 테스트로 확인.
- 강제: `scripts/check_pipeline.py`, `StructuredOutputRulesTest`.
- 다시 볼 때: SDK·API 제약이 바뀔 때.
