package com.todayit.place.raw.service.model;

/**
 * 외부 수집 시도 결과입니다. 인증·쿼터 중단 이후의 미시도 건수는 포함하지 않습니다.
 *
 * @param attempted 실제 시도 건수
 * @param succeeded 성공 저장 건수
 * @param failed 실패 저장 건수
 */
public record RawCollectionResult(int attempted, int succeeded, int failed) {}
