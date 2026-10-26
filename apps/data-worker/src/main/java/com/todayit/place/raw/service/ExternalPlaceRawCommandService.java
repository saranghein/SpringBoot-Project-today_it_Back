package com.todayit.place.raw.service;

import com.todayit.place.client.external.PlaceSourceException.Reason;
import com.todayit.place.raw.repository.ExternalPlaceRawRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 외부 호출이 끝난 결과만 짧은 DB 트랜잭션으로 저장합니다. */
@Service
public class ExternalPlaceRawCommandService {

  private final ExternalPlaceRawRepository repository;

  /**
   * 저장 구현을 주입합니다.
   *
   * @param repository 원본 저장소
   */
  public ExternalPlaceRawCommandService(ExternalPlaceRawRepository repository) {
    this.repository = repository;
  }

  /**
   * 동일 공급원·장소의 완전한 성공 원본을 저장합니다.
   *
   * @param provider 공급원 코드
   * @param sourceId 공급원 장소 ID
   * @param payload 완전한 원본 JSON
   * @param collectedAt 수집 완료 시각
   */
  @Transactional
  public void saveSuccess(String provider, String sourceId, String payload, Instant collectedAt) {
    repository.upsertSuccess(provider, sourceId, payload, collectedAt);
  }

  /**
   * 이전 성공 원본을 보존하며 실패를 기록합니다. DB 오류는 수집 실패로 치환하지 않고 전파합니다.
   *
   * @param provider 공급원 코드
   * @param sourceId 공급원 장소 ID
   * @param listPayload 첫 수집 실패 시 재수집을 위한 목록 원본
   * @param reason 비밀정보가 없는 실패 분류
   */
  @Transactional
  public void saveFailure(String provider, String sourceId, String listPayload, Reason reason) {
    repository.upsertFailure(provider, sourceId, listPayload, reason.name());
  }
}
