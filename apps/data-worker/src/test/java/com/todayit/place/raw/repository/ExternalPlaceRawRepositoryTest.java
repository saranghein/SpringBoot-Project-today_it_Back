package com.todayit.place.raw.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.todayit.dataworker.DataWorkerApplication;
import com.todayit.place.client.external.PlaceDataSource;
import com.todayit.place.client.external.PlaceDataSource.Component;
import com.todayit.place.client.external.PlaceDataSource.SourceComponent;
import com.todayit.place.client.external.PlaceDataSource.SourceComponentRequest;
import com.todayit.place.client.external.PlaceDataSource.SourceItem;
import com.todayit.place.client.external.PlaceDataSource.SourceKey;
import com.todayit.place.client.external.PlaceDataSource.SourcePage;
import com.todayit.place.client.external.PlaceDataSource.SourcePageRequest;
import com.todayit.place.client.external.PlaceDataSourceRegistry;
import com.todayit.place.client.external.PlaceSourceException;
import com.todayit.place.client.external.PlaceSourceException.Reason;
import com.todayit.place.raw.service.ExternalPlaceRawCommandService;
import com.todayit.place.raw.service.ExternalPlaceRawQueryService;
import com.todayit.place.raw.service.PlaceNormalizationQueryService;
import com.todayit.place.raw.service.TourApiCollectionPlanService;
import com.todayit.place.raw.service.TourApiRawCollectionService;
import com.todayit.place.raw.service.command.TourApiCollectionScope;
import com.todayit.place.raw.service.model.PlaceNormalizationResult.Status;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 실제 Migration과 PostgreSQL JSONB·ON CONFLICT를 검증합니다. H2로 대체하지 않습니다. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = DataWorkerApplication.class,
    properties = {
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:raw-migration",
      "spring.jpa.hibernate.ddl-auto=validate",
      "todayit.place.collection.enabled=false",
      "TOUR_API_ENABLED=false",
      "REDTABLE_ENABLED=false",
      "CULTURE_ENABLED=false"
    })
class ExternalPlaceRawRepositoryTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final Instant FIRST = Instant.parse("2026-10-25T00:00:00Z");
  private static final String PAYLOAD =
      "{\"list\":{\"contentid\":\"001\"},\"common\":[],\"intro\":[],\"images\":[]}";

  @Autowired private ExternalPlaceRawCommandService service;
  @Autowired private ExternalPlaceRawRepository repository;
  @Autowired private ExternalPlaceRawQueryService queryService;
  @Autowired private TourApiRawCollectionService collectionService;
  @Autowired private PlaceNormalizationQueryService normalizationService;
  @Autowired private JdbcTemplate jdbc;
  @MockitoBean private PlaceDataSourceRegistry registry;

  /**
   * 격리된 PostgreSQL 컨테이너의 접속 정보를 테스트 컨텍스트에 등록합니다.
   *
   * @param registry 테스트 DB 접속 속성을 등록할 Registry
   */
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  /** 각 테스트가 독립적으로 실행되도록 원본 행을 정리합니다. */
  @BeforeEach
  void clearRows() {
    repository.deleteAllInBatch();
  }

  /** 재수집이 원본 식별자를 유지하며 같은 행만 갱신하는지 검증합니다. */
  @Test
  void repeatedCollectionUpdatesSingleRowAndPreservesItsIdentity() {
    service.saveSuccess("TOUR_API", "001", PAYLOAD, FIRST);
    Long id = repository.findByProviderAndSourceId("TOUR_API", "001").orElseThrow().getId();

    service.saveSuccess("TOUR_API", "001", PAYLOAD, FIRST.plusSeconds(60));

    var row = repository.findByProviderAndSourceId("TOUR_API", "001").orElseThrow();
    assertThat(repository.count()).isEqualTo(1);
    assertThat(row.getId()).isEqualTo(id);
    assertThat(row.getCollectedAt()).isEqualTo(FIRST.plusSeconds(60));
    assertThat(row.getPayload()).contains("001");
    assertThat(row.getUpdatedAt()).isNotNull();
  }

  /** PK 간격과 상태 차이를 건너뛰며 고정 상한 이후에 추가된 원본을 다음 실행으로 남기는지 확인합니다. */
  @Test
  void successfulKeysetTraversalHonorsProviderStatusAndFixedUpperBound() {
    service.saveSuccess("TOUR_API", "001", PAYLOAD, FIRST);
    service.saveFailure("TOUR_API", "002", null, Reason.TRANSIENT);
    service.saveSuccess("CULTURE", "003", "{}", FIRST);
    service.saveSuccess("TOUR_API", "004", PAYLOAD, FIRST);
    long upperBound = queryService.findSucceededUpperBound("TOUR_API");
    var first = queryService.findSucceededAfter("TOUR_API", 0, upperBound, 1);
    service.saveSuccess("TOUR_API", "005", PAYLOAD, FIRST);
    // 앞선 행의 상태가 변해도 offset처럼 다음 성공 행을 건너뛰지 않아야 합니다.
    service.saveFailure("TOUR_API", "001", null, Reason.TRANSIENT);

    var next = queryService.findSucceededAfter("TOUR_API", first.getFirst().rawId(), upperBound, 1);

    assertThat(first)
        .extracting(com.todayit.place.raw.service.model.RawPlaceSnapshot::sourceId)
        .containsExactly("001");
    assertThat(next)
        .extracting(com.todayit.place.raw.service.model.RawPlaceSnapshot::sourceId)
        .containsExactly("004");
    assertThat(queryService.findSucceededAfter("TOUR_API", next.getFirst().rawId(), upperBound, 1))
        .isEmpty();
    assertThat(queryService.findSucceededUpperBound("TOUR_API")).isGreaterThan(upperBound);
  }

  /** 실패 시 성공 원본을 보존하고 복구 성공 시 오류를 지우는지 검증합니다. */
  @Test
  void failurePreservesSuccessfulSnapshotAndRecoveryClearsError() {
    service.saveSuccess("TOUR_API", "001", PAYLOAD, FIRST);
    String original =
        repository.findByProviderAndSourceId("TOUR_API", "001").orElseThrow().getPayload();

    service.saveFailure("TOUR_API", "001", "{\"list\":{}}", Reason.TRANSIENT);

    var failed = repository.findByProviderAndSourceId("TOUR_API", "001").orElseThrow();
    assertThat(failed.getStatus()).isEqualTo("FAILED");
    assertThat(failed.getErrorCode()).isEqualTo("TRANSIENT");
    assertThat(failed.getPayload()).isEqualTo(original);
    assertThat(failed.getCollectedAt()).isEqualTo(FIRST);
    service.saveSuccess("TOUR_API", "001", PAYLOAD, FIRST.plusSeconds(60));
    var recovered = repository.findByProviderAndSourceId("TOUR_API", "001").orElseThrow();
    assertThat(recovered.getStatus()).isEqualTo("SUCCESS");
    assertThat(recovered.getErrorCode()).isNull();
    assertThat(repository.count()).isEqualTo(1);
  }

  /** 첫 수집 실패가 성공 시각 없이 NULL 원본을 허용하는지 검증합니다. */
  @Test
  void initialFailureHasNoSuccessfulTimestampAndAllowsNullPayload() {
    service.saveFailure(
        "TOUR_API", "001", "{\"list\":{\"contenttypeid\":\"12\"}}", Reason.TRANSIENT);
    service.saveFailure("CULTURE", "001", null, Reason.INVALID_RESPONSE);

    var failed = repository.findByProviderAndSourceId("TOUR_API", "001").orElseThrow();
    assertThat(failed.getCollectedAt()).isNull();
    assertThat(failed.getPayload()).contains("contenttypeid");
    assertThat(repository.findByProviderAndSourceId("CULTURE", "001").orElseThrow().getPayload())
        .isNull();
    assertThat(repository.count()).isEqualTo(2);
  }

  /** 동일 원천의 동시 upsert가 중복 원본 행을 만들지 않는지 검증합니다. */
  @Test
  void concurrentUpsertsCannotCreateDuplicatePlaces() {
    var writes =
        IntStream.range(0, 8)
            .mapToObj(
                index ->
                    CompletableFuture.runAsync(
                        () -> service.saveSuccess("TOUR_API", "001", PAYLOAD, FIRST)))
            .toArray(CompletableFuture[]::new);

    CompletableFuture.allOf(writes).join();

    assertThat(repository.count()).isEqualTo(1);
  }

  /** 겹치는 범위와 다른 지역을 순차 수집해도 원본 ID를 유지하며 재실행하는지 PostgreSQL에서 확인합니다. */
  @Test
  void multiScopeCollectionPreservesIdentityAcrossOverlappingTargetsAndReruns() {
    var source = mock(PlaceDataSource.class);
    when(registry.get(new SourceKey("TOUR_API", "kor-service2"))).thenReturn(source);
    when(source.fetchPage(any()))
        .thenAnswer(
            call -> {
              SourcePageRequest request = call.getArgument(0);
              String id = request.filters().get("lDongRegnCd").equals("26") ? "busan" : "seoul";
              String payload =
                  "{\"contentid\":\""
                      + id
                      + "\",\"title\":\"장소\",\"contenttypeid\":\""
                      + request.filters().get("contentTypeId")
                      + "\"}";
              return new SourcePage(List.of(new SourceItem(id, payload)), "", 1, FIRST);
            });
    when(source.fetchComponent(any()))
        .thenAnswer(
            call -> {
              SourceComponentRequest request = call.getArgument(0);
              String payload =
                  request.component() == Component.IMAGES
                      ? "[]"
                      : "[{\"contentid\":\"" + request.sourceId() + "\",\"title\":\"장소\"}]";
              return new SourceComponent(request.sourceId(), request.component(), payload, FIRST);
            });
    var plan = new TourApiCollectionPlanService(collectionService);
    var targets =
        List.of(
            new TourApiCollectionScope("11", "", "12"),
            new TourApiCollectionScope("11", "110", "12"),
            new TourApiCollectionScope("26", "110", "39"));

    assertThat(plan.collect(targets, 1, true).succeeded()).isEqualTo(3);
    var ids =
        jdbc.queryForList(
            "SELECT source_id, external_place_raw_id FROM external_place_raw ORDER BY source_id");
    assertThat(plan.collect(targets, 1, true).succeeded()).isEqualTo(3);

    assertThat(repository.count()).isEqualTo(2);
    assertThat(
            jdbc.queryForList(
                "SELECT source_id, external_place_raw_id FROM external_place_raw ORDER BY source_id"))
        .isEqualTo(ids);
    assertThat(repository.findByProviderAndSourceId("TOUR_API", "busan").orElseThrow().getStatus())
        .isEqualTo("SUCCESS");
  }

  /** TourAPI 실패 원본만 PK 순서와 건수 제한에 맞춰 조회하는지 검증합니다. */
  @Test
  void selectsOnlyTourApiFailedRowsInPrimaryKeyOrderWithLimit() {
    service.saveFailure("TOUR_API", "002", listPayload("002"), Reason.TRANSIENT);
    service.saveFailure("TOUR_API", "001", listPayload("001"), Reason.TRANSIENT);
    service.saveSuccess("TOUR_API", "success", PAYLOAD, FIRST);
    service.saveFailure("CULTURE", "other", null, Reason.TRANSIENT);
    jdbc.update(
        "INSERT INTO external_place_raw (provider, source_id, status) VALUES (?, ?, ?)",
        "TOUR_API",
        "pending",
        "PENDING");

    var first = queryService.findFailed("TOUR_API", 1);
    var all = queryService.findFailed("TOUR_API", 10);

    assertThat(first).extracting(target -> target.sourceId()).containsExactly("002");
    assertThat(all).extracting(target -> target.sourceId()).containsExactly("002", "001");
  }

  /** 재수집이 원본 식별자와 다른 실패 행을 보존하며 대상만 복구하는지 검증합니다. */
  @Test
  void retryRecoversExistingFailedRowWithoutCreatingNewIdentityOrTouchingOtherFailures() {
    service.saveSuccess("TOUR_API", "001", listPayload("001"), FIRST);
    service.saveFailure("TOUR_API", "001", null, Reason.TRANSIENT);
    service.saveFailure("TOUR_API", "002", listPayload("002"), Reason.TRANSIENT);
    var originalId = repository.findByProviderAndSourceId("TOUR_API", "001").orElseThrow().getId();
    var source = mock(PlaceDataSource.class);
    when(registry.get(new SourceKey("TOUR_API", "kor-service2"))).thenReturn(source);
    when(source.fetchComponent(any()))
        .thenAnswer(
            invocation -> {
              SourceComponentRequest request = invocation.getArgument(0);
              String payload =
                  request.component() == Component.IMAGES
                      ? "[]"
                      : "[{\"contentid\":\"001\",\"title\":\"장소\"}]";
              return new SourceComponent(
                  request.sourceId(), request.component(), payload, FIRST.plusSeconds(1));
            });

    var result = collectionService.retryFailed(1);

    var recovered = repository.findByProviderAndSourceId("TOUR_API", "001").orElseThrow();
    assertThat(result.succeeded()).isEqualTo(1);
    assertThat(recovered.getId()).isEqualTo(originalId);
    assertThat(recovered.getStatus()).isEqualTo("SUCCESS");
    assertThat(recovered.getErrorCode()).isNull();
    assertThat(recovered.getCollectedAt()).isAfter(FIRST);
    assertThat(recovered.getPayload()).contains("common", "intro", "images");
    assertThat(repository.findByProviderAndSourceId("TOUR_API", "002").orElseThrow().getStatus())
        .isEqualTo("FAILED");
    assertThat(repository.count()).isEqualTo(2);
    verify(source, never()).fetchPage(any());
  }

  /** 반복 재수집 실패에도 이전 성공 원본과 성공 시각이 유지되는지 검증합니다. */
  @Test
  void repeatedRetryFailurePreservesPreviousSuccessfulPayloadAndTimestamp() {
    service.saveSuccess("TOUR_API", "001", listPayload("001"), FIRST);
    service.saveFailure("TOUR_API", "001", null, Reason.TRANSIENT);
    var before = repository.findByProviderAndSourceId("TOUR_API", "001").orElseThrow();
    String originalPayload = before.getPayload();
    var source = mock(PlaceDataSource.class);
    when(registry.get(new SourceKey("TOUR_API", "kor-service2"))).thenReturn(source);
    when(source.fetchComponent(any())).thenThrow(new PlaceSourceException(Reason.TRANSIENT));

    var result = collectionService.retryFailed(10);

    var after = repository.findByProviderAndSourceId("TOUR_API", "001").orElseThrow();
    assertThat(result.failed()).isEqualTo(1);
    assertThat(after.getId()).isEqualTo(before.getId());
    assertThat(after.getStatus()).isEqualTo("FAILED");
    assertThat(after.getPayload()).isEqualTo(originalPayload);
    assertThat(after.getCollectedAt()).isEqualTo(FIRST);
    assertThat(repository.count()).isEqualTo(1);
  }

  /**
   * 재수집에 필요한 원천 ID와 관광타입을 가진 목록 JSON을 생성합니다.
   *
   * @param sourceId 공급원 장소 ID
   * @return 원천 ID와 관광타입을 포함하는 재수집용 목록 JSON
   */
  private String listPayload(String sourceId) {
    return "{\"list\":{\"contentid\":\""
        + sourceId
        + "\",\"contenttypeid\":\"12\",\"title\":\"장소\"}}";
  }

  /** 정제 미리보기가 성공 원본의 내용·상태·시각을 변경하지 않는지 검증합니다. */
  @Test
  void normalizationPreviewDoesNotChangeSuccessfulRawPayloadStatusOrTimestamps() {
    String payload =
        """
        {"list":{"contentid":"001","lclsSystm3":"VE030100","title":"공원","addr1":"서울 종로구","mapy":"37.5","mapx":"127.1","lDongRegnCd":"11","lDongSignguCd":"110"},
         "common":[{"contentid":"001"}],"intro":[{"contentid":"001"}],"images":[]}
        """;
    service.saveSuccess("TOUR_API", "001", payload, FIRST);
    service.saveFailure("TOUR_API", "002", listPayload("002"), Reason.TRANSIENT);
    service.saveSuccess("CULTURE", "003", "{}", FIRST);
    var before = repository.findByProviderAndSourceId("TOUR_API", "001").orElseThrow();

    var results = normalizationService.previewTourApi(10);

    assertThat(results).hasSize(1);
    assertThat(results.getFirst().status()).isEqualTo(Status.NORMALIZED);
    var after = repository.findByProviderAndSourceId("TOUR_API", "001").orElseThrow();
    assertThat(after.getId()).isEqualTo(before.getId());
    assertThat(after.getPayload()).isEqualTo(before.getPayload());
    assertThat(after.getStatus()).isEqualTo("SUCCESS");
    assertThat(after.getCollectedAt()).isEqualTo(before.getCollectedAt());
    assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    assertThat(repository.count()).isEqualTo(3);
    org.mockito.Mockito.verifyNoInteractions(registry);
  }
}
