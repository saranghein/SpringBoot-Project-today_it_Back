package com.todayit.place.raw.service.model;

/**
 * 정제에 사용하는 불변 원본 스냅샷입니다. 외부 호출이나 원본 갱신에 사용하지 않습니다.
 *
 * @param rawId 원본 PK
 * @param provider 공급원 코드
 * @param sourceId 공급원 장소 ID
 * @param status 원본 수집 상태
 * @param payloadJson 저장된 원본 JSON
 */
public record RawPlaceSnapshot(
    long rawId, String provider, String sourceId, String status, String payloadJson) {}
