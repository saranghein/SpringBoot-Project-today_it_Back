package com.todayit.member.client.external;

/** Kakao OAuth 외부 연동 중 발생한 오류입니다. */
public class KakaoOAuthException extends RuntimeException {

  /** Kakao OAuth 실패 원인입니다. */
  public enum Reason {
    INVALID_AUTHORIZATION_CODE,
    INVALID_ACCESS_TOKEN,
    INVALID_USER_INFO,
    PROVIDER_UNAVAILABLE,
    PROVIDER_REQUEST_REJECTED
  }

  private final Reason reason;

  /**
   * Kakao OAuth 오류를 생성합니다.
   *
   * @param reason 실패 원인
   */
  public KakaoOAuthException(Reason reason) {
    super("Kakao OAuth failed: " + reason);
    this.reason = reason;
  }

  /**
   * 원인 예외를 포함한 Kakao OAuth 오류를 생성합니다.
   *
   * @param reason 실패 원인
   * @param cause 실제 오류를 발생시킨 원인 예외
   */
  public KakaoOAuthException(Reason reason, Throwable cause) {
    // 원래 오류를 보관해 서버에서 원인을 추적할 수 있도록 함
    super("Kakao OAuth failed: " + reason, cause);
    this.reason = reason;
  }

  public Reason getReason() {
    return reason;
  }
}
