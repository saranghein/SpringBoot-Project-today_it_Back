package com.todayit.place.raw.service;

import com.todayit.place.raw.repository.ExternalPlaceRawRepository;
import com.todayit.place.raw.service.model.RawPlaceSnapshot;
import com.todayit.place.raw.service.model.RawRetryTarget;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 재수집·정제·적재 대상을 짧은 읽기 트랜잭션에서 조회하고 불변 스냅샷을 반환합니다. */
@Service
public class ExternalPlaceRawQueryService {

  private final ExternalPlaceRawRepository repository;

  /**
   * 원본 저장소를 주입합니다.
   *
   * @param repository 원본 저장소
   */
  public ExternalPlaceRawQueryService(ExternalPlaceRawRepository repository) {
    this.repository = repository;
  }

  /**
   * 해당 공급원의 FAILED 원본을 PK 오름차순으로 제한 조회합니다. 성공·대기 원본은 제외합니다.
   *
   * @param provider 공급원 코드
   * @param limit 최대 조회 건수, 1~10
   * @return 실행당 한 번씩 처리할 재수집 후보
   * @throws IllegalArgumentException 조회 건수가 범위를 벗어날 때
   */
  @Transactional(readOnly = true)
  public List<RawRetryTarget> findFailed(String provider, int limit) {
    if (limit < 1 || limit > 10) {
      throw new IllegalArgumentException("재수집 크기는 1~10이어야 합니다.");
    }
    return repository
        .findByProviderAndStatusOrderByIdAsc(provider, "FAILED", PageRequest.of(0, limit))
        .stream()
        .map(raw -> new RawRetryTarget(raw.getSourceId(), raw.getPayload()))
        .toList();
  }

  /**
   * 정제 미리보기와 서비스 적재에 사용할 성공 원본을 PK 순서로 제한 조회합니다. 원본 상태를 갱신하지 않습니다.
   *
   * @param provider 공급원 코드
   * @param limit 최대 조회 건수, 1~10
   * @return 성공 원본의 불변 스냅샷
   * @throws IllegalArgumentException 조회 건수가 범위를 벗어날 때
   */
  @Transactional(readOnly = true)
  public List<RawPlaceSnapshot> findSucceeded(String provider, int limit) {
    if (limit < 1 || limit > 10) {
      throw new IllegalArgumentException("성공 원본 조회 크기는 1~10이어야 합니다.");
    }
    return repository
        .findByProviderAndStatusOrderByIdAsc(provider, "SUCCESS", PageRequest.of(0, limit))
        .stream()
        .map(
            raw ->
                new RawPlaceSnapshot(
                    raw.getId(),
                    raw.getProvider(),
                    raw.getSourceId(),
                    raw.getStatus(),
                    raw.getPayload()))
        .toList();
  }
}
