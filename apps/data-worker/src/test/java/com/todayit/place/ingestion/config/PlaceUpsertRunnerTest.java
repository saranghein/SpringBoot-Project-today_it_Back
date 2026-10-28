package com.todayit.place.ingestion.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.todayit.place.ingestion.service.PlaceIngestionService;
import com.todayit.place.ingestion.service.model.PlaceIngestionResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 적재 실행 스위치와 동시 실행 차단 및 종료 결과를 검증합니다. */
class PlaceUpsertRunnerTest {

  /** 명시적 실행 스위치 없이 적재 Runner가 등록되지 않는지 검증합니다. */
  @Test
  void noRunnerWithoutExplicitRunSwitch() {
    new ApplicationContextRunner()
        .withUserConfiguration(PlaceUpsertRunner.class)
        .withPropertyValues("todayit.place.upsert.enabled=true")
        .run(context -> assertThat(context).doesNotHaveBean(PlaceUpsertRunner.class));
  }

  /** 적재 설정의 기본값이 바인딩되며 Bean 생성만으로 저장하지 않는지 검증합니다. */
  @Test
  void bothFlagsBindDefaultsButBeanCreationDoesNotWrite() {
    var service = mock(PlaceIngestionService.class);
    new ApplicationContextRunner()
        .withUserConfiguration(PlaceUpsertRunner.class)
        .withBean(PlaceIngestionService.class, () -> service)
        .withPropertyValues(
            "todayit.place.upsert.enabled=true", "todayit.place.upsert.run-enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(PlaceUpsertRunner.class);
              var properties = context.getBean(PlaceUpsertProperties.class);
              assertThat(properties.enabled()).isTrue();
              assertThat(properties.runEnabled()).isTrue();
              assertThat(properties.limit()).isEqualTo(10);
              assertThat(properties.mode()).isEqualTo(PlaceUpsertProperties.Mode.SAMPLE);
              verifyNoInteractions(service);
            });
  }

  /** 저장 기능 없이 실행 스위치만 켜면 실행 전에 시작이 실패하는지 검증합니다. */
  @Test
  void runSwitchWithoutWriteFeatureFailsBeforeExecution() {
    var service = mock(PlaceIngestionService.class);
    new ApplicationContextRunner()
        .withUserConfiguration(PlaceUpsertRunner.class)
        .withBean(PlaceIngestionService.class, () -> service)
        .withPropertyValues("todayit.place.upsert.run-enabled=true")
        .run(context -> assertThat(context).hasFailed());
    verifyNoInteractions(service);
  }

  /** 수집과 적재 Runner의 동시 활성화를 컨텍스트 초기화 단계에서 거부하는지 검증합니다. */
  @Test
  void rejectsSimultaneouslyEnabledRawRunnerDuringContextInitialization() {
    var service = mock(PlaceIngestionService.class);
    new ApplicationContextRunner()
        .withUserConfiguration(PlaceUpsertRunner.class)
        .withBean(PlaceIngestionService.class, () -> service)
        .withPropertyValues(
            "todayit.place.upsert.enabled=true",
            "todayit.place.upsert.run-enabled=true",
            "todayit.place.collection.enabled=true")
        .run(context -> assertThat(context).hasFailed());
    verifyNoInteractions(service);
  }

  /** Runner 호출 시에만 적재하고 일부 저장 실패는 실행 실패로 보고하는지 검증합니다. */
  @Test
  void writesOnlyWhenRunnerIsInvokedAndFailsAfterPartialStorageFailure() {
    var service = mock(PlaceIngestionService.class);
    when(service.ingestTourApi(10)).thenReturn(new PlaceIngestionResult(2, 1, 0, 0, 0, 1));
    var runner =
        new PlaceUpsertRunner(
            service,
            new PlaceUpsertProperties(true, true, 10, PlaceUpsertProperties.Mode.SAMPLE),
            false);

    assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("stored=1, failed=1");
    verify(service).ingestTourApi(10);
  }

  /** 적재 대상이 없거나 보류 대상뿐이면 정상 종료하는지 검증합니다. */
  @Test
  void noCandidatesOrOnlyDeferredRowsCompletesNormally() {
    var service = mock(PlaceIngestionService.class);
    when(service.ingestTourApi(10))
        .thenReturn(new PlaceIngestionResult(0, 0, 0, 0, 0, 0))
        .thenReturn(new PlaceIngestionResult(3, 0, 1, 1, 1, 0));
    var runner =
        new PlaceUpsertRunner(
            service,
            new PlaceUpsertProperties(true, true, 10, PlaceUpsertProperties.Mode.SAMPLE),
            false);

    runner.run(new DefaultApplicationArguments());
    runner.run(new DefaultApplicationArguments());
  }

  /** FULL로 전체 순회를 선택하고 일부 실패가 있으면 실행을 실패시키는지 확인합니다. */
  @Test
  void fullModeBindsAndReportsPartialFailureAfterCompleteTraversal() {
    var service = mock(PlaceIngestionService.class);
    when(service.ingestAllTourApi(2)).thenReturn(new PlaceIngestionResult(20, 18, 0, 1, 0, 1));
    new ApplicationContextRunner()
        .withUserConfiguration(PlaceUpsertRunner.class)
        .withBean(PlaceIngestionService.class, () -> service)
        .withPropertyValues(
            "todayit.place.upsert.enabled=true",
            "todayit.place.upsert.run-enabled=true",
            "todayit.place.upsert.mode=FULL",
            "todayit.place.upsert.limit=2")
        .run(
            context -> {
              assertThat(context).hasSingleBean(PlaceUpsertRunner.class);
              assertThatThrownBy(
                      () ->
                          context
                              .getBean(PlaceUpsertRunner.class)
                              .run(new DefaultApplicationArguments()))
                  .isInstanceOf(IllegalStateException.class)
                  .hasMessageContaining("stored=18, failed=1");
            });
    verify(service).ingestAllTourApi(2);
    org.mockito.Mockito.verifyNoMoreInteractions(service);
  }

  /** 실패 재처리 모드가 전체 적재 대신 실패 목록을 사용하며 대상이 없으면 정상 종료하는지 확인합니다. */
  @Test
  void retryModeUsesPendingFailuresOnlyAndEmptySetIsNormal() {
    var service = mock(PlaceIngestionService.class);
    when(service.retryFailedTourApi(10)).thenReturn(new PlaceIngestionResult(0, 0, 0, 0, 0, 0));
    var runner =
        new PlaceUpsertRunner(
            service,
            new PlaceUpsertProperties(true, true, 10, PlaceUpsertProperties.Mode.RETRY_FAILED),
            false);

    runner.run(new DefaultApplicationArguments());

    verify(service).retryFailedTourApi(10);
    org.mockito.Mockito.verifyNoMoreInteractions(service);
  }
}
