package com.todayit.place.client.external;

import static org.assertj.core.api.Assertions.assertThat;

import com.todayit.place.client.external.culture.CulturePlaceDataSource;
import com.todayit.place.client.external.redtable.RedtablePlaceDataSource;
import com.todayit.place.client.external.tourapi.TourApiPlaceDataSource;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/** 공급원 설정 기본값과 바인딩·제한 시간 및 메타데이터 생성을 검증합니다. */
class PlaceSourcePropertiesTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(PlaceSourceConfiguration.class);

  /** 설정 누락 시 모든 공급원이 기본값을 사용하고 비활성 상태를 유지하는지 검증합니다. */
  @Test
  void missingPropertiesKeepAllProvidersDisabledWithDefaults() {
    contextRunner.run(
        context -> {
          assertThat(context).hasNotFailed().hasSingleBean(PlaceSourceProperties.class);
          var properties = context.getBean(PlaceSourceProperties.class);
          assertThat(properties.tourapi().enabled()).isFalse();
          assertThat(properties.tourapi().serviceKey()).isEmpty();
          assertThat(properties.tourapi().timeout()).isEqualTo(Duration.ofSeconds(30));
          assertThat(properties.tourapi().mobileApp()).isEqualTo("TodayIt");
          for (var provider :
              new PlaceSourceProperties.ProviderProperties[] {
                properties.redtable(), properties.culture()
              }) {
            assertThat(provider.enabled()).isFalse();
            assertThat(provider.serviceKey()).isEmpty();
            assertThat(provider.timeout()).isEqualTo(Duration.ofSeconds(30));
          }
          assertThat(context.getBeansOfType(PlaceDataSource.class)).isEmpty();
        });
  }

  /** 네트워크 호출 없이 공급원 설정과 제한 시간을 바인딩하는지 검증합니다. */
  @Test
  void bindsEachProviderAndConvertsDurationWithoutNetworkCalls() {
    contextRunner
        .withPropertyValues(
            "todayit.place.sources.tourapi.enabled=true",
            "todayit.place.sources.tourapi.service-key=tour-fixture",
            "todayit.place.sources.tourapi.timeout=250ms",
            "todayit.place.sources.tourapi.mobile-app=FixtureApp",
            "todayit.place.sources.redtable.enabled=true",
            "todayit.place.sources.redtable.service-key=redtable-fixture",
            "todayit.place.sources.redtable.timeout=2s",
            "todayit.place.sources.culture.enabled=true",
            "todayit.place.sources.culture.service-key=culture-fixture",
            "todayit.place.sources.culture.timeout=PT3S")
        .run(
            context -> {
              assertThat(context)
                  .hasNotFailed()
                  .hasSingleBean(TourApiPlaceDataSource.class)
                  .hasSingleBean(RedtablePlaceDataSource.class)
                  .hasSingleBean(CulturePlaceDataSource.class);
              var properties = context.getBean(PlaceSourceProperties.class);
              assertThat(properties.tourapi().enabled()).isTrue();
              assertThat(properties.tourapi().serviceKey()).isEqualTo("tour-fixture");
              assertThat(properties.tourapi().timeout()).isEqualTo(Duration.ofMillis(250));
              assertThat(properties.tourapi().mobileApp()).isEqualTo("FixtureApp");
              assertThat(properties.redtable().enabled()).isTrue();
              assertThat(properties.redtable().serviceKey()).isEqualTo("redtable-fixture");
              assertThat(properties.redtable().timeout()).isEqualTo(Duration.ofSeconds(2));
              assertThat(properties.culture().enabled()).isTrue();
              assertThat(properties.culture().serviceKey()).isEqualTo("culture-fixture");
              assertThat(properties.culture().timeout()).isEqualTo(Duration.ofSeconds(3));
              assertThat(properties.toString())
                  .doesNotContain("tour-fixture", "redtable-fixture", "culture-fixture");
            });
  }

  /** 활성 공급원의 키가 없어도 초기화 시 조회하지 않으며 비활성 공급원 키를 검사하지 않는지 검증합니다. */
  @Test
  void enabledProviderWithoutKeyStillInitializesWithoutValidatingDisabledKeys() {
    contextRunner
        .withPropertyValues("todayit.place.sources.tourapi.enabled=true")
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(TourApiPlaceDataSource.class);
              assertThat(context.getBeansOfType(PlaceDataSource.class)).hasSize(1);
              assertThat(context.getBean(PlaceSourceProperties.class).tourapi().serviceKey())
                  .isEmpty();
            });
  }

  /** 활성 공급원의 제한 시간이 양수가 아니면 설정을 거부하는지 검증합니다. */
  @Test
  void enabledProviderRejectsNonPositiveTimeout() {
    contextRunner
        .withPropertyValues(
            "todayit.place.sources.tourapi.enabled=true",
            "todayit.place.sources.tourapi.timeout=0s")
        .run(context -> assertThat(context).hasFailed());
  }

  /**
   * 모든 공급원 설정의 IDE 자동완성용 메타데이터가 생성되는지 검증합니다.
   *
   * @throws Exception 테스트 자원 준비 또는 비동기 실행에 실패할 때
   */
  @Test
  void generatesMetadataForAllProviderSettings() throws Exception {
    try (var metadata =
        PlaceSourceProperties.class.getResourceAsStream(
            "/META-INF/spring-configuration-metadata.json")) {
      assertThat(metadata).isNotNull();
      var properties = JsonMapper.builder().build().readTree(metadata).path("properties");
      var names = new ArrayList<String>();
      for (var property : properties) {
        names.add(property.path("name").asText());
        if (property.path("name").asText().endsWith(".timeout")) {
          assertThat(property.path("type").asText()).isEqualTo("java.time.Duration");
          assertThat(property.path("defaultValue").asText()).isEqualTo("30s");
        }
      }
      assertThat(names)
          .containsExactlyInAnyOrder(
              "todayit.place.sources.tourapi.enabled",
              "todayit.place.sources.tourapi.service-key",
              "todayit.place.sources.tourapi.timeout",
              "todayit.place.sources.tourapi.mobile-app",
              "todayit.place.sources.redtable.enabled",
              "todayit.place.sources.redtable.service-key",
              "todayit.place.sources.redtable.timeout",
              "todayit.place.sources.culture.enabled",
              "todayit.place.sources.culture.service-key",
              "todayit.place.sources.culture.timeout");
    }
  }
}
