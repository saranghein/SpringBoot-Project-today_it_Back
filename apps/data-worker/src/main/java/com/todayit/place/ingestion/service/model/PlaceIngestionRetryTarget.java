package com.todayit.place.ingestion.service.model;

import com.todayit.place.raw.service.model.RawPlaceSnapshot;

/**
 * 재적재할 실패의 식별자와 조회 시점의 성공 원본 스냅샷입니다.
 *
 * @param failureId 실패 목록 PK
 * @param raw 현재 SUCCESS인 원본 스냅샷
 */
public record PlaceIngestionRetryTarget(long failureId, RawPlaceSnapshot raw) {}
