# E6 외부 데이터 (키 발급 + 1회 적재)

셋 다 **로컬에서 한 번 돌려 결과 CSV를 시드로 쓰는** 스크립트입니다. 서비스 실행 중에는 부르지 않습니다.

| 스크립트 | 키 발급 | 결과 | 쓰임 |
|---|---|---|---|
| `nts_status.py` | 공공데이터포털 → '국세청_사업자등록정보 진위확인 및 상태조회 서비스' 활용신청(자동승인) → **Decoding 키** | `nts_status.csv` (18곳 상태) | M2 휴·폐업 탐지 |
| `odsay_commute.py` | [ODsay LAB](https://lab.odsay.com) 가입 → 애플리케이션 등록 → 플랫폼 **서버**에 본인 PC 공인 IP 등록 | `commute.csv` (서경대 → 근로지 소요분) | 직무 상세 참고 정보 |
| `ncs_load.py` | 공공데이터포털 → '한국산업인력공단_NCS 관련 정보' 활용신청(자동승인) → **Decoding 키** | `ncs_units.csv` (능력단위 약 15,520건) | M3 직무 풀이(P1) |

```
pip install requests openpyxl
NTS_SERVICE_KEY=... python nts_status.py
python odsay_commute.py template --jobs-xlsx "<참여기관 리스트.xlsx>"   # workplaces.csv 의 lat/lng 채우기
ODSAY_API_KEY=... python odsay_commute.py run --origin-lat <서경대 위도> --origin-lng <서경대 경도>
NCS_SERVICE_KEY=... python ncs_load.py
```

- **공공데이터포털 키는 Decoding(일반 인증키)을 씁니다.** 스크립트가 URL 인코딩을 한 번 하므로, Encoding 키를 넣으면 두 번 인코딩돼 인증에 실패합니다.
- **발급 직후 반영이 늦을 수 있습니다.** 자동승인이어도 인증 오류가 나면 1~2시간 뒤 다시 해 봅니다.
- **ODsay 무료 한도는 하루 30건입니다.** 근로지는 20곳 안쪽이라 하루면 끝납니다. 한도에 걸리면 받은 곳은 `odsay_cache/`에 남고, 다음 날 나머지만 부릅니다.
- **좌표는 사람이 한 번 찍습니다.** 근로지 18곳 정도라 지오코딩 API를 따로 붙이지 않습니다.
- **API 연결 확인:** `odsay_commute.py`는 잘못된 키로 실제 API를 불러 인증 실패 응답을 제대로 읽는 것까지 확인했습니다. 국세청·NCS는 이 작업 환경에서 호출이 막혀 있어 키를 받은 뒤 처음 돌려 보게 됩니다.
- **저장소:** `*.csv`와 `odsay_cache/`는 저장소에 올리지 않습니다. 사업자번호가 포함되고, 결과는 시드로 옮깁니다.
