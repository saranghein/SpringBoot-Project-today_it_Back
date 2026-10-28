package com.todayit.place.raw.service.model;

/**
 * 외부 수집 시도 결과입니다. 인증·쿼터 중단 이후의 미시도 건수는 포함하지 않습니다.
 *
 * @param attempted 실제 시도 건수
 * @param succeeded 성공 저장 건수
 * @param failed 실패 저장 건수
 * @param stoppedEarly 인증·호출 한도 오류로 나머지 대상을 중단했는지 여부
 */
public record RawCollectionResult(int attempted, int succeeded, int failed, boolean stoppedEarly) {

  /**
   * 중단 없이 선택 범위를 처리한 결과를 만듭니다. 개별 실패가 있어도 순회 자체는 완료될 수 있습니다.
   *
   * @param attempted 시도 건수
   * @param succeeded 성공 건수
   * @param failed 실패 건수
   */
  public RawCollectionResult(int attempted, int succeeded, int failed) {
    this(attempted, succeeded, failed, false);
  }
}
