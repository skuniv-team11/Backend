package kr.ac.skuniv.coopradar.common;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 계정 행 잠금. 계정당 1건을 지우고 다시 넣는 저장(탐색·커리어 리포트)이 같은 계정의 겹친 요청끼리 차례로 돌게 한다.
 * {@code FOR NO KEY UPDATE}라 다른 표의 외래 키 확인은 막지 않고, 탈퇴(DELETE)와는 서로 기다린다. 트랜잭션 안에서 부른다.
 */
public final class AccountLock {

    private AccountLock() {
    }

    /** 잠갔으면 true, 계정이 없으면(탈퇴·체험 계정 정리) false. */
    public static boolean lock(JdbcClient db, long userId) {
        return db.sql("SELECT 1 FROM app_user WHERE id = :id FOR NO KEY UPDATE").param("id", userId)
                .query(Integer.class).optional().isPresent();
    }
}
