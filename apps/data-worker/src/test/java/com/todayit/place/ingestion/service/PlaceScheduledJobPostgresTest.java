package com.todayit.place.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.todayit.dataworker.DataWorkerApplication;
import com.todayit.place.ingestion.service.PlaceScheduledJobService.JobType;
import com.todayit.place.ingestion.service.model.PlaceIngestionResult;
import com.todayit.place.raw.service.TourApiCollectionPlanService;
import com.todayit.place.raw.service.model.RawCollectionResult;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 실제 사용자 DB·공급원 없이 스케줄 구성·실행 이력·PostgreSQL 세션 잠금을 검증합니다. */
@Testcontainers
@SpringBootTest(
    classes = DataWorkerApplication.class,
    properties = {
      "spring.flyway.enabled=true", "spring.flyway.locations=classpath:place-service-migration",
      "spring.jpa.hibernate.ddl-auto=validate", "todayit.place.schedule.enabled=true",
      "todayit.place.schedule.collect-cron=0 0 0 1 1 *", "todayit.place.schedule.retry-cron=-",
      "todayit.place.collection.enabled=false", "todayit.place.upsert.enabled=true",
      "todayit.place.upsert.run-enabled=false", "todayit.place.sources.tourapi.enabled=true",
      "todayit.place.sources.redtable.enabled=false", "todayit.place.sources.culture.enabled=false"
    })
class PlaceScheduledJobPostgresTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @MockitoBean private TourApiCollectionPlanService collection;
  @MockitoBean private PlaceIngestionService ingestion;
  @Autowired private PlaceScheduledJobService jobs;
  @Autowired private PlaceJobLock lock;
  @Autowired private PlaceJobHistoryService history;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;

  /**
   * 테스트 전용 PostgreSQL 접속 정보를 등록합니다.
   *
   * @param registry 테스트 속성 Registry
   */
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  /** 실행 이력을 테스트마다 정리합니다. */
  @BeforeEach
  void clearRuns() {
    jdbc.update("DELETE FROM place_job_run");
  }

  /** 정상 전체 수집 뒤에만 적재하고 집계·성공 상태를 커밋하는지 확인합니다. */
  @Test
  void collectionThenIngestionAreOrderedAndRecorded() {
    when(collection.collect(anyList(), anyInt(), eq(true)))
        .thenReturn(new RawCollectionResult(3, 3, 0));
    when(ingestion.ingestAllTourApi(10)).thenReturn(new PlaceIngestionResult(3, 2, 0, 1, 0, 0));

    assertThat(jobs.execute(JobType.COLLECT_AND_INGEST)).isTrue();

    var order = inOrder(collection, ingestion);
    order.verify(collection).collect(anyList(), eq(10), eq(true));
    order.verify(ingestion).ingestAllTourApi(10);
    var row =
        jdbc.queryForMap(
            "SELECT status, error_code, finished_at, collection_result->>'succeeded' AS collected, ingestion_result->>'stored' AS stored FROM place_job_run");
    assertThat(row)
        .containsEntry("status", "SUCCESS")
        .containsEntry("error_code", null)
        .containsEntry("collected", "3")
        .containsEntry("stored", "2");
    assertThat(row.get("finished_at")).isNotNull();
  }

  /** 일반 상세 실패가 있어도 성공 원본 적재를 진행하고 전체 실행은 실패로 표시하는지 확인합니다. */
  @Test
  void individualCollectionFailureStillIngestsSuccessesAndRecordsFailedRun() {
    when(collection.collect(anyList(), anyInt(), eq(true)))
        .thenReturn(new RawCollectionResult(3, 2, 1));
    when(ingestion.ingestAllTourApi(10)).thenReturn(new PlaceIngestionResult(2, 2, 0, 0, 0, 0));

    jobs.execute(JobType.COLLECT_AND_INGEST);

    assertThat(
            jdbc.queryForMap(
                "SELECT status, error_code, ingestion_result->>'stored' AS stored FROM place_job_run"))
        .containsEntry("status", "FAILED")
        .containsEntry("error_code", "PARTIAL_FAILURE")
        .containsEntry("stored", "2");
  }

  /** 인증·쿼터로 중단한 수집 뒤에는 적재를 호출하지 않는지 확인합니다. */
  @Test
  void stoppedCollectionNeverStartsIngestion() {
    when(collection.collect(anyList(), anyInt(), eq(true)))
        .thenReturn(new RawCollectionResult(1, 0, 1, true));
    jobs.execute(JobType.COLLECT_AND_INGEST);
    verifyNoInteractions(ingestion);
    assertThat(jdbc.queryForMap("SELECT status, error_code, ingestion_result FROM place_job_run"))
        .containsEntry("status", "FAILED")
        .containsEntry("error_code", "COLLECTION_STOPPED")
        .containsEntry("ingestion_result", null);
  }

  /** 목록·DB 오류를 기록하고 후속 적재를 생략하며 다음 주기의 재처리는 가능한지 확인합니다. */
  @Test
  void collectionExceptionIsRecordedAndNextJobCanRun() {
    when(collection.collect(anyList(), anyInt(), eq(true)))
        .thenThrow(new IllegalStateException("private-value"));
    jobs.execute(JobType.COLLECT_AND_INGEST);
    assertThat(jdbc.queryForObject("SELECT error_code FROM place_job_run", String.class))
        .isEqualTo("COLLECTION_ERROR");
    verifyNoInteractions(ingestion);
    when(ingestion.retryFailedTourApi(10)).thenReturn(new PlaceIngestionResult(0, 0, 0, 0, 0, 0));
    assertThat(jobs.execute(JobType.RETRY_FAILED)).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM place_job_run WHERE status = 'SUCCESS'", Integer.class))
        .isEqualTo(1);
  }

  /** 재처리는 외부 수집 없이 실패 원본만 처리하는지 확인합니다. */
  @Test
  void retryJobDoesNotCallExternalCollection() {
    when(ingestion.retryFailedTourApi(10)).thenReturn(new PlaceIngestionResult(2, 1, 0, 0, 0, 1));
    jobs.execute(JobType.RETRY_FAILED);
    verifyNoInteractions(collection);
    assertThat(jdbc.queryForMap("SELECT job_type, status, error_code FROM place_job_run"))
        .containsEntry("job_type", "RETRY_FAILED")
        .containsEntry("status", "FAILED")
        .containsEntry("error_code", "PARTIAL_FAILURE");
  }

  /**
   * 서로 다른 실행 인스턴스가 같은 잠금을 공유하고 HTTP 대기 중 DB 트랜잭션을 유지하지 않는지 확인합니다.
   *
   * @throws Exception 비동기 테스트 실행에 실패할 때
   */
  @Test
  void lockPreventsOverlapWithoutKeepingDatabaseTransactionOpen() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var outsider = independentLock();
    try (var executor = Executors.newSingleThreadExecutor()) {
      var running =
          executor.submit(
              () ->
                  lock.runIfAvailable(
                      () -> {
                        entered.countDown();
                        try {
                          if (!release.await(20, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test timeout");
                          }
                        } catch (InterruptedException exception) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException("test interrupted");
                        }
                      }));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(
                outsider.runIfAvailable(
                    () -> {
                      throw new AssertionError("must skip");
                    }))
            .isFalse();
        assertThat(
                jdbc.queryForObject(
                    "SELECT bool_and(a.xact_start IS NULL) FROM pg_locks l JOIN pg_stat_activity a ON a.pid = l.pid WHERE l.locktype = 'advisory' AND l.granted AND a.datname = current_database()",
                    Boolean.class))
            .isTrue();
      } finally {
        release.countDown();
      }
      assertThat(running.get(10, TimeUnit.SECONDS)).isTrue();
    }
    assertThat(outsider.runIfAvailable(() -> {})).isTrue();
  }

  /** 예외 종료 뒤에도 풀에 잠금이 남지 않고 독립 연결이 다시 획득하는지 확인합니다. */
  @Test
  void thrownJobAlwaysReleasesSessionLock() {
    assertThatThrownBy(
            () ->
                lock.runIfAvailable(
                    () -> {
                      throw new IllegalStateException("fixture");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(independentLock().runIfAvailable(() -> {})).isTrue();
  }

  /** 이력 시작은 바깥 트랜잭션 롤백과 독립적으로 남는지 확인합니다. */
  @Test
  void runHistoryIsCommittedIndependently() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              history.start(JobType.RETRY_FAILED.name());
              status.setRollbackOnly();
            });
    assertThat(jdbc.queryForObject("SELECT status FROM place_job_run", String.class))
        .isEqualTo("RUNNING");
  }

  /**
   * 풀에서 같은 물리 연결을 재사용하지 않는 독립 잠금 인스턴스를 만듭니다.
   *
   * @return 독립 연결의 잠금 서비스
   */
  private PlaceJobLock independentLock() {
    return new PlaceJobLock(
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
  }
}
