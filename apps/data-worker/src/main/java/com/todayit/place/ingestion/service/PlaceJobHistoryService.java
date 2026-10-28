package com.todayit.place.ingestion.service;

import com.todayit.place.ingestion.repository.PlaceJobRunRepository;
import com.todayit.place.ingestion.service.model.PlaceIngestionResult;
import com.todayit.place.raw.service.model.RawCollectionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** 수집·적재 처리와 별도의 짧은 트랜잭션으로 작업 시작·수집 집계·종료 상태를 기록합니다. */
@Service
@ConditionalOnProperty(name = "todayit.place.schedule.enabled", havingValue = "true")
public class PlaceJobHistoryService {

  private final PlaceJobRunRepository repository;
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * 실행 이력 저장소를 받습니다.
   *
   * @param repository 작업 이력 저장소
   */
  public PlaceJobHistoryService(PlaceJobRunRepository repository) {
    this.repository = repository;
  }

  /** 스케줄 등록 전에 필요한 Migration을 검사합니다. */
  @Transactional(readOnly = true)
  public void verifyReady() {
    repository.verifyReady();
  }

  /**
   * 작업 시작을 별도 트랜잭션으로 커밋합니다.
   *
   * @param jobType 작업 코드
   * @return 실행 식별자
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public long start(String jobType) {
    return repository.start(jobType);
  }

  /**
   * 완료된 수집 집계를 별도로 커밋합니다.
   *
   * @param id 실행 식별자
   * @param result 수집 합계
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void collected(long id, RawCollectionResult result) {
    repository.collected(id, json.writeValueAsString(result));
  }

  /**
   * 완료 또는 실패 상태를 별도로 커밋합니다.
   *
   * @param id 실행 식별자
   * @param status SUCCESS 또는 FAILED
   * @param errorCode 안전한 실패 분류
   * @param result 적재가 완료된 경우의 합계, 아니면 null
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(long id, String status, String errorCode, PlaceIngestionResult result) {
    repository.finish(
        id, status, errorCode, result == null ? null : json.writeValueAsString(result));
  }
}
