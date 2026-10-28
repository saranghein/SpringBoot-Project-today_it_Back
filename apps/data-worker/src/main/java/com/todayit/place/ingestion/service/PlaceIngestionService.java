package com.todayit.place.ingestion.service;

import com.todayit.place.ingestion.service.PlaceIngestionFailureService.Code;
import com.todayit.place.ingestion.service.model.PlaceIngestionResult;
import com.todayit.place.raw.service.ExternalPlaceRawQueryService;
import com.todayit.place.raw.service.TourApiPlaceNormalizationService;
import com.todayit.place.raw.service.model.PlaceNormalizationResult.Status;
import com.todayit.place.raw.service.model.RawPlaceSnapshot;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
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
  private final PlaceIngestionFailureService failureService;

  /**
   * 읽기·정제·장소별 저장을 수행할 서비스를 주입합니다.
   *
   * @param rawQueryService 성공 원본 조회
   * @param normalizer 원본 정제
   * @param commandService 별도 트랜잭션의 서비스 장소 저장
   * @param failureService 독립적인 실패 기록과 재처리 대상 조회
   */
  public PlaceIngestionService(
      ExternalPlaceRawQueryService rawQueryService,
      TourApiPlaceNormalizationService normalizer,
      PlaceUpsertCommandService commandService,
      PlaceIngestionFailureService failureService) {
    this.rawQueryService = rawQueryService;
    this.normalizer = normalizer;
    this.commandService = commandService;
    this.failureService = failureService;
  }

  /**
   * PK 순서의 첫 성공 원본 묶음을 조회하고 장소별로 적재합니다. 장소 저장 실패는 별도 트랜잭션에 기록한 뒤 다음 원본을 처리하며, 실패 기록 자체가 실패하면 실행을
   * 중단합니다.
   *
   * @param limit 이번 실행에서 처리할 최대 원본 수, 1~10
   * @return 커밋 성공·제외·검수·지역 보류·저장 실패 집계
   * @throws IllegalArgumentException 처리 건수가 범위를 벗어날 때
   * @throws IllegalStateException 저장 서비스가 정상 정제 결과를 예기치 않게 건너뛰었을 때
   */
  @Transactional(propagation = Propagation.NEVER)
  public PlaceIngestionResult ingestTourApi(int limit) {
    validateLimit(limit);
    var result = ingestBatch(rawQueryService.findSucceeded("TOUR_API", limit));
    logResult(result);
    return result;
  }

  /**
   * 시작 시 성공 원본의 ID 상한을 정하고 묶음별로 순차 적재합니다. 보류되거나 저장에 실패한 원본을 지나 다음 묶음으로 진행합니다. 원본 조회나 실패 기록의 DB 오류는
   * 실행을 중단합니다. 상한보다 큰 신규 원본은 다음 실행에 처리하며, 원본 내용은 각 조회 시점의 값을 사용합니다.
   *
   * @param batchSize 한 번에 읽을 원본 수, 1~10
   * @return 전체 실행의 커밋 성공·보류·실패 집계
   * @throws IllegalArgumentException 묶음 크기가 범위를 벗어날 때
   * @throws IllegalStateException 조회 ID가 전진하지 않거나 저장이 예기치 않게 건너뛰어졌을 때
   */
  @Transactional(propagation = Propagation.NEVER)
  public PlaceIngestionResult ingestAllTourApi(int batchSize) {
    validateLimit(batchSize);
    long upperBound = rawQueryService.findSucceededUpperBound("TOUR_API");
    long afterId = 0;
    var total = new PlaceIngestionResult(0, 0, 0, 0, 0, 0);
    while (afterId < upperBound) {
      var snapshots =
          rawQueryService.findSucceededAfter("TOUR_API", afterId, upperBound, batchSize);
      if (snapshots.isEmpty()) {
        break;
      }
      long nextId = snapshots.getLast().rawId();
      if (nextId <= afterId || nextId > upperBound) {
        throw new IllegalStateException("원본 조회 ID가 실행 범위 내에서 전진해야 합니다.");
      }
      var batch = ingestBatch(snapshots);
      total = addResults(total, batch);
      afterId = nextId;
      LOG.info(
          "PLACE_INGESTION_BATCH 장소 묶음 처리 공급원=TOUR_API 마지막원본번호={} 상한={} 누적처리={} 누적저장={} 누적실패={}",
          afterId,
          upperBound,
          total.total(),
          total.stored(),
          total.failed());
    }
    logResult(total);
    return total;
  }

  /**
   * 시작 시 실패 PK 상한을 정하고, 원본의 현재 수집 상태가 SUCCESS인 미해결 적재 실패를 순차 재처리합니다. 재실패해도 같은 실행에서는 다시 선택하지 않으며,
   * 보류된 원본의 실패 기록은 유지합니다. 외부 API는 호출하지 않습니다.
   *
   * @param batchSize 실패 조회 묶음 크기, 1~10
   * @return 성공·보류·재실패 집계
   * @throws IllegalArgumentException 묶음 크기가 잘못됐을 때
   * @throws IllegalStateException 실패 목록 ID가 범위 내에서 전진하지 않거나 정상 정제 결과의 저장이 건너뛰어졌을 때
   */
  @Transactional(propagation = Propagation.NEVER)
  public PlaceIngestionResult retryFailedTourApi(int batchSize) {
    validateLimit(batchSize);
    long upperBound = failureService.findUpperBound("TOUR_API");
    long afterId = 0;
    var total = new PlaceIngestionResult(0, 0, 0, 0, 0, 0);
    while (afterId < upperBound) {
      var targets = failureService.findAfter("TOUR_API", afterId, upperBound, batchSize);
      if (targets.isEmpty()) {
        break;
      }
      long nextId = targets.getLast().failureId();
      if (nextId <= afterId || nextId > upperBound) {
        throw new IllegalStateException("실패 목록 ID가 실행 범위 내에서 전진해야 합니다.");
      }
      total = addResults(total, ingestBatch(targets.stream().map(target -> target.raw()).toList()));
      afterId = nextId;
      LOG.info(
          "PLACE_INGESTION_RETRY 실패 재적재 진행 공급원=TOUR_API 마지막실패번호={} 상한={} 누적처리={} 저장={} 실패={}",
          afterId,
          upperBound,
          total.total(),
          total.stored(),
          total.failed());
    }
    logResult(total);
    return total;
  }

  /**
   * 순차 처리한 묶음의 집계를 누적합니다.
   *
   * @param total 이전 누적 결과
   * @param batch 이번 묶음 결과
   * @return 합산 결과
   */
  private PlaceIngestionResult addResults(PlaceIngestionResult total, PlaceIngestionResult batch) {
    return new PlaceIngestionResult(
        total.total() + batch.total(),
        total.stored() + batch.stored(),
        total.excluded() + batch.excluded(),
        total.reviewRequired() + batch.reviewRequired(),
        total.regionMissing() + batch.regionMissing(),
        total.failed() + batch.failed());
  }

  /**
   * 모든 실행 모드의 묶음 크기를 DB 접근 전에 검사합니다.
   *
   * @param limit 원본 조회 크기
   * @throws IllegalArgumentException 1~10 범위를 벗어날 때
   */
  private void validateLimit(int limit) {
    if (limit < 1 || limit > 10) {
      throw new IllegalArgumentException("장소 적재 크기는 1~10이어야 합니다.");
    }
  }

  /**
   * 한 묶음을 순서대로 정제·저장합니다. 장소 저장 실패는 별도 트랜잭션으로 기록하며, 기록에 실패하면 호출자에게 전파합니다.
   *
   * @param snapshots 이번 묶음의 성공 원본
   * @return 묶음 처리 집계
   * @throws IllegalStateException 정상 정제 결과가 예기치 않게 저장을 건너뛰었을 때
   */
  private PlaceIngestionResult ingestBatch(List<RawPlaceSnapshot> snapshots) {
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
        Code code =
            exception instanceof DataIntegrityViolationException
                ? Code.DATA_INTEGRITY
                : exception instanceof TransactionException ? Code.TRANSACTION : Code.DATA_ACCESS;
        failureService.recordFailure(snapshot.rawId(), code);
        failed++;
        LOG.warn(
            "PLACE_INGESTION_ITEM_FAILED 장소 적재 실패 공급원=TOUR_API 원본번호={} 오류유형={}",
            snapshot.rawId(),
            exception.getClass().getSimpleName());
      }
    }
    return new PlaceIngestionResult(
        snapshots.size(), stored, excluded, reviewRequired, regionMissing, failed);
  }

  /**
   * 정상적으로 순회를 마친 실행의 전체 집계를 기록합니다.
   *
   * @param result 표본 또는 전체 순회 결과
   */
  private void logResult(PlaceIngestionResult result) {
    LOG.info(
        "PLACE_INGESTION_RESULT 장소 적재 완료 공급원=TOUR_API 전체={} 저장={} 제외={} 검수={} 지역보류={} 실패={}",
        result.total(),
        result.stored(),
        result.excluded(),
        result.reviewRequired(),
        result.regionMissing(),
        result.failed());
  }
}
