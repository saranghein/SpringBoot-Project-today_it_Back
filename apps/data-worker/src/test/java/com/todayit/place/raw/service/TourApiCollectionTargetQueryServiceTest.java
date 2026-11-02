package com.todayit.place.raw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.todayit.place.raw.config.NationwideCollectionProperties;
import com.todayit.place.raw.config.RawCollectionProperties;
import com.todayit.place.raw.config.TourApiRawCollectionRunner;
import com.todayit.place.raw.service.command.TourApiCollectionScope;
import com.todayit.place.raw.service.model.RawCollectionResult;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** 지역 DB의 중복 제거·정렬·변경 반영과 전국 수집의 명시적 활성화를 검증합니다. */
class TourApiCollectionTargetQueryServiceTest {

  private EmbeddedDatabase database;
  private JdbcTemplate jdbc;

  /** 외부 접속 없이 중복 시도 행을 가진 테스트 지역 목록을 준비합니다. */
  @BeforeEach
  void setUp() {
    database =
        new EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build();
    jdbc = new JdbcTemplate(database);
    jdbc.execute("CREATE TABLE region (sido_code VARCHAR(5))");
    jdbc.update("INSERT INTO region VALUES ('26'), ('11'), ('11')");
  }

  /** 테스트 DB를 종료합니다. */
  @AfterEach
  void tearDown() {
    database.shutdown();
  }

  /** 시도 중복을 제거하고 코드 순서·관광타입 설정 순서를 유지하며 기준 데이터를 보존하는지 확인합니다. */
  @Test
  void distinctSidosGenerateOrderedCartesianProductWithoutChangingRegions() {
    List<TourApiCollectionScope> result = service().findScopes(manual(List.of()));
    assertThat(result)
        .containsExactly(
            new TourApiCollectionScope("11", "", "39"),
            new TourApiCollectionScope("11", "", "12"),
            new TourApiCollectionScope("26", "", "39"),
            new TourApiCollectionScope("26", "", "12"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM region", Integer.class)).isEqualTo(3);
    assertThatThrownBy(() -> result.clear()).isInstanceOf(UnsupportedOperationException.class);
  }

  /** 실행마다 지역을 다시 조회하고 이미 확정한 목록은 변경하지 않는지 확인합니다. */
  @Test
  void nextRunSeesNewSidosButCurrentPlanIsStable() {
    TourApiCollectionTargetQueryService service = service();
    List<TourApiCollectionScope> first = service.findScopes(manual(List.of()));
    jdbc.update("INSERT INTO region VALUES ('27')");
    assertThat(first).hasSize(4);
    assertThat(service.findScopes(manual(List.of()))).hasSize(6);
  }

  /** 지역 목록이 비면 서울 등으로 대체하지 않는지 확인합니다. */
  @Test
  void emptyRegionsFailBeforeExternalCalls() {
    jdbc.update("DELETE FROM region");
    assertThatThrownBy(() -> service().findScopes(manual(List.of())))
        .isInstanceOf(IllegalStateException.class);
  }

  /** 잘못된 지역 코드를 조용히 생략하지 않고 전체 대상 생성을 거부하는지 확인합니다. */
  @Test
  void invalidSidoFailsEntirePlan() {
    jdbc.update("INSERT INTO region VALUES ('000')");
    assertThatThrownBy(() -> service().findScopes(manual(List.of())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 수동 targets와 전국 자동 생성의 충돌을 DB 조회 전에 거부하는지 확인합니다. */
  @Test
  void explicitTargetsConflictBeforeDatabaseAccess() {
    JdbcTemplate unused = mock(JdbcTemplate.class);
    TourApiCollectionTargetQueryService service =
        new TourApiCollectionTargetQueryService(
            unused, new NationwideCollectionProperties(true, List.of("12")));
    assertThatThrownBy(
            () ->
                service.findScopes(
                    manual(List.of(new RawCollectionProperties.Target("11", "", "12")))))
        .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(unused);
  }

  /** 비활성이면 DB 조회 없이 기존 수동 범위를 유지하는지 확인합니다. */
  @Test
  void disabledNationwidePreservesManualTargetsWithoutQuery() {
    JdbcTemplate unused = mock(JdbcTemplate.class);
    TourApiCollectionTargetQueryService service =
        new TourApiCollectionTargetQueryService(
            unused, new NationwideCollectionProperties(false, List.of()));
    assertThat(service.findScopes(manual(List.of())))
        .containsExactly(new TourApiCollectionScope("11", "110", "12"));
    assertThat(
            service.findScopes(manual(List.of(new RawCollectionProperties.Target("26", "", "39")))))
        .containsExactly(new TourApiCollectionScope("26", "", "39"));
    verifyNoInteractions(unused);
  }

  /** 관광타입이 없거나 중복·형식 오류가 있으면 활성화를 거부하는지 확인합니다. */
  @Test
  void enabledNationwideRequiresExplicitValidUniqueTypes() {
    for (List<String> types :
        List.of(List.<String>of(), List.of("12", "12"), List.of("x"), List.of("00"))) {
      assertThatThrownBy(() -> new NationwideCollectionProperties(true, types))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  /** 설정 이름의 바인딩과 기본 비활성을 확인하고 Bean 생성만으로 DB를 조회하지 않는지 검사합니다. */
  @Test
  void bindsOptInAndTypesWithoutQueryingAtStartup() {
    JdbcTemplate unused = mock(JdbcTemplate.class);
    ApplicationContextRunner context =
        new ApplicationContextRunner()
            .withUserConfiguration(TourApiCollectionTargetQueryService.class)
            .withBean(JdbcTemplate.class, () -> unused)
            .withPropertyValues("todayit.place.collection.enabled=true");
    context.run(
        app -> assertThat(app.getBean(NationwideCollectionProperties.class).enabled()).isFalse());
    context
        .withPropertyValues(
            "todayit.place.collection.nationwide.enabled=true",
            "todayit.place.collection.nationwide.content-type-ids=12,39")
        .run(
            app -> {
              assertThat(app).hasNotFailed();
              assertThat(app.getBean(NationwideCollectionProperties.class).contentTypeIds())
                  .containsExactly("12", "39");
            });
    verify(unused, never()).queryForList(anyString(), eq(String.class));
  }

  /** DB에서 생성한 범위가 실제 수동 FULL Runner의 모든 수집 대상에 전달되는지 확인합니다. */
  @Test
  void generatedTargetsAreUsedByFullRunner() {
    TourApiRawCollectionService collection = mock(TourApiRawCollectionService.class);
    TourApiCollectionTargetQueryService targetQuery = service();
    RawCollectionProperties properties = manual(List.of());
    List<TourApiCollectionScope> scopes = targetQuery.findScopes(properties);
    for (TourApiCollectionScope scope : scopes) {
      when(collection.collectAll(10, scope)).thenReturn(new RawCollectionResult(1, 1, 0));
    }
    TourApiRawCollectionRunner runner =
        new TourApiRawCollectionRunner(
            collection,
            properties,
            mock(PlaceNormalizationQueryService.class),
            new TourApiCollectionPlanService(collection, targetQuery));
    runner.run(new DefaultApplicationArguments());
    InOrder order = org.mockito.Mockito.inOrder(collection);
    for (TourApiCollectionScope scope : scopes) {
      order.verify(collection).collectAll(10, scope);
    }
    org.mockito.Mockito.verifyNoMoreInteractions(collection);
  }

  /**
   * 관광타입 순서가 지정된 전국 수집 조회를 만듭니다.
   *
   * @return 테스트 지역 DB 조회 서비스
   */
  private TourApiCollectionTargetQueryService service() {
    return new TourApiCollectionTargetQueryService(
        jdbc, new NationwideCollectionProperties(true, List.of("39", "12")));
  }

  /**
   * 전국 설정과 구분할 기존 수동 범위를 만듭니다.
   *
   * @param targets 수동 범위 목록
   * @return 단일 범위와 수동 목록을 가진 설정
   */
  private RawCollectionProperties manual(List<RawCollectionProperties.Target> targets) {
    return new RawCollectionProperties(
        true, 10, RawCollectionProperties.Mode.FULL, "11", "110", "12", targets);
  }
}
