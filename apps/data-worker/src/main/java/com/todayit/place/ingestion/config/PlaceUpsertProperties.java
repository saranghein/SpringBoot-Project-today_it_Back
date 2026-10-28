package com.todayit.place.ingestion.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 지역 기준 데이터와 Migration을 준비한 환경에서 명시적으로 활성화하는 서비스 반영 설정입니다.
 *
 * @param enabled 지역 조회·저장 기능 등록 여부, 기본 false. 등록만으로 적재하지 않음
 * @param runEnabled 시작 시 적재 실행 여부, 기본 false
 * @param limit 1~10, 기본 10. SAMPLE은 최대 원본 수, FULL·RETRY_FAILED는 조회 묶음 크기
 * @param mode SAMPLE: 첫 묶음, FULL: 성공 원본 전체, RETRY_FAILED: 미해결 저장 실패 전체 재처리
 */
@ConfigurationProperties("todayit.place.upsert")
public record PlaceUpsertProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("false") boolean runEnabled,
    @DefaultValue("10") int limit,
    @DefaultValue("SAMPLE") Mode mode) {

  /** 성공 원본의 표본·전체 적재 또는 미해결 저장 실패의 전체 재적재를 선택합니다. */
  public enum Mode {
    SAMPLE,
    FULL,
    RETRY_FAILED
  }
}
