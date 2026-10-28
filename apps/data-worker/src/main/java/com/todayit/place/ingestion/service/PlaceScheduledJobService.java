package com.todayit.place.ingestion.service;

import com.todayit.place.ingestion.config.PlaceUpsertProperties;
import com.todayit.place.ingestion.service.model.PlaceIngestionResult;
import com.todayit.place.raw.config.RawCollectionProperties;
import com.todayit.place.raw.service.TourApiCollectionPlanService;
import com.todayit.place.raw.service.model.RawCollectionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 정기 수집·적재와 저장 실패 재처리를 한 실행 흐름으로 조정합니다. */
@Service
@ConditionalOnProperty(name = "todayit.place.schedule.enabled", havingValue = "true")
public class PlaceScheduledJobService {

  private static final Logger LOG = LoggerFactory.getLogger(PlaceScheduledJobService.class);
  private final PlaceJobLock lock;
  private final PlaceJobHistoryService history;
  private final TourApiCollectionPlanService collection;
  private final PlaceIngestionService ingestion;
  private final RawCollectionProperties collectionProperties;
  private final PlaceUpsertProperties upsertProperties;

  /** 정기 실행할 작업의 종류입니다. 두 작업은 같은 세션 잠금을 공유합니다. */
  public enum JobType {
    COLLECT_AND_INGEST,
    RETRY_FAILED
  }

  /**
   * 실행 잠금·작업 이력·범위별 수집·건별 적재 서비스와 설정을 주입합니다.
   *
   * @param lock 정기 실행 잠금
   * @param history 실행 이력
   * @param collection 목록 순차 수집
   * @param ingestion 장소 적재
   * @param collectionProperties 수집 대상과 페이지 크기
   * @param upsertProperties 적재 묶음 크기
   */
  public PlaceScheduledJobService(
      PlaceJobLock lock,
      PlaceJobHistoryService history,
      TourApiCollectionPlanService collection,
      PlaceIngestionService ingestion,
      RawCollectionProperties collectionProperties,
      PlaceUpsertProperties upsertProperties) {
    this.lock = lock;
    this.history = history;
    this.collection = collection;
    this.ingestion = ingestion;
    this.collectionProperties = collectionProperties;
    this.upsertProperties = upsertProperties;
  }

  /**
   * 잠금을 획득한 작업만 실행합니다. 이미 다른 정기 작업이 실행 중이면 이력을 만들지 않고 건너뜁니다.
   *
   * @param type 실행할 작업
   * @return 실제로 실행했는지 여부
   */
  @Transactional(propagation = Propagation.NEVER)
  public boolean execute(JobType type) {
    boolean ran = lock.runIfAvailable(() -> runJob(type));
    if (!ran) {
      LOG.info("PLACE_JOB_SKIPPED 정기 작업 중복 생략 작업={}", type);
    }
    return ran;
  }

  /**
   * 전체 수집이 조기 중단 없이 반환되면 성공 원본을 적재합니다. 개별 수집 실패가 있으면 적재 후에도 작업을 실패로 기록합니다. 이력 저장 오류는 호출자에게 전파합니다.
   *
   * @param type 잠금을 획득한 작업
   */
  private void runJob(JobType type) {
    long id = history.start(type.name());
    RawCollectionResult collected = null;
    PlaceIngestionResult ingested = null;
    String errorCode = null;
    String stage = type == JobType.COLLECT_AND_INGEST ? "COLLECTION_ERROR" : "INGESTION_ERROR";
    try {
      if (type == JobType.COLLECT_AND_INGEST) {
        collected =
            collection.collect(collectionProperties.toScopes(), collectionProperties.limit(), true);
        stage = "HISTORY_ERROR";
        history.collected(id, collected);
        if (collected.stoppedEarly()) {
          errorCode = "COLLECTION_STOPPED";
        }
      }
      if (errorCode == null) {
        stage = "INGESTION_ERROR";
        ingested =
            type == JobType.RETRY_FAILED
                ? ingestion.retryFailedTourApi(upsertProperties.limit())
                : ingestion.ingestAllTourApi(upsertProperties.limit());
        if ((collected != null && collected.failed() > 0) || ingested.failed() > 0) {
          errorCode = "PARTIAL_FAILURE";
        }
      }
    } catch (RuntimeException exception) {
      errorCode = stage;
      LOG.warn(
          "PLACE_JOB_FAILED 정기 작업 실패 실행번호={} 작업={} 단계={} 오류유형={}",
          id,
          type,
          stage,
          exception.getClass().getSimpleName());
    }
    String status = errorCode == null ? "SUCCESS" : "FAILED";
    history.finish(id, status, errorCode, ingested);
    LOG.info("PLACE_JOB_RESULT 정기 작업 완료 실행번호={} 작업={} 상태={} 오류={}", id, type, status, errorCode);
  }
}
