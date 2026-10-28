package com.todayit.place.ingestion.config;

import com.todayit.place.ingestion.service.PlaceIngestionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** 저장 기능과 실행 스위치를 모두 켰을 때만 DB 적재를 시작합니다. 공급원 수집 Runner와 함께 실행하지 않습니다. */
@Component
@EnableConfigurationProperties(PlaceUpsertProperties.class)
@ConditionalOnProperty(name = "todayit.place.upsert.run-enabled", havingValue = "true")
public class PlaceUpsertRunner implements ApplicationRunner {

  private final PlaceIngestionService ingestionService;
  private final PlaceUpsertProperties properties;

  /**
   * 명시적 적재 실행을 준비하고 모든 ApplicationRunner 실행 전에 잘못된 동시 실행 설정을 거부합니다.
   *
   * @param ingestionService 장소별 적재 조정 서비스
   * @param properties 저장 기능·실행·건수 설정
   * @param collectionEnabled 수집·재수집·정제 Runner 활성화 여부
   * @throws IllegalStateException 저장 기능이 비활성이거나 다른 Runner를 함께 켰을 때
   */
  public PlaceUpsertRunner(
      PlaceIngestionService ingestionService,
      PlaceUpsertProperties properties,
      @Value("${todayit.place.collection.enabled:false}") boolean collectionEnabled) {
    if (!properties.enabled()) {
      throw new IllegalStateException("적재 실행에는 todayit.place.upsert.enabled=true가 필요합니다.");
    }
    if (collectionEnabled) {
      throw new IllegalStateException("적재와 수집/정제 Runner를 동시에 실행할 수 없습니다.");
    }
    this.ingestionService = ingestionService;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    var result =
        switch (properties.mode()) {
          case SAMPLE -> ingestionService.ingestTourApi(properties.limit());
          case FULL -> ingestionService.ingestAllTourApi(properties.limit());
          case RETRY_FAILED -> ingestionService.retryFailedTourApi(properties.limit());
        };
    if (result.failed() > 0) {
      throw new IllegalStateException(
          "장소 적재 일부 실패: stored=" + result.stored() + ", failed=" + result.failed());
    }
  }
}
