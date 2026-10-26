package com.todayit.place.ingestion.service;

import com.todayit.place.ingestion.service.model.PlaceIngestionResult;
import com.todayit.place.raw.service.ExternalPlaceRawQueryService;
import com.todayit.place.raw.service.TourApiPlaceNormalizationService;
import com.todayit.place.raw.service.model.PlaceNormalizationResult.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 성공 원본 조회·정제·장소별 커밋을 조정합니다. 원본은 읽기만 하며 외부 공급원은 호출하지 않습니다. */
@Service
@ConditionalOnProperty(name = "todayit.place.upsert.enabled", havingValue = "true")
public class PlaceIngestionService {

  private static final Logger LOG = LoggerFactory.getLogger(PlaceIngestionService.class);
  private final ExternalPlaceRawQueryService rawQueryService;
  private final TourApiPlaceNormalizationService normalizer;
  private final PlaceUpsertCommandService commandService;

  /**
   * 읽기·정제·장소별 저장을 수행할 서비스를 주입합니다.
   *
   * @param rawQueryService 성공 원본 조회
   * @param normalizer 원본 정제
   * @param commandService 별도 트랜잭션의 서비스 장소 저장
   */
  public PlaceIngestionService(
      ExternalPlaceRawQueryService rawQueryService,
      TourApiPlaceNormalizationService normalizer,
      PlaceUpsertCommandService commandService) {
    this.rawQueryService = rawQueryService;
    this.normalizer = normalizer;
    this.commandService = commandService;
  }

  /**
   * 성공 원본을 한 번 조회하고 각 장소를 독립적으로 적재합니다. 실패한 DB 트랜잭션을 다음 장소에 전파하지 않습니다.
   *
   * @param limit 이번 실행에서 처리할 최대 원본 수, 1~10
   * @return 커밋 성공·제외·검수·지역 보류·저장 실패 집계
   * @throws IllegalArgumentException 처리 건수가 범위를 벗어날 때
   * @throws IllegalStateException 저장 서비스가 정상 정제 결과를 예기치 않게 건너뛰었을 때
   */
  @Transactional(propagation = Propagation.NEVER)
  public PlaceIngestionResult ingestTourApi(int limit) {
    if (limit < 1 || limit > 10) {
      throw new IllegalArgumentException("장소 적재 크기는 1~10이어야 합니다.");
    }
    var snapshots = rawQueryService.findSucceeded("TOUR_API", limit);
    int stored = 0;
    int excluded = 0;
    int reviewRequired = 0;
    int regionMissing = 0;
    int failed = 0;
    for (var snapshot : snapshots) {
      var normalized = normalizer.normalize(snapshot);
      if (normalized.status() == Status.EXCLUDED) {
        excluded++;
        continue;
      }
      if (normalized.status() == Status.REVIEW_REQUIRED) {
        reviewRequired++;
        continue;
      }
      try {
        var result = commandService.upsert(normalized);
        switch (result.status()) {
          // Spring의 별도 저장 Bean이 반환된 시점에는 해당 장소 트랜잭션의 커밋이 완료됐습니다.
          case APPLIED -> stored++;
          case REGION_NOT_FOUND -> regionMissing++;
          case NORMALIZATION_SKIPPED ->
              throw new IllegalStateException("정상 정제 결과의 저장이 예기치 않게 건너뛰어졌습니다.");
        }
      } catch (DataAccessException | TransactionException exception) {
        failed++;
        LOG.warn(
            "PLACE_INGESTION_ITEM_FAILED 장소 적재 실패 공급원=TOUR_API 원본번호={} 오류유형={}",
            snapshot.rawId(),
            exception.getClass().getSimpleName());
      }
    }
    var result =
        new PlaceIngestionResult(
            snapshots.size(), stored, excluded, reviewRequired, regionMissing, failed);
    LOG.info(
        "PLACE_INGESTION_RESULT 장소 적재 완료 공급원=TOUR_API 전체={} 저장={} 제외={} 검수={} 지역보류={} 실패={}",
        result.total(),
        result.stored(),
        result.excluded(),
        result.reviewRequired(),
        result.regionMissing(),
        result.failed());
    return result;
  }
}
