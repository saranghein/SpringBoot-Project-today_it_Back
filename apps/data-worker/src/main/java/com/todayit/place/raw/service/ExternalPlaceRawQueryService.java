package com.todayit.place.raw.service;

import com.todayit.place.raw.entity.ExternalPlaceRaw;
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
   * 해당 공급원의 FAILED 원본 중 PK가 작은 첫 묶음을 조회합니다. 처리 위치를 보관하지 않아 실패가 유지된 원본은 다음 실행에서도 선택될 수 있습니다.
   *
   * @param provider 공급원 코드
   * @param limit 최대 조회 건수, 1~10
   * @return 원천 ID와 저장된 목록 JSON을 가진 재수집 후보
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
        .map(this::snapshot)
        .toList();
  }

  /**
   * 전체 적재 범위를 정할 성공 원본의 ID 상한을 조회합니다. 원본 내용의 고정 스냅샷을 만드는 것은 아닙니다.
   *
   * @param provider 공급원 코드
   * @return 최대 성공 원본 ID, 대상이 없으면 0
   */
  @Transactional(readOnly = true)
  public long findSucceededUpperBound(String provider) {
    return repository.findSucceededUpperBound(provider);
  }

  /**
   * 고정한 상한 이내에서 직전 원본 이후의 성공 원본을 조회합니다.
   *
   * @param provider 공급원 코드
   * @param afterId 직전 원본 ID, 최초 0
   * @param upperBound 실행 시작 시 정한 ID 상한, 포함
   * @param limit 묶음 크기, 1~10
   * @return 원본 PK 오름차순의 다음 성공 원본 스냅샷
   * @throws IllegalArgumentException ID 범위 또는 묶음 크기가 잘못됐을 때
   */
  @Transactional(readOnly = true)
  public List<RawPlaceSnapshot> findSucceededAfter(
      String provider, long afterId, long upperBound, int limit) {
    if (afterId < 0 || upperBound < afterId || limit < 1 || limit > 10) {
      throw new IllegalArgumentException("원본 ID 범위와 조회 크기 1~10을 확인해야 합니다.");
    }
    return repository
        .findSucceededAfter(provider, afterId, upperBound, PageRequest.of(0, limit))
        .stream()
        .map(this::snapshot)
        .toList();
  }

  /**
   * 읽기 트랜잭션이 종료되기 전에 필요한 원본 값만 복사합니다.
   *
   * @param raw 조회한 원본 Entity
   * @return 불변 원본 스냅샷
   */
  private RawPlaceSnapshot snapshot(ExternalPlaceRaw raw) {
    return new RawPlaceSnapshot(
        raw.getId(), raw.getProvider(), raw.getSourceId(), raw.getStatus(), raw.getPayload());
  }
}
