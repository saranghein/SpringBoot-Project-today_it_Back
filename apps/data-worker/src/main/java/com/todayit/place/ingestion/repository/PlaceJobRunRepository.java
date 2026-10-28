package com.todayit.place.ingestion.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 원본과 비밀값 없이 정기 작업의 집계와 상태를 저장합니다. */
@Repository
@ConditionalOnProperty(name = "todayit.place.schedule.enabled", havingValue = "true")
public class PlaceJobRunRepository {

  private final JdbcTemplate jdbc;

  /**
   * 작업 이력 저장에 사용할 JDBC 도구를 받습니다.
   *
   * @param jdbc 동일 DB의 JDBC 도구
   */
  public PlaceJobRunRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** 시작 전에 이력·실패 테이블 Migration 적용 여부를 확인합니다. */
  public void verifyReady() {
    jdbc.queryForList("SELECT place_job_run_id FROM place_job_run WHERE FALSE");
    jdbc.queryForList("SELECT external_place_raw_id FROM place_ingestion_failure WHERE FALSE");
  }

  /**
   * 실행 시작을 기록합니다.
   *
   * @param jobType 작업 코드
   * @return 실행 식별자
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public long start(String jobType) {
    return jdbc.queryForObject(
        "INSERT INTO place_job_run (job_type) VALUES (?) RETURNING place_job_run_id",
        Long.class,
        jobType);
  }

  /**
   * 적재가 나중에 실패해도 확인할 수 있도록 완료된 수집 집계를 기록합니다.
   *
   * @param id 실행 식별자
   * @param json 비밀값 없는 집계 JSON
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void collected(long id, String json) {
    jdbc.update(
        "UPDATE place_job_run SET collection_result = CAST(? AS jsonb) WHERE place_job_run_id = ? AND status = 'RUNNING'",
        json,
        id);
  }

  /**
   * 실행의 마지막 상태를 기록합니다.
   *
   * @param id 실행 식별자
   * @param status SUCCESS 또는 FAILED
   * @param errorCode 안전한 실패 코드, 성공 시 null
   * @param ingestionJson 적재 집계, 적재가 중단됐으면 null
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void finish(long id, String status, String errorCode, String ingestionJson) {
    jdbc.update(
        "UPDATE place_job_run SET status = ?, error_code = ?, ingestion_result = CAST(? AS jsonb), finished_at = CURRENT_TIMESTAMP WHERE place_job_run_id = ? AND status = 'RUNNING'",
        status,
        errorCode,
        ingestionJson,
        id);
  }
}
