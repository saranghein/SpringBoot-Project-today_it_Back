package com.todayit.place.ingestion.repository;

import com.todayit.place.ingestion.service.model.PlaceIngestionRetryTarget;
import com.todayit.place.raw.service.model.RawPlaceSnapshot;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 미해결 장소 적재 실패를 기록하고, 현재 수집 상태가 SUCCESS인 원본을 재적재 대상으로 조회합니다. */
@Repository
@ConditionalOnProperty(name = "todayit.place.upsert.enabled", havingValue = "true")
public class PlaceIngestionFailureRepository {

  private final JdbcTemplate jdbc;

  /**
   * 적재 실패 기록·조회에 사용할 JDBC 도구를 주입합니다.
   *
   * @param jdbc worker의 JDBC 도구
   */
  public PlaceIngestionFailureRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 최근 실패 분류와 누적 실패 횟수를 원자적으로 기록합니다.
   *
   * @param rawId 원본 PK
   * @param errorCode 안전한 실패 분류
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void record(long rawId, String errorCode) {
    jdbc.update(
        """
        INSERT INTO place_ingestion_failure (external_place_raw_id, error_code)
        VALUES (?, ?)
        ON CONFLICT (external_place_raw_id) DO UPDATE
        SET error_code = EXCLUDED.error_code,
            failure_count = place_ingestion_failure.failure_count + 1,
            last_failed_at = CURRENT_TIMESTAMP
        """,
        rawId,
        errorCode);
  }

  /**
   * 장소 반영 트랜잭션과 함께 미해결 실패를 제거합니다.
   *
   * @param rawId 반영 성공한 원본 PK
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void clear(long rawId) {
    jdbc.update("DELETE FROM place_ingestion_failure WHERE external_place_raw_id = ?", rawId);
  }

  /**
   * 실행 시작 시 재처리할 실패 목록의 상한을 조회합니다.
   *
   * @param provider 공급원 코드
   * @return 원본이 현재 SUCCESS인 미해결 실패 행의 최대 PK, 없으면 0
   */
  public long findUpperBound(String provider) {
    return jdbc.queryForObject(
        """
        SELECT COALESCE(MAX(f.place_ingestion_failure_id), 0)
        FROM place_ingestion_failure f
        JOIN external_place_raw r ON r.external_place_raw_id = f.external_place_raw_id
        WHERE r.provider = ? AND r.status = 'SUCCESS'
        """,
        Long.class,
        provider);
  }

  /**
   * 재적재 성공으로 실패 행이 삭제돼도 다음 대상을 건너뛰지 않도록 실패 PK로 순차 조회합니다. 원본 내용과 수집 상태는 조회 시점의 값을 사용합니다.
   *
   * @param provider 공급원 코드
   * @param afterId 직전 묶음의 마지막 실패 PK, 최초 0
   * @param upperBound 실행 시작 시 고정한 실패 PK 상한, 포함
   * @param limit 최대 조회 건수
   * @return 실패 PK 오름차순의 재적재 대상과 조회 시점의 성공 원본 스냅샷
   */
  public List<PlaceIngestionRetryTarget> findAfter(
      String provider, long afterId, long upperBound, int limit) {
    return jdbc.query(
        """
        SELECT f.place_ingestion_failure_id, r.external_place_raw_id, r.provider,
               r.source_id, r.status, r.payload::text AS payload
        FROM place_ingestion_failure f
        JOIN external_place_raw r ON r.external_place_raw_id = f.external_place_raw_id
        WHERE r.provider = ? AND r.status = 'SUCCESS'
          AND f.place_ingestion_failure_id > ? AND f.place_ingestion_failure_id <= ?
        ORDER BY f.place_ingestion_failure_id LIMIT ?
        """,
        (row, index) ->
            new PlaceIngestionRetryTarget(
                row.getLong("place_ingestion_failure_id"),
                new RawPlaceSnapshot(
                    row.getLong("external_place_raw_id"),
                    row.getString("provider"),
                    row.getString("source_id"),
                    row.getString("status"),
                    row.getString("payload"))),
        provider,
        afterId,
        upperBound,
        limit);
  }
}
