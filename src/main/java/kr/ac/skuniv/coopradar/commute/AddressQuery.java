package kr.ac.skuniv.coopradar.commute;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 근로지 주소 → 카카오 주소 검색어. 참여기관 리스트의 주소는 건물명·층·호수가 섞여 있어(예: '서울시 성동구 뚝섬로1길 25, 706-707호')
 * 도로명 + 건물번호까지만 남긴다. 화면에 보이는 주소(destination.address)는 원문 그대로 둔다.
 */
final class AddressQuery {

    // '…로 N(번)?(가)?길 M' — 도로명과 'N길' 사이 띄어쓰기는 붙인다(당산로 41길 11 → 당산로41길 11)
    private static final Pattern ROAD_GIL =
            Pattern.compile("^(.+?(?:로|길))\\s*(\\d+(?:번)?[가-힣]?길)\\s*(\\d+(?:-\\d+)?)(?![\\d-])");
    // '…로 M' / '…길 M' (서초중앙로41 → 서초중앙로 41)
    private static final Pattern ROAD = Pattern.compile("^(.+?(?:로|길))\\s*(\\d+(?:-\\d+)?)(?![\\d-])");

    private AddressQuery() {
    }

    /** 검색어. 도로명 + 건물번호를 못 찾으면 첫 쉼표 앞까지. */
    static String of(String address) {
        String s = address.strip().replaceAll("\\s+", " ");
        if (s.startsWith("서울시 ")) {
            s = "서울 " + s.substring("서울시 ".length());
        }
        Matcher m = ROAD_GIL.matcher(s);
        if (m.find()) {
            return m.group(1).strip() + m.group(2) + " " + m.group(3);
        }
        m = ROAD.matcher(s);
        if (m.find()) {
            return m.group(1).strip() + " " + m.group(2);
        }
        return beforeComma(s);
    }

    /** 첫 쉼표 앞(두 번째 검색어). */
    static String beforeComma(String address) {
        int comma = address.indexOf(',');
        return (comma < 0 ? address : address.substring(0, comma)).strip();
    }
}
