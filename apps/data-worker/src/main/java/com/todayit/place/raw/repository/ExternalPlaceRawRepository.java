package com.todayit.place.raw.repository;

import com.todayit.place.raw.entity.ExternalPlaceRaw;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 공급원·장소 유일 제약을 기준으로 PostgreSQL에서 원본을 원자적으로 저장합니다. */
public interface ExternalPlaceRawRepository extends JpaRepository<ExternalPlaceRaw, Long> {

  /**
   * 공급원의 장소 원본을 조회합니다.
   *
   * @param provider 공급원 코드
   * @param sourceId 공급원 장소 ID
   * @return 저장된 원본
   */
  Optional<ExternalPlaceRaw> findByProviderAndSourceId(String provider, String sourceId);

  /**
   * 실행 시작 시 재수집할 원본을 PK 순서로 제한 조회합니다. 상태 변경 중 offset 순회는 하지 않습니다.
   *
   * @param provider 공급원 코드
   * @param status 수집 상태
   * @param pageable 첫 페이지와 최대 조회 건수
   * @return 조건에 일치하는 원본
   */
  List<ExternalPlaceRaw> findByProviderAndStatusOrderByIdAsc(
      String provider, String status, Pageable pageable);

  /**
   * 조회 시점의 성공 원본 최대 PK를 반환합니다. 전체 적재 호출자가 이 값을 실행 범위의 상한으로 사용합니다.
   *
   * @param provider 공급원 코드
   * @return 성공 원본의 최대 ID, 대상이 없으면 0
   */
  @Query(
      "select coalesce(max(raw.id), 0) from ExternalPlaceRaw raw where raw.provider = :provider and raw.status = 'SUCCESS'")
  long findSucceededUpperBound(@Param("provider") String provider);

  /**
   * 직전 처리 위치보다 크고 상한 이하인 성공 원본을 PK 순서로 조회합니다.
   *
   * @param provider 공급원 코드
   * @param afterId 직전 묶음의 마지막 원본 ID, 최초 0
   * @param upperBound 실행 시작 시 고정한 ID 상한
   * @param pageable 첫 페이지와 묶음 크기
   * @return ID 오름차순 성공 원본
   */
  @Query(
      "select raw from ExternalPlaceRaw raw where raw.provider = :provider and raw.status = 'SUCCESS' and raw.id > :afterId and raw.id <= :upperBound order by raw.id")
  List<ExternalPlaceRaw> findSucceededAfter(
      @Param("provider") String provider,
      @Param("afterId") long afterId,
      @Param("upperBound") long upperBound,
      Pageable pageable);

  /**
   * 성공 원본을 교체하며 이전 실패 분류를 지웁니다.
   *
   * @param provider 공급원 코드
   * @param sourceId 공급원 장소 ID
   * @param payload 완전한 원본 JSON
   * @param collectedAt 모든 구성 요소를 성공적으로 수집한 시각
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
      INSERT INTO external_place_raw (provider, source_id, payload, status, collected_at, updated_at)
      VALUES (:provider, :sourceId, CAST(:payload AS jsonb), 'SUCCESS', :collectedAt, CURRENT_TIMESTAMP)
      ON CONFLICT (provider, source_id) DO UPDATE
      SET payload = EXCLUDED.payload, status = 'SUCCESS', error_code = NULL,
          collected_at = EXCLUDED.collected_at, updated_at = CURRENT_TIMESTAMP
      """,
      nativeQuery = true)
  void upsertSuccess(
      @Param("provider") String provider,
      @Param("sourceId") String sourceId,
      @Param("payload") String payload,
      @Param("collectedAt") Instant collectedAt);

  /**
   * 첫 실패에는 재수집용 목록을 보존하고, 기존 행에서는 payload와 최근 성공 시각을 변경하지 않습니다.
   *
   * @param provider 공급원 코드
   * @param sourceId 공급원 장소 ID
   * @param listPayload 첫 실패 시 보관할 목록 원본 JSON
   * @param errorCode 안전한 실패 분류
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
      INSERT INTO external_place_raw (provider, source_id, payload, status, error_code, updated_at)
      VALUES (:provider, :sourceId, CAST(:listPayload AS jsonb), 'FAILED', :errorCode, CURRENT_TIMESTAMP)
      ON CONFLICT (provider, source_id) DO UPDATE
      SET status = 'FAILED', error_code = EXCLUDED.error_code, updated_at = CURRENT_TIMESTAMP
      """,
      nativeQuery = true)
  void upsertFailure(
      @Param("provider") String provider,
      @Param("sourceId") String sourceId,
      @Param("listPayload") String listPayload,
      @Param("errorCode") String errorCode);
}
