package com.todayit.place.raw.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.todayit.place.raw.config.RawCollectionProperties.Mode;
import com.todayit.place.raw.service.PlaceNormalizationQueryService;
import com.todayit.place.raw.service.TourApiCollectionPlanService;
import com.todayit.place.raw.service.TourApiRawCollectionService;
import com.todayit.place.raw.service.command.TourApiCollectionScope;
import com.todayit.place.raw.service.model.RawCollectionResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 수집·재수집·정제 실행 모드와 명시적 실행 조건을 검증합니다. */
class TourApiRawCollectionRunnerTest {

  /** 명시적으로 활성화한 경우에만 수집 Runner가 등록되는지 검증합니다. */
  @Test
  void collectionRequiresExplicitOptIn() {
    new ApplicationContextRunner()
        .withUserConfiguration(TourApiRawCollectionRunner.class, TourApiCollectionPlanService.class)
        .run(context -> assertThat(context).doesNotHaveBean(TourApiRawCollectionRunner.class));
  }

  /** 기본 수집 건수를 바인딩하고 Bean 생성 단계에서는 조회를 시작하지 않는지 검증합니다. */
  @Test
  void enabledRunnerBindsDefaultLimitWithoutStartingCollectionOnBeanCreation() {
    new ApplicationContextRunner()
        .withUserConfiguration(TourApiRawCollectionRunner.class, TourApiCollectionPlanService.class)
        .withBean(TourApiRawCollectionService.class, () -> mock(TourApiRawCollectionService.class))
        .withBean(
            PlaceNormalizationQueryService.class, () -> mock(PlaceNormalizationQueryService.class))
        .withPropertyValues("todayit.place.collection.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(TourApiRawCollectionRunner.class);
              assertThat(context.getBean(RawCollectionProperties.class).limit()).isEqualTo(10);
              assertThat(context.getBean(RawCollectionProperties.class).toScope())
                  .isEqualTo(TourApiCollectionScope.seoulAttractions());
              assertThat(context.getBean(RawCollectionProperties.class).mode())
                  .isEqualTo(Mode.SAMPLE);
              org.mockito.Mockito.verifyNoInteractions(
                  context.getBean(TourApiRawCollectionService.class));
            });
  }

  /** 일부 원본 수집 실패가 있으면 명시적 실행도 실패하는지 검증합니다. */
  @Test
  void partialFailureMakesExplicitExecutionFail() {
    var service = mock(TourApiRawCollectionService.class);
    when(service.collectSample(10, TourApiCollectionScope.seoulAttractions()))
        .thenReturn(new RawCollectionResult(2, 1, 1));
    var runner = runner(service, Mode.SAMPLE);

    assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("succeeded=1, failed=1");
  }

  /** 재수집 모드가 신규 표본 수집을 호출하지 않는지 검증합니다. */
  @Test
  void retryModeDoesNotRunSampleCollection() {
    var service = mock(TourApiRawCollectionService.class);
    when(service.retryFailed(10)).thenReturn(new RawCollectionResult(1, 1, 0));
    var runner = runner(service, Mode.RETRY_FAILED);

    runner.run(new DefaultApplicationArguments());

    org.mockito.Mockito.verify(service).retryFailed(10);
    org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
        .collectSample(
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.any(TourApiCollectionScope.class));
  }

  /** 재수집 대상이 없으면 정상 종료하는지 검증합니다. */
  @Test
  void retryModeWithNoTargetsCompletesNormally() {
    var service = mock(TourApiRawCollectionService.class);
    when(service.retryFailed(10)).thenReturn(new RawCollectionResult(0, 0, 0));
    var runner = runner(service, Mode.RETRY_FAILED);

    runner.run(new DefaultApplicationArguments());
  }

  /** 원본 재수집 실패가 실행 실패로 전파되는지 검증합니다. */
  @Test
  void retryFailureMakesExecutionFail() {
    var service = mock(TourApiRawCollectionService.class);
    when(service.retryFailed(10)).thenReturn(new RawCollectionResult(1, 0, 1));
    var runner = runner(service, Mode.RETRY_FAILED);

    assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("failed=1");
  }

  /** 명시한 재수집 모드가 설정에 바인딩되는지 검증합니다. */
  @Test
  void retryModeBindsFromExplicitConfiguration() {
    new ApplicationContextRunner()
        .withUserConfiguration(TourApiRawCollectionRunner.class, TourApiCollectionPlanService.class)
        .withBean(TourApiRawCollectionService.class, () -> mock(TourApiRawCollectionService.class))
        .withBean(
            PlaceNormalizationQueryService.class, () -> mock(PlaceNormalizationQueryService.class))
        .withPropertyValues(
            "todayit.place.collection.enabled=true", "todayit.place.collection.mode=RETRY_FAILED")
        .run(
            context ->
                assertThat(context.getBean(RawCollectionProperties.class).mode())
                    .isEqualTo(Mode.RETRY_FAILED));
  }

  /** 정제 모드에서 저장 없이 미리보기만 호출하는지 검증합니다. */
  @Test
  void normalizationModeOnlyCallsReadOnlyPreview() {
    var collection = mock(TourApiRawCollectionService.class);
    var normalization = mock(PlaceNormalizationQueryService.class);
    var runner =
        new TourApiRawCollectionRunner(
            collection,
            new RawCollectionProperties(true, 10, Mode.NORMALIZE, "11", "", "12", List.of()),
            normalization,
            new TourApiCollectionPlanService(collection));

    runner.run(new DefaultApplicationArguments());

    org.mockito.Mockito.verify(normalization).previewTourApi(10);
    org.mockito.Mockito.verifyNoInteractions(collection);
  }

  /**
   * 지정한 실행 모드로 수집 Runner를 생성합니다.
   *
   * @param service 테스트에서 사용할 수집 서비스
   * @param mode 검증할 실행 모드
   * @return 지정한 모드로 최대 10건을 처리하는 수집 Runner
   */
  private TourApiRawCollectionRunner runner(TourApiRawCollectionService service, Mode mode) {
    return new TourApiRawCollectionRunner(
        service,
        new RawCollectionProperties(true, 10, mode, "11", "", "12", List.of()),
        mock(PlaceNormalizationQueryService.class),
        new TourApiCollectionPlanService(service));
  }

  /** 전체 순회 후 개별 상세 실패가 있으면 최종 실행을 실패시키는지 확인합니다. */
  @Test
  void fullModeRunsAllPagesAndFailsOnIndividualCollectionFailure() {
    var service = mock(TourApiRawCollectionService.class);
    when(service.collectAll(10, TourApiCollectionScope.seoulAttractions()))
        .thenReturn(new RawCollectionResult(25, 24, 1));

    assertThatThrownBy(() -> runner(service, Mode.FULL).run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalStateException.class);

    verify(service).collectAll(10, TourApiCollectionScope.seoulAttractions());
  }

  /** 지역·타입 설정을 바인딩하고 FULL 수집에 전달하는지 확인합니다. */
  @Test
  void bindsDistrictAndTypeAndPassesSelectedScopeToFullCollection() {
    var service = mock(TourApiRawCollectionService.class);
    var scope = new TourApiCollectionScope("26", "110", "39");
    when(service.collectAll(10, scope)).thenReturn(new RawCollectionResult(1, 1, 0));
    new ApplicationContextRunner()
        .withUserConfiguration(TourApiRawCollectionRunner.class, TourApiCollectionPlanService.class)
        .withBean(TourApiRawCollectionService.class, () -> service)
        .withBean(
            PlaceNormalizationQueryService.class, () -> mock(PlaceNormalizationQueryService.class))
        .withPropertyValues(
            "todayit.place.collection.enabled=true",
            "todayit.place.collection.mode=FULL",
            "todayit.place.collection.sido-code=26",
            "todayit.place.collection.sigungu-code=110",
            "todayit.place.collection.content-type-id=39")
        .run(
            context ->
                context
                    .getBean(TourApiRawCollectionRunner.class)
                    .run(new DefaultApplicationArguments()));
    verify(service).collectAll(10, scope);
  }

  /** 잘못된 수집 범위는 외부 조회 전에 거부하는지 확인합니다. */
  @Test
  void invalidScopeDoesNotStartExternalCollection() {
    var service = mock(TourApiRawCollectionService.class);
    var runner =
        new TourApiRawCollectionRunner(
            service,
            new RawCollectionProperties(true, 10, Mode.SAMPLE, "", "110", "39", List.of()),
            mock(PlaceNormalizationQueryService.class),
            new TourApiCollectionPlanService(service));
    assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
        .isInstanceOf(IllegalArgumentException.class);
    org.mockito.Mockito.verifyNoInteractions(service);
  }

  /** 목록 설정이 기존 단일 설정을 덮어쓰고 지정 순서와 생략 시군구 기본값을 유지하는지 확인합니다. */
  @Test
  void targetListOverridesSingleSettingsAndRunsInConfiguredOrder() {
    var service = mock(TourApiRawCollectionService.class);
    var first = new TourApiCollectionScope("26", "110", "39");
    var second = new TourApiCollectionScope("11", "", "14");
    when(service.collectAll(10, first)).thenReturn(new RawCollectionResult(1, 1, 0));
    when(service.collectAll(10, second)).thenReturn(new RawCollectionResult(2, 2, 0));
    new ApplicationContextRunner()
        .withUserConfiguration(TourApiRawCollectionRunner.class, TourApiCollectionPlanService.class)
        .withBean(TourApiRawCollectionService.class, () -> service)
        .withBean(
            PlaceNormalizationQueryService.class, () -> mock(PlaceNormalizationQueryService.class))
        .withPropertyValues(
            "todayit.place.collection.enabled=true",
            "todayit.place.collection.mode=FULL",
            "todayit.place.collection.sido-code=invalid",
            "todayit.place.collection.targets[0].sido-code=26",
            "todayit.place.collection.targets[0].sigungu-code=110",
            "todayit.place.collection.targets[0].content-type-id=39",
            "todayit.place.collection.targets[1].sido-code=11",
            "todayit.place.collection.targets[1].content-type-id=14")
        .run(
            context -> {
              assertThat(context.getBean(RawCollectionProperties.class).toScopes())
                  .containsExactly(first, second);
              context
                  .getBean(TourApiRawCollectionRunner.class)
                  .run(new DefaultApplicationArguments());
            });
    var order = org.mockito.Mockito.inOrder(service);
    order.verify(service).collectAll(10, first);
    order.verify(service).collectAll(10, second);
    org.mockito.Mockito.verifyNoMoreInteractions(service);
  }

  /** 뒤쪽 항목 오류나 중복을 첫 대상의 외부 조회 전에 거부하는지 확인합니다. */
  @Test
  void invalidLaterTargetAndDuplicateAreRejectedBeforeFirstExternalCall() {
    var service = mock(TourApiRawCollectionService.class);
    var valid = new RawCollectionProperties.Target("26", "110", "39");
    var invalid = new RawCollectionProperties.Target("11", "", "invalid");
    for (var targets : List.of(List.of(valid, invalid), List.of(valid, valid))) {
      var properties = new RawCollectionProperties(true, 10, Mode.FULL, "11", "", "12", targets);
      var runner =
          new TourApiRawCollectionRunner(
              service,
              properties,
              mock(PlaceNormalizationQueryService.class),
              new TourApiCollectionPlanService(service));
      assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
          .isInstanceOf(IllegalArgumentException.class);
    }
    org.mockito.Mockito.verifyNoInteractions(service);
  }
}
