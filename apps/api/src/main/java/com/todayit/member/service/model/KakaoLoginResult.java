package com.todayit.member.service.model;

/**
 * Kakao 로그인 처리 결과입니다.
 *
 * @param isNewMember 신규 회원 여부
 * @param signupToken 신규 회원의 임시 가입 토큰
 * @param loginResult 기존 회원의 로그인 토큰 및 회원 정보
 */
public record KakaoLoginResult(boolean isNewMember, String signupToken, LoginResult loginResult) {
  /**
   * 기존 Kakao 회원의 로그인 결과를 생성합니다.
   *
   * @param loginResult 발급된 로그인 토큰과 회원 정보
   * @return 기존 회원 로그인 결과
   */
  public static KakaoLoginResult existingMember(LoginResult loginResult) {
    // 기존 회원은 회원가입이 필요하지 않음
    return new KakaoLoginResult(false, null, loginResult);
  }

  /**
   * 신규 Kakao 사용자의 인증 결과를 생성합니다.
   *
   * @param signupToken 회원가입용 임시 토큰
   * @return 신규 회원 인증 결과
   */
  public static KakaoLoginResult newMember(String signupToken) {
    // 신규 회원은 아직 DB에 등록되지 않음
    return new KakaoLoginResult(true, signupToken, null);
  }
}
