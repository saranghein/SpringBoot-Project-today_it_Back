package com.todayit.place.raw.service.model;

/**
 * DB 조회 트랜잭션 밖에서 재수집할 불변 원본입니다.
 *
 * @param sourceId 공급원 장소 ID
 * @param payloadJson 저장된 원본 JSON, 첫 실패 원본이 없으면 null
 */
public record RawRetryTarget(String sourceId, String payloadJson) {}
