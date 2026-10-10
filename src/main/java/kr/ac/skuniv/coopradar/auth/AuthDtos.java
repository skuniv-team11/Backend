package kr.ac.skuniv.coopradar.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import kr.ac.skuniv.coopradar.internship.InternshipDtos.GuestStage;
import kr.ac.skuniv.coopradar.common.Times;
import kr.ac.skuniv.coopradar.me.ProfileView;

/** 인증 API의 요청·응답 모양(docs/api: auth-*.json, me.json). 필드 이름이 JSON 이름이다. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record CredentialsRequest(
            @NotBlank(message = "필수예요") @Email(message = "이메일 형식이 아니에요") @Size(max = 254, message = "254자 이하")
            String email,
            @NotNull(message = "필수예요") @Size(min = 8, message = "8자 이상") String password) {
    }

    /**
     * @param stage     체험 학생의 시점(APPLYING 지원 중 · PRACTICING 실습 중 · DONE 실습 마친 뒤). 없으면 APPLYING. 센터는 무시
     * @param demoGroup 같은 브라우저에서 먼저 만든 체험 계정의 묶음. 없거나 만료됐으면 새 묶음(ADR-0033)
     */
    public record GuestRequest(@NotNull(message = "STUDENT 또는 CENTER") Role role, GuestStage stage, UUID demoGroup) {
    }

    public record UserView(long id, String email, Role role, boolean isGuest, OffsetDateTime expiresAt, boolean hasProfile) {

        public static UserView of(UserRepository.Account a) {
            return new UserView(a.id(), a.email(), a.role(), a.guest(), Times.kst(a.expiresAt()), a.hasProfile());
        }
    }

    public record TokenResponse(String accessToken, String tokenType, OffsetDateTime expiresAt, UserView user) {
    }

    /** 체험 계정. STUDENT면 저장된 예시 프로필, CENTER면 profile은 null. */
    public record GuestTokenResponse(String accessToken, String tokenType, OffsetDateTime expiresAt, UserView user,
                                     ProfileView profile, UUID demoGroup, LocalDate demoToday) {
    }
}
