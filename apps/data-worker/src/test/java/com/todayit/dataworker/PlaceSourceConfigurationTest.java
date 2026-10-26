package com.todayit.dataworker;

import static org.assertj.core.api.Assertions.assertThat;

import com.todayit.place.client.external.PlaceDataSource;
import com.todayit.place.client.external.PlaceDataSourceRegistry;
import com.todayit.place.client.external.PlaceSourceConfiguration;
import com.todayit.place.client.external.PlaceSourceProperties;
import com.todayit.place.client.external.tourapi.TourApiPlaceDataSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** worker YAML과 공급원 활성화 설정의 바인딩을 검증합니다. */
class PlaceSourceConfigurationTest {

  /** DB 접속 없이 worker의 공급원 설정이 짧은 환경 변수 이름으로 바인딩되는지 검증합니다. */
  @Test
  void workerYamlUsesShortEnvironmentNamesWithoutLoadingDatabase() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(PlaceSourceConfiguration.class)
        .withPropertyValues(
            "TOUR_API_ENABLED=true",
            "TOUR_API_SERVICE_KEY=fixture",
            "TOUR_API_TIMEOUT=2s",
            "REDTABLE_ENABLED=false",
            "CULTURE_ENABLED=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(TourApiPlaceDataSource.class);
              assertThat(context.getBeansOfType(PlaceDataSource.class)).hasSize(1);
              assertThat(
                      context
                          .getEnvironment()
                          .getProperty("todayit.place.sources.tourapi.service-key"))
                  .isEqualTo("fixture");
              assertThat(
                      context.getEnvironment().getProperty("todayit.place.sources.tourapi.timeout"))
                  .isEqualTo("2s");
              assertThat(context.getBeansOfType(javax.sql.DataSource.class)).isEmpty();
              var properties = context.getBean(PlaceSourceProperties.class);
              assertThat(properties.tourapi().enabled()).isTrue();
              assertThat(properties.tourapi().serviceKey()).isEqualTo("fixture");
              assertThat(properties.tourapi().timeout()).isEqualTo(Duration.ofSeconds(2));
              assertThat(
                      context
                          .getBean(PlaceDataSourceRegistry.class)
                          .get(new PlaceDataSource.SourceKey("TOUR_API", "kor-service2")))
                  .isSameAs(context.getBean(TourApiPlaceDataSource.class));
            });
  }

  /** 공급원이 명시적으로 활성화되기 전에는 비활성 상태를 유지하는지 검증합니다. */
  @Test
  void sourcesStayDisabledUnlessExplicitlyEnabled() {
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(PlaceSourceConfiguration.class)
        .withPropertyValues(
            "TOUR_API_ENABLED=false", "REDTABLE_ENABLED=false", "CULTURE_ENABLED=false")
        .run(
            context -> {
              assertThat(context).hasNotFailed().hasSingleBean(PlaceDataSourceRegistry.class);
              assertThat(context.getBeansOfType(PlaceDataSource.class)).isEmpty();
            });
  }
}
