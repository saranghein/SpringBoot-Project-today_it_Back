package com.todayit.place.client.external;

import com.todayit.place.client.external.culture.CulturePlaceDataSource;
import com.todayit.place.client.external.redtable.RedtablePlaceDataSource;
import com.todayit.place.client.external.support.PlaceSourceHttpClient;
import com.todayit.place.client.external.tourapi.TourApiPlaceDataSource;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** 명시적으로 활성화한 원천만 등록합니다. 등록 또는 앱 시작 시 HTTP 호출은 하지 않습니다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlaceSourceProperties.class)
public class PlaceSourceConfiguration {

  /**
   * TourAPI 원천을 등록합니다.
   *
   * @param properties 공급원별 인증·호출 설정
   * @return TourAPI 구현체
   */
  @Bean
  @ConditionalOnProperty(name = "todayit.place.sources.tourapi.enabled", havingValue = "true")
  public TourApiPlaceDataSource tourApiPlaceDataSource(PlaceSourceProperties properties) {
    var tourapi = properties.tourapi();
    return new TourApiPlaceDataSource(
        http(
            "https://apis.data.go.kr/B551011/KorService2", tourapi.serviceKey(), tourapi.timeout()),
        tourapi.mobileApp());
  }

  /**
   * 서울 레드테이블 원천을 등록합니다.
   *
   * @param properties 공급원별 인증·호출 설정
   * @return 서울 상품 구현체
   */
  @Bean
  @ConditionalOnProperty(name = "todayit.place.sources.redtable.enabled", havingValue = "true")
  public RedtablePlaceDataSource redtablePlaceDataSource(PlaceSourceProperties properties) {
    var redtable = properties.redtable();
    return new RedtablePlaceDataSource(
        http("https://seoul.openapi.redtable.global", redtable.serviceKey(), redtable.timeout()));
  }

  /**
   * 문화시설 원천을 등록합니다.
   *
   * @param properties 공급원별 인증·호출 설정
   * @return 문화시설 구현체
   */
  @Bean
  @ConditionalOnProperty(name = "todayit.place.sources.culture.enabled", havingValue = "true")
  public CulturePlaceDataSource culturePlaceDataSource(PlaceSourceProperties properties) {
    var culture = properties.culture();
    return new CulturePlaceDataSource(
        http(
            "https://apis.data.go.kr/B553457/nopenapi/rest/cultureartspaces",
            culture.serviceKey(),
            culture.timeout()));
  }

  /**
   * 구현체 목록으로 원천 Registry를 생성합니다.
   *
   * @param sources 활성화된 원천 목록
   * @return 식별자별 Registry
   */
  @Bean
  public PlaceDataSourceRegistry placeDataSourceRegistry(List<PlaceDataSource> sources) {
    return new PlaceDataSourceRegistry(sources);
  }

  /**
   * 응답을 최대 2MiB로 제한하는 Client를 생성합니다. 인증키는 인코딩 전 값으로 전달합니다.
   *
   * @param baseUrl 고정된 공식 공급원 주소
   * @param key 인코딩 전 발급키
   * @param timeout 바인딩된 전체 응답 제한 시간
   * @return 자동 재시도 없이 한 번 요청하는 공급원 HTTP Client
   * @throws IllegalArgumentException 제한 시간이 양수가 아닐 때
   */
  private PlaceSourceHttpClient http(String baseUrl, String key, Duration timeout) {
    WebClient client =
        WebClient.builder()
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
            .build();
    return new PlaceSourceHttpClient(client, baseUrl, key, timeout);
  }
}
