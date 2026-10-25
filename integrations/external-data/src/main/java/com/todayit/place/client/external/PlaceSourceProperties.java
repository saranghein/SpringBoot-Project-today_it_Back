package com.todayit.place.client.external;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 외부 공급원의 설정을 바인딩합니다. 비활성 공급원의 인증키는 필수가 아닙니다.
 *
 * @param tourapi TourAPI 설정
 * @param redtable 레드테이블 설정
 * @param culture 문화시설 설정
 */
@ConfigurationProperties("todayit.place.sources")
public record PlaceSourceProperties(
    @DefaultValue TourApiProperties tourapi,
    @DefaultValue ProviderProperties redtable,
    @DefaultValue ProviderProperties culture) {

  /**
   * TourAPI의 인증·호출 설정입니다.
   *
   * @param enabled 명시적으로 활성화할 때만 true
   * @param serviceKey URL 인코딩 전 발급키, 기본 빈값
   * @param timeout 전체 응답 제한 시간, 기본 30초
   * @param mobileApp 공공데이터포털에 전달할 앱 이름
   */
  public record TourApiProperties(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("") String serviceKey,
      @DefaultValue("30s") Duration timeout,
      @DefaultValue("TodayIt") String mobileApp) {

    /**
     * @return 인증키를 제외한 설정 요약
     */
    @Override
    public String toString() {
      return "TourApiProperties[enabled=" + enabled + ", timeout=" + timeout + "]";
    }
  }

  /**
   * 레드테이블·문화시설의 인증·호출 설정입니다.
   *
   * @param enabled 명시적으로 활성화할 때만 true
   * @param serviceKey URL 인코딩 전 발급키, 기본 빈값
   * @param timeout 전체 응답 제한 시간, 기본 30초
   */
  public record ProviderProperties(
      @DefaultValue("false") boolean enabled,
      @DefaultValue("") String serviceKey,
      @DefaultValue("30s") Duration timeout) {

    /**
     * @return 인증키를 제외한 설정 요약
     */
    @Override
    public String toString() {
      return "ProviderProperties[enabled=" + enabled + ", timeout=" + timeout + "]";
    }
  }
}
