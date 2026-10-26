package com.todayit.place.ingestion.service.model;

/**
 * 성공 원본 한 묶음의 적재 결과입니다. 건수는 서로 중복되지 않으며 합계는 total입니다.
 *
 * @param total 이번 실행에서 조회·처리한 원본 수
 * @param stored 커밋까지 성공한 장소 수
 * @param excluded 대상 제외 수
 * @param reviewRequired 정제 검수 필요 수
 * @param regionMissing 지역 미등록으로 보류한 수
 * @param failed 지역 조회·저장·커밋의 DB 또는 트랜잭션 실패 수
 */
public record PlaceIngestionResult(
    int total, int stored, int excluded, int reviewRequired, int regionMissing, int failed) {}
