package com.todayit.place.raw.service.model;

import com.todayit.place.service.model.NormalizedPlace;

/**
 * DB에 저장하지 않는 정제 결과입니다. NORMALIZED는 지역 기준표 대조나 서비스 적재 완료를 뜻하지 않습니다.
 *
 * @param rawId 원본 PK
 * @param sourceId 공급원 장소 ID
 * @param status 정제 결과 구분
 * @param reason 결과 사유, 정상 변환이면 null
 * @param place 정상 변환한 값, 정상 이외에는 null
 */
public record PlaceNormalizationResult(
    long rawId, String sourceId, Status status, Reason reason, NormalizedPlace place) {

  /** 원본 수집 상태와 별개의 메모리 내 정제 결과입니다. */
  public enum Status {
    NORMALIZED,
    EXCLUDED,
    REVIEW_REQUIRED
  }

  /** 원문·예외 메시지를 노출하지 않는 정제 결과 사유입니다. */
  public enum Reason {
    UNSUPPORTED_PROVIDER,
    NOT_COLLECTED,
    INVALID_PAYLOAD,
    CATEGORY_NOT_ALLOWED,
    CATEGORY_UNMAPPED,
    INVALID_NAME,
    INVALID_ADDRESS,
    INVALID_COORDINATES,
    INVALID_REGION_CODES,
    INVALID_IMAGES
  }
}
