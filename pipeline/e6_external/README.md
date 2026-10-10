# E6 외부 데이터 (키 발급 + 1회 적재)

**로컬에서 한 번 돌려 결과 CSV를 시드로 쓰는** 스크립트입니다. 서비스 실행 중에는 부르지 않습니다.

> **통근 시간·좌표는 여기서 만들지 않습니다.** 통근은 서비스가 조회할 때마다 카카오 주소 검색 + 대중교통으로 계산하고 저장하지 않습니다([ADR-0007](../../docs/decisions/0007-commute-kakao-live.md), 10/2 개정). `odsay_commute.py`는 10/1 호출 확인용으로 남긴 보관 파일이고 다시 돌리지 않으므로 ODsay 키는 필요 없습니다.

| 스크립트 | 키 발급 | 결과 | 쓰임 |
|---|---|---|---|
| `nts_status.py` | 공공데이터포털 → '국세청_사업자등록정보 진위확인 및 상태조회 서비스' 활용신청(자동승인) → **Decoding 키** | `nts_status.csv` (18곳 상태) | M2 휴·폐업 탐지 |
| `ncs_load.py` | 공공데이터포털 → '한국산업인력공단_NCS 관련 정보' 활용신청(자동승인) → **Decoding 키** | `ncs_units.csv` (능력단위 약 15,520건) | 커리어(직무 → NCS 세분류·능력단위, ADR-0032 — `seed/ncs_seed.py`) |

```
pip install requests openpyxl
NTS_SERVICE_KEY=... python nts_status.py
NCS_SERVICE_KEY=... python ncs_load.py
```

- **공공데이터포털 키는 Decoding(일반 인증키)을 씁니다.** 스크립트가 URL 인코딩을 한 번 하므로, Encoding 키를 넣으면 두 번 인코딩돼 인증에 실패합니다.
- **발급 직후 반영이 늦을 수 있습니다.** 자동승인이어도 인증 오류가 나면 1~2시간 뒤 다시 해 봅니다.
- **API 연결 확인(10/1):** 국세청·NCS 모두 실제 키로 응답을 확인했습니다.
- **저장소:** `*.csv`와 `odsay_cache/`는 저장소에 올리지 않습니다. 사업자번호가 포함되고, 결과는 시드로 옮깁니다. 로컬에 남은 `commute.csv`·`odsay_cache/`·`workplaces.csv`(10/1 E6)는 더 쓰지 않으니 지워도 됩니다(ODsay 결과는 정책상 지우는 것을 권합니다).
