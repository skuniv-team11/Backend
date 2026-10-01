package kr.ac.skuniv.coopradar.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
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

    public record GuestRequest(@NotNull(message = "STUDENT 또는 CENTER") Role role) {
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
                                     ProfileView profile) {
    }
}
