package com.todayit.place.raw.config;

import com.todayit.place.raw.config.RawCollectionProperties.Mode;
import com.todayit.place.raw.service.PlaceNormalizationQueryService;
import com.todayit.place.raw.service.TourApiCollectionPlanService;
import com.todayit.place.raw.service.TourApiRawCollectionService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 명시적 실행 플래그로만 수집하며 실패가 있으면 프로세스 실행을 실패 처리합니다. */
@Component
@EnableConfigurationProperties(RawCollectionProperties.class)
@ConditionalOnProperty(name = "todayit.place.collection.enabled", havingValue = "true")
public class TourApiRawCollectionRunner implements ApplicationRunner {

  private final TourApiRawCollectionService collectionService;
  private final RawCollectionProperties properties;
  private final PlaceNormalizationQueryService normalizationQueryService;
  private final TourApiCollectionPlanService planService;

  /**
   * 원본 재수집·정제 미리보기·범위별 수집 서비스와 실행 설정을 주입합니다.
   *
   * @param collectionService 실패 원본 재수집 서비스
   * @param properties 수동 실행 설정
   * @param normalizationQueryService 저장 없는 정제 미리보기
   * @param planService 여러 수집 범위의 순차 실행 서비스
   */
  public TourApiRawCollectionRunner(
      TourApiRawCollectionService collectionService,
      RawCollectionProperties properties,
      PlaceNormalizationQueryService normalizationQueryService,
      TourApiCollectionPlanService planService) {
    this.collectionService = collectionService;
    this.properties = properties;
    this.normalizationQueryService = normalizationQueryService;
    this.planService = planService;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (properties.mode() == Mode.NORMALIZE) {
      normalizationQueryService.previewTourApi(properties.limit());
      return;
    }
    var result =
        switch (properties.mode()) {
          case SAMPLE -> planService.collect(properties.toScopes(), properties.limit(), false);
          case FULL -> planService.collect(properties.toScopes(), properties.limit(), true);
          case RETRY_FAILED -> collectionService.retryFailed(properties.limit());
          case NORMALIZE -> throw new IllegalStateException("정제 모드는 수집을 수행할 수 없습니다.");
        };
    if ((properties.mode() == Mode.SAMPLE && result.attempted() == 0)
        || result.failed() > 0
        || result.stoppedEarly()) {
      throw new IllegalStateException(
          "장소 원본 수집 미완료: succeeded=" + result.succeeded() + ", failed=" + result.failed());
    }
  }
}
