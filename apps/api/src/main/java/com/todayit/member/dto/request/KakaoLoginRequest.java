package com.todayit.member.dto.request;

import com.todayit.common.exception.InvalidRequestException;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Kakao OAuth 로그인 요청입니다.
 *
 * @param authorizationCode Kakao에서 발급한 인가 코드
 * @param redirectUri 인가 코드를 발급받을 때 사용한 Redirect URI
 */
@Schema(description = "Kakao 로그인 요청")
public record KakaoLoginRequest(
    @Schema(description = "Kakao에서 발급한 일회성 인가 코드입니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        String authorizationCode,
    @Schema(
            description = "Kakao Developers에 등록한 Redirect URI입니다.",
            example = "http://localhost:3000/oauth/kakao/callback",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String redirectUri) {

  /** 필수 입력값을 검사합니다. */
  public void validate() {
    // Kakao 인가 코드 필수
    if (authorizationCode == null || authorizationCode.isBlank()) {
      throw new InvalidRequestException("Kakao 인가 코드는 필수입니다.");
    }

    // 인가 코드 발급에 사용한 Redirect URI 필수
    if (redirectUri == null || redirectUri.isBlank()) {
      throw new InvalidRequestException("Redirect URI는 필수입니다.");
    }
  }
}
