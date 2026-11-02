package com.todayit.member.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.todayit.member.service.model.KakaoLoginResult;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kakao 로그인 및 신규 회원 인증 결과입니다.
 *
 * @param isNewMember 신규 회원 여부
 * @param signupToken 신규 회원의 임시 가입 토큰
 * @param accessToken 기존 회원의 Access Token
 * @param refreshToken 기존 회원의 Refresh Token
 * @param expiresIn Access Token 유효 시간(초)
 * @param member 기존 회원의 정보
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Kakao 로그인 응답")
public record KakaoLoginResponse(
    @Schema(description = "신규 회원 여부", example = "true") boolean isNewMember,
    @Schema(description = "신규 회원에게 발급하는 30분 유효 임시 가입 토큰") String signupToken,
    @Schema(description = "기존 회원에게 발급하는 Access Token") String accessToken,
    @Schema(description = "기존 회원에게 발급하는 Refresh Token") String refreshToken,
    @Schema(description = "Access Token 유효 시간(초)") Long expiresIn,
    @Schema(description = "기존 회원의 정보") LoginResponse.MemberResponse member) {

  /**
   * Kakao 로그인 서비스 결과를 API 응답 데이터로 변환합니다.
   *
   * @param result Kakao 로그인 처리 결과
   * @return 기존 회원 또는 신규 회원에 맞는 Kakao 로그인 응답
   */
  public static KakaoLoginResponse from(KakaoLoginResult result) {
    // 신규 회원은 회원가입용 임시 토큰만 반환
    if (result.isNewMember()) {
      return new KakaoLoginResponse(true, result.signupToken(), null, null, null, null);
    }

    // 기존 회원은 LOCAL 로그인과 동일한 응답 구성 재사용
    LoginResponse login = LoginResponse.from(result.loginResult());

    return new KakaoLoginResponse(
        false, null, login.accessToken(), login.refreshToken(), login.expiresIn(), login.member());
  }
}
