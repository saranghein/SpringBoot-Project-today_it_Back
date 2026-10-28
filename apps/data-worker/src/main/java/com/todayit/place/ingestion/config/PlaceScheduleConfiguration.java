package com.todayit.place.ingestion.config;

import com.todayit.place.ingestion.service.PlaceJobHistoryService;
import com.todayit.place.ingestion.service.PlaceScheduledJobService;
import com.todayit.place.ingestion.service.PlaceScheduledJobService.JobType;
import com.todayit.place.raw.config.RawCollectionProperties;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;

/** 정기 실행을 명시적으로 켠 경우에만 스케줄러와 상시 실행 스레드를 등록합니다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({
  PlaceScheduleProperties.class,
  RawCollectionProperties.class,
  PlaceUpsertProperties.class
})
@ConditionalOnProperty(name = "todayit.place.schedule.enabled", havingValue = "true")
public class PlaceScheduleConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(PlaceScheduleConfiguration.class);
  private final PlaceScheduledJobService jobs;

  /**
   * 수동 실행과의 충돌 및 필요한 설정·Migration을 작업 시작 전에 검사합니다.
   *
   * @param schedule 시간대와 cron 설정
   * @param collection 수집 목록·페이지 크기
   * @param upsert 적재 기능·묶음 크기
   * @param tourApiEnabled 공급원 활성 여부
   * @param history 필요한 DB 테이블 준비 검사
   * @param jobs 정기 작업 조정 서비스
   * @throws IllegalArgumentException 수동 모드 충돌·주기·시간대·크기·수집 목록이 잘못됐을 때
   */
  public PlaceScheduleConfiguration(
      PlaceScheduleProperties schedule,
      RawCollectionProperties collection,
      PlaceUpsertProperties upsert,
      @Value("${todayit.place.sources.tourapi.enabled:false}") boolean tourApiEnabled,
      PlaceJobHistoryService history,
      PlaceScheduledJobService jobs) {
    if (collection.enabled() || upsert.runEnabled() || !upsert.enabled() || !tourApiEnabled) {
      throw new IllegalArgumentException("정기 실행은 수동 Runner를 끄고 적재 기능과 TourAPI를 켜야 합니다.");
    }
    if (collection.limit() < 1
        || collection.limit() > 10
        || upsert.limit() < 1
        || upsert.limit() > 10) {
      throw new IllegalArgumentException("수집·적재 묶음 크기는 1~10이어야 합니다.");
    }
    collection.toScopes();
    ZoneId.of(schedule.zone());
    if (schedule.collectCron().equals("-") && schedule.retryCron().equals("-")) {
      throw new IllegalArgumentException("정기 작업 주기를 하나 이상 활성화해야 합니다.");
    }
    validateCron(schedule.collectCron());
    validateCron(schedule.retryCron());
    history.verifyReady();
    this.jobs = jobs;
  }

  /**
   * 자동 실행 모드에서 JVM이 유지되도록 비데몬 스레드를 사용합니다.
   *
   * @return 종료 시 작업 정리를 기다리는 스케줄러
   */
  @Bean
  public ThreadPoolTaskScheduler placeTaskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(2);
    scheduler.setDaemon(false);
    scheduler.setThreadNamePrefix("place-schedule-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    scheduler.setAwaitTerminationSeconds(30);
    return scheduler;
  }

  /** 설정한 시각에 대상 목록 전체 수집과 장소 적재를 실행합니다. */
  @Scheduled(
      cron = "${todayit.place.schedule.collect-cron:0 0 3 * * *}",
      zone = "${todayit.place.schedule.zone:Asia/Seoul}")
  public void collectAndIngest() {
    execute(JobType.COLLECT_AND_INGEST);
  }

  /** 설정한 주기에 미해결 장소 저장 실패만 재적재합니다. */
  @Scheduled(
      cron = "${todayit.place.schedule.retry-cron:0 0 */6 * * *}",
      zone = "${todayit.place.schedule.zone:Asia/Seoul}")
  public void retryFailed() {
    execute(JobType.RETRY_FAILED);
  }

  /**
   * 작업 경계의 오류를 비밀값 없이 기록하고 스케줄러의 다음 실행을 유지합니다.
   *
   * @param type 실행할 작업
   */
  private void execute(JobType type) {
    try {
      jobs.execute(type);
    } catch (RuntimeException exception) {
      LOG.error(
          "PLACE_JOB_BOUNDARY_FAILED 정기 작업 경계 실패 작업={} 오류유형={}",
          type,
          exception.getClass().getSimpleName());
    }
  }

  /**
   * 사용하지 않을 작업의 '-' 또는 유효한 Spring cron인지 검사합니다.
   *
   * @param cron 작업 주기
   */
  private void validateCron(String cron) {
    if (!cron.equals("-")) {
      CronExpression.parse(cron);
    }
  }
}
