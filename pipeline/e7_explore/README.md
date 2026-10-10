# E7 직무 탐색 — AI가 필요한가 (ADR-0031)

학생이 쓴 '해 본 일'로 지원할 자리를 찾을 때 키워드로 충분한지, AI가 필요한지 잰 실험이다. 결과는 서비스의 직무 탐색(#28~#32)이 됐다.

```
python build_inputs.py                                   # ① 시드 → out/jobs.json · out/testimonials.json (커밋 안 함)
python e7.py k                                           # ② 키워드(글자 2·3-gram TF-IDF), AI 없음
ANTHROPIC_API_KEY=... python e7.py a1 claude-haiku-5-5   #    AI가 경험을 공고 말로 바꾼 뒤 키워드
ANTHROPIC_API_KEY=... python e7.py a2 claude-sonnet-5-5  #    AI가 자리 40개를 읽고 5곳 + 구절
python score.py                                          # ③ Hit@1·Hit@3·MRR@5, 구절 원문 일치, 토큰·응답 시간
ANTHROPIC_API_KEY=... python why.py                      # '왜 맞나요' 시험(예시 학생 × 5곳)
ANTHROPIC_API_KEY=... python demo.py                     # 예시 학생: 지원 가능 23곳 → AI 순서 → 상위 3곳 설명
```

| 세트 | 내용 | 정답 |
|---|---|---|
| S1 실제 | 선배 수기(2026-2 참여기관과 이어지는 17건)의 실습 내용 | 같은 기관 자리(기관 단위) |
| S2 가상 | 학생 말투 '해 본 일' 30개(`synthetic.json`, 직무 용어 섞임 14 · 생활 말 16) | 돌리기 전에 고정한 자리. **Claude가 쓰고 정답도 Claude가 붙임 — 팀 검수 전** |

- 2026-10-10 결과: 키워드 S1 Hit@3 13/17 → AI 판단(Sonnet, 검증 통과만) 15/17, 생활 말 Hit@1 10 → 15~16/16. AI가 바꾸기만 하는 방식은 키워드와 거의 같았고, Haiku는 키워드보다 Hit@1이 낮았다. 자세한 표는 프로젝트 문서 'E7-AI-필요성-실험'.
- 서비스로 옮긴 것: 지원 조건은 규칙이 먼저 거른 후보만 AI에 보낸다(demo.py), 구절은 코드가 원문과 대조한다, 맞는 정도는 순위로 정한다, 학과·학년은 AI에 보내지 않는다(why.py 2회차 규칙). 서비스 프롬프트는 `src/main/resources/prompts/explore-*-system.md`.
- 생각 단계는 끈다(Haiku `disabled`, Sonnet 5.5 `between_tools`). 키는 환경변수로만 받는다. 결과(`out/`)는 커밋하지 않는다.
