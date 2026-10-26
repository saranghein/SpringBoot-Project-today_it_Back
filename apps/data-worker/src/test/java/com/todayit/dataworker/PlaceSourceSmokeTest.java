package com.todayit.dataworker;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.todayit.place.client.external.PlaceDataSource;
import com.todayit.place.client.external.PlaceDataSource.Component;
import com.todayit.place.client.external.PlaceDataSource.SourceComponentRequest;
import com.todayit.place.client.external.PlaceDataSource.SourcePage;
import com.todayit.place.client.external.PlaceDataSource.SourcePageRequest;
import com.todayit.place.client.external.PlaceSourceConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 명시적 Gradle task에서만 실행하는 읽기 전용 실제 인증 검사입니다. */
class PlaceSourceSmokeTest {

  private static final Logger LOG = LoggerFactory.getLogger(PlaceSourceSmokeTest.class);

  /** 활성 공급원을 소량 조회하여 실제 인증과 응답 계약을 확인합니다. */
  @Test
  void verifyEnabledProvidersWithSmallBoundedReadRequests() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(PlaceSourceConfiguration.class)
        .run(
            context -> {
              assertTrue(context.getStartupFailure() == null, "외부 연동 설정 초기화 실패");
              var sources = context.getBeansOfType(PlaceDataSource.class).values();
              assertFalse(sources.isEmpty(), "공급원 ENABLED 설정과 SERVICE_KEY 환경 변수를 등록해야 합니다.");
              assertAll(sources.stream().map(source -> () -> verify(source)));
            });
  }

  /**
   * 한 공급원의 제한된 목록·다음 페이지·지원 상세를 실제 조회하고 안전한 완료 요약을 남깁니다.
   *
   * @param source 활성화되어 인증키가 주입된 공급원
   * @throws com.todayit.place.client.external.PlaceSourceException 외부 조회 실패 시
   * @throws AssertionError 표본이 없거나 상세 항목·ID·필수 장소명 검증에 실패할 때
   */
  private void verify(PlaceDataSource source) {
    String provider = source.sourceKey().provider();
    Map<String, String> filters =
        switch (provider) {
          case "TOUR_API" -> Map.of("lDongRegnCd", "11", "contentTypeId", "12");
          case "CULTURE" -> Map.of("facility", "museum");
          default -> Map.of();
        };
    SourcePage first = source.fetchPage(new SourcePageRequest(Component.BASIC, "", 1, filters));
    assertFalse(first.items().isEmpty(), "표본 목록이 없어 상세 검증을 수행할 수 없습니다: " + provider);
    assertNotNull(first.collectedAt());
    if (!first.nextCursor().isEmpty()) {
      SourcePage second =
          source.fetchPage(new SourcePageRequest(Component.BASIC, first.nextCursor(), 1, filters));
      assertFalse(second.items().isEmpty(), "두 번째 표본 페이지가 비어 있습니다: " + provider);
    }
    String sourceId = first.items().getFirst().sourceId();
    for (Component component : source.capabilities().individualComponents()) {
      var detailFilters =
          provider.equals("TOUR_API") && component == Component.INTRODUCTION
              ? Map.of("contentTypeId", "12")
              : Map.<String, String>of();
      var request = new SourceComponentRequest(sourceId, component, detailFilters);
      var detail = source.fetchComponent(request);
      PlaceSourceSmokeVerifier.verify(source.sourceKey(), request, detail);
    }
    for (Component component : source.capabilities().pagedComponents()) {
      if (component != Component.BASIC) {
        source.fetchPage(new SourcePageRequest(component, "", 1, filters));
      }
    }
    // 키·요청 URI·원본 payload 대신 실행 범위와 건수만 남깁니다.
    LOG.info(
        "SOURCE_SMOKE_OK provider={} dataset={} firstPageItems={} totalCount={}",
        provider,
        source.sourceKey().dataset(),
        first.items().size(),
        first.totalCount());
  }
}
