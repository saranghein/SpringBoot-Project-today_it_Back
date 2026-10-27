package com.todayit.place.ingestion.service.model;

/**
 * 장소 저장 결과입니다.
 *
 * @param sourceId 공급원 장소 ID
 * @param status 반영 또는 보류 여부
 * @param placeId 저장한 장소 ID, 미반영 시 null
 */
public record PlaceUpsertResult(String sourceId, Status status, Integer placeId) {

  /** 정제 보류·지역 누락은 저장소를 호출하지 않습니다. */
  public enum Status {
    APPLIED,
    NORMALIZATION_SKIPPED,
    REGION_NOT_FOUND
  }
}
