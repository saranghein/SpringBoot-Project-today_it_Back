package com.todayit.place.raw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 명시적으로 활성화할 때만 실행하는 소량 원본 수집·재수집·정제 미리보기 설정입니다.
 *
 * @param enabled 선택한 수집/정제 모드 실행 여부, 기본 false
 * @param limit 실행당 최대 대상 수, 1~10, 기본 10. SAMPLE은 첫 페이지 크기
 * @param mode SAMPLE: 표본 수집, RETRY_FAILED: 실패 재수집, NORMALIZE: 성공 원본의 읽기 전용 정제 미리보기
 */
@ConfigurationProperties("todayit.place.collection")
public record RawCollectionProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("10") int limit,
    @DefaultValue("SAMPLE") Mode mode) {

  /** 수동 실행에서 선택할 모드입니다. 한 번의 실행에서는 하나의 모드만 수행합니다. */
  public enum Mode {
    SAMPLE,
    RETRY_FAILED,
    NORMALIZE
  }
}
