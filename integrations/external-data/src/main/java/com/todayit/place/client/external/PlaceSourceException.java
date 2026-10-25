package com.todayit.place.client.external;

/** 비밀키·요청 URL·응답 본문을 포함하지 않는 외부 수집 실패입니다. */
public final class PlaceSourceException extends RuntimeException {

  private final Reason reason;

  /**
   * 비밀정보를 포함하지 않는 분류로 예외를 생성합니다. 기술 예외 cause는 보존하지 않습니다.
   *
   * @param reason 실패 분류
   */
  public PlaceSourceException(Reason reason) {
    super("외부 장소 수집 실패: " + reason.name());
    this.reason = reason;
  }

  /**
   * @return 재시도 판단에 사용할 실패 분류
   */
  public Reason getReason() {
    return reason;
  }

  /** 호출자가 재시도·검수를 결정하는 오류 분류입니다. */
  public enum Reason {
    AUTHENTICATION,
    RATE_LIMIT,
    TRANSIENT,
    INVALID_REQUEST,
    INVALID_RESPONSE,
    UNSUPPORTED
  }
}
