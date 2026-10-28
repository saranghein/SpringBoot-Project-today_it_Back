package com.todayit.place.ingestion.service;

import com.todayit.place.ingestion.repository.PlaceIngestionFailureRepository;
import com.todayit.place.ingestion.service.model.PlaceIngestionRetryTarget;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 장소 저장 실패를 별도 트랜잭션으로 기록하고, 재적재 대상 조회와 성공 시 실패 기록 제거를 담당합니다. */
@Service
@ConditionalOnProperty(name = "todayit.place.upsert.enabled", havingValue = "true")
public class PlaceIngestionFailureService {

  private final PlaceIngestionFailureRepository repository;

  /**
   * 미해결 적재 실패 저장소를 주입합니다.
   *
   * @param repository 실패 목록 저장소
   */
  public PlaceIngestionFailureService(PlaceIngestionFailureRepository repository) {
    this.repository = repository;
  }

  /** 저장 예외의 원문을 저장하지 않는 안정적인 분류입니다. */
  public enum Code {
    DATA_INTEGRITY,
    TRANSACTION,
    DATA_ACCESS
  }

  /**
   * 이미 롤백된 장소 트랜잭션과 독립적으로 실패를 기록합니다. 기록 자체의 DB 오류는 숨기지 않습니다.
   *
   * @param rawId 실패한 원본 PK
   * @param code 최근 실패 분류
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(long rawId, Code code) {
    repository.record(rawId, code.name());
  }

  /**
   * 장소 저장 트랜잭션 안에서 해당 원본의 실패 기록을 제거합니다. 장소 커밋에 실패하면 기록 제거도 롤백됩니다.
   *
   * @param rawId 성공한 원본 PK
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void clearFailure(long rawId) {
    repository.clear(rawId);
  }

  /**
   * 재처리 가능한 실패 목록의 실행 상한을 조회합니다.
   *
   * @param provider 공급원 코드
   * @return 원본 수집 상태가 SUCCESS인 미해결 실패의 최대 PK, 없으면 0
   */
  @Transactional(readOnly = true)
  public long findUpperBound(String provider) {
    return repository.findUpperBound(provider);
  }

  /**
   * 한 실행에서 같은 실패를 반복 선택하지 않도록 PK 범위로 제한 조회합니다.
   *
   * @param provider 공급원 코드
   * @param afterId 직전 묶음의 마지막 실패 PK, 최초 0
   * @param upperBound 시작 시 고정한 실패 PK 상한, 포함
   * @param limit 묶음 크기, 1~10
   * @return 실패 PK 오름차순의 재적재 대상과 조회 시점의 성공 원본 스냅샷
   * @throws IllegalArgumentException 범위나 묶음 크기가 잘못됐을 때
   */
  @Transactional(readOnly = true)
  public List<PlaceIngestionRetryTarget> findAfter(
      String provider, long afterId, long upperBound, int limit) {
    if (afterId < 0 || upperBound < afterId || limit < 1 || limit > 10) {
      throw new IllegalArgumentException("실패 목록의 ID 범위와 조회 크기 1~10을 확인해야 합니다.");
    }
    return repository.findAfter(provider, afterId, upperBound, limit);
  }
}
