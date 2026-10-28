package com.todayit.place.ingestion.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.todayit.place.ingestion.service.PlaceJobHistoryService;
import com.todayit.place.ingestion.service.PlaceScheduledJobService;
import com.todayit.place.ingestion.service.PlaceScheduledJobService.JobType;
import com.todayit.place.raw.config.RawCollectionProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 스케줄 기본 비활성·설정 검증과 수동 Runner 충돌 차단을 확인합니다. */
class PlaceScheduleConfigurationTest {

  private final PlaceJobHistoryService history = mock(PlaceJobHistoryService.class);
  private final PlaceScheduledJobService jobs = mock(PlaceScheduledJobService.class);
  private final PlaceScheduleProperties schedule =
      new PlaceScheduleProperties(true, "0 0 3 * * *", "0 0 */6 * * *", "Asia/Seoul");

  /** 켜지 않은 설정이 스케줄러와 작업을 등록하지 않는지 확인합니다. */
  @Test
  void defaultDoesNotRegisterSchedulingConfiguration() {
    new ApplicationContextRunner()
        .withUserConfiguration(PlaceScheduleConfiguration.class)
        .run(context -> assertThat(context).doesNotHaveBean(PlaceScheduleConfiguration.class));
  }

  /** 정상 설정에서는 저장소 준비를 확인하고 생성 자체로는 작업하지 않는지 확인합니다. */
  @Test
  void validConfigurationVerifiesDatabaseButDoesNotExecuteOnConstruction() {
    var config =
        new PlaceScheduleConfiguration(
            schedule, collection(false), upsert(true, false), true, history, jobs);
    verify(history).verifyReady();
    verifyNoInteractions(jobs);
    config.collectAndIngest();
    config.retryFailed();
    verify(jobs).execute(JobType.COLLECT_AND_INGEST);
    verify(jobs).execute(JobType.RETRY_FAILED);
  }

  /** 수동 실행·적재 비활성·공급원 비활성을 DB 검사와 작업 실행 전에 차단하는지 확인합니다. */
  @Test
  void conflictingOrDisabledFeaturesFailBeforeDatabaseAndExecution() {
    assertThatThrownBy(
            () ->
                new PlaceScheduleConfiguration(
                    schedule, collection(true), upsert(true, false), true, history, jobs))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PlaceScheduleConfiguration(
                    schedule, collection(false), upsert(true, true), true, history, jobs))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PlaceScheduleConfiguration(
                    schedule, collection(false), upsert(false, false), true, history, jobs))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PlaceScheduleConfiguration(
                    schedule, collection(false), upsert(true, false), false, history, jobs))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(history, jobs);
  }

  /** 잘못된 cron·시간대·전체 작업 비활성 설정을 시작 전에 거부하는지 확인합니다. */
  @Test
  void invalidClockSettingsFailBeforeDatabase() {
    for (var invalid :
        List.of(
            new PlaceScheduleProperties(true, "bad", "-", "Asia/Seoul"),
            new PlaceScheduleProperties(true, "-", "-", "Asia/Seoul"),
            new PlaceScheduleProperties(true, "0 0 3 * * *", "-", "bad-zone"))) {
      assertThatThrownBy(
              () ->
                  new PlaceScheduleConfiguration(
                      invalid, collection(false), upsert(true, false), true, history, jobs))
          .isInstanceOf(RuntimeException.class);
    }
    verifyNoInteractions(history, jobs);
  }

  /**
   * 기본 서울 수집 설정을 만듭니다.
   *
   * @param enabled 수동 Runner 활성 여부
   * @return 수집 설정
   */
  private RawCollectionProperties collection(boolean enabled) {
    return new RawCollectionProperties(
        enabled, 10, RawCollectionProperties.Mode.FULL, "11", "", "12", List.of());
  }

  /**
   * 적재 기능과 수동 실행 설정을 만듭니다.
   *
   * @param enabled 적재 기능 여부
   * @param runEnabled 수동 실행 여부
   * @return 적재 설정
   */
  private PlaceUpsertProperties upsert(boolean enabled, boolean runEnabled) {
    return new PlaceUpsertProperties(enabled, runEnabled, 10, PlaceUpsertProperties.Mode.FULL);
  }
}
