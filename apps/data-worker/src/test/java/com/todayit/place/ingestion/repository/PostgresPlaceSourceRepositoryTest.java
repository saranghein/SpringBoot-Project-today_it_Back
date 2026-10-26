package com.todayit.place.ingestion.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.todayit.dataworker.DataWorkerApplication;
import com.todayit.place.client.external.PlaceSourceException.Reason;
import com.todayit.place.ingestion.service.DatabasePlaceRegionQueryService;
import com.todayit.place.ingestion.service.PlaceIngestionService;
import com.todayit.place.ingestion.service.PlaceRegionQueryService;
import com.todayit.place.ingestion.service.PlaceUpsertCommandService;
import com.todayit.place.ingestion.service.model.PlaceIngestionResult;
import com.todayit.place.ingestion.service.model.PlaceUpsertResult;
import com.todayit.place.raw.service.ExternalPlaceRawCommandService;
import com.todayit.place.raw.service.model.PlaceNormalizationResult;
import com.todayit.place.raw.service.model.PlaceNormalizationResult.Status;
import com.todayit.place.service.model.NormalizedPlace;
import com.todayit.place.service.model.NormalizedPlace.Category;
import com.todayit.place.service.model.NormalizedPlace.Image;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 원본 공급원과 사용자 DB 없이 실제 PostgreSQL Migration·트랜잭션·동시 저장을 검증합니다. */
@Testcontainers
@SpringBootTest(
    classes = DataWorkerApplication.class,
    properties = {
      "spring.flyway.enabled=true",
      "spring.flyway.locations=classpath:place-service-migration",
      "spring.jpa.hibernate.ddl-auto=validate",
      "todayit.place.upsert.enabled=true",
      "todayit.place.collection.enabled=false",
      "TOUR_API_ENABLED=false",
      "REDTABLE_ENABLED=false",
      "CULTURE_ENABLED=false"
    })
class PostgresPlaceSourceRepositoryTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @MockitoBean private PlaceRegionQueryService regions;
  @Autowired private PlaceUpsertCommandService service;
  @Autowired private PlaceSourceRepository repository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlaceIngestionService ingestionService;
  @Autowired private ExternalPlaceRawCommandService rawStore;
  @Autowired private PlatformTransactionManager transactionManager;
  private int regionId;

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

  /** 이전 테스트 데이터를 정리하고 지역 조회에 사용할 기준 행을 준비합니다. */
  @BeforeEach
  void fixtures() {
    // 이 연결은 테스트 전용 컨테이너이며 실행 환경의 사용자 DB 설정을 사용하지 않습니다.
    for (String table :
        List.of(
            "place_scrap",
            "place_member_like",
            "place_image",
            "region_place",
            "place_source",
            "external_place_raw",
            "place",
            "member",
            "region")) {
      jdbc.update("DELETE FROM " + table);
    }
    // 새 지역 스키마의 코드·이름으로 FK fixture를 준비합니다.
    regionId =
        jdbc.queryForObject(
            "INSERT INTO region (sido_code, sigungu_code, sido_name, sigungu_name) VALUES ('11', '110', '서울특별시', '종로구') RETURNING region_id",
            Integer.class);
    when(regions.findRegionId("11", "110")).thenReturn(Optional.of(regionId));
  }

  /** 신규 활성 장소와 원천·지역 연결을 생성하되 후보 이미지는 공개하지 않는지 검증합니다. */
  @Test
  void createsActivePlaceAndSourceAndRegionButDoesNotPublishCandidateImages() {
    var result = service.upsert(normalized("001", "공원", "소개"));

    assertThat(result.status()).isEqualTo(PlaceUpsertResult.Status.APPLIED);
    var row = jdbc.queryForMap("SELECT * FROM place WHERE place_id = ?", result.placeId());
    assertThat(row.get("is_active")).isEqualTo(true);
    assertThat(row.get("is_deleted")).isEqualTo(false);
    assertThat(row.get("view_count")).isEqualTo(0);
    assertThat(row.get("description")).isEqualTo("소개");
    assertThat(count("place_source")).isEqualTo(1);
    assertThat(count("region_place")).isEqualTo(1);
    assertThat(count("place_image")).isZero();
    assertThat(count("external_place_raw")).isZero();
  }

  /** 동일 원천을 재적재해도 장소·지역 연결과 최초 생성 시각이 유지되는지 검증합니다. */
  @Test
  void repeatedSourceKeepsOnePlaceAndOneRegionLinkAndSameCreationTime() {
    var first = service.upsert(normalized("001", "공원", null));
    var created =
        jdbc.queryForObject(
            "SELECT created_at FROM place WHERE place_id = ?",
            OffsetDateTime.class,
            first.placeId());

    var second = service.upsert(normalized("001", "바뀐 이름", "소개 추가"));

    assertThat(second.placeId()).isEqualTo(first.placeId());
    assertThat(count("place")).isEqualTo(1);
    assertThat(count("place_source")).isEqualTo(1);
    assertThat(count("region_place")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT created_at FROM place WHERE place_id = ?",
                OffsetDateTime.class,
                first.placeId()))
        .isEqualTo(created);
    assertThat(
            jdbc.queryForObject(
                "SELECT description FROM place WHERE place_id = ?", String.class, first.placeId()))
        .isEqualTo("소개 추가");
  }

  /** 갱신 시 운영 상태와 누락된 소개 및 기존 사용자 관계를 보존하는지 검증합니다. */
  @Test
  void preservesOperationalStateDescriptionAndUserRelationsOnUpdate() {
    int id = service.upsert(normalized("001", "기존 이름", "기존 소개")).placeId();
    jdbc.update(
        "UPDATE place SET view_count = 17, is_active = false, is_deleted = true WHERE place_id = ?",
        id);
    jdbc.update(
        "INSERT INTO place_image (place_id, image_url) VALUES (?, 'https://example.com/manual.jpg')",
        id);
    jdbc.update(
        "INSERT INTO member (member_id, provider, nickname) VALUES ('fixture-member', 'KAKAO', 'fixture-nickname')");
    jdbc.update(
        "INSERT INTO place_member_like (place_id, member_id) VALUES (?, 'fixture-member')", id);
    jdbc.update(
        "INSERT INTO place_scrap (place_id, member_id, is_deleted) VALUES (?, 'fixture-member', false)",
        id);

    service.upsert(normalized("001", "새 이름", null));

    var row = jdbc.queryForMap("SELECT * FROM place WHERE place_id = ?", id);
    assertThat(row.get("name")).isEqualTo("새 이름");
    assertThat(row.get("description")).isEqualTo("기존 소개");
    assertThat(row.get("view_count")).isEqualTo(17);
    assertThat(row.get("is_active")).isEqualTo(false);
    assertThat(row.get("is_deleted")).isEqualTo(true);
    assertThat(row.get("updated_at")).isNotNull();
    assertThat(count("place_image")).isEqualTo(1);
    assertThat(count("place_member_like")).isEqualTo(1);
    assertThat(count("place_scrap")).isEqualTo(1);
  }

  /** 지역이 없으면 장소와 원천 연결을 생성하지 않는지 검증합니다. */
  @Test
  void missingRegionNeverCreatesPlaceOrMapping() {
    when(regions.findRegionId("11", "110")).thenReturn(Optional.empty());

    assertThat(service.upsert(normalized("001", "공원", null)).status())
        .isEqualTo(PlaceUpsertResult.Status.REGION_NOT_FOUND);

    assertThat(count("place")).isZero();
    assertThat(count("place_source")).isZero();
  }

  /** 지역 연결 실패 시 신규 장소와 원천 연결이 함께 롤백되는지 검증합니다. */
  @Test
  void newPlaceAndSourceAreRolledBackIfRegionLinkFails() {
    when(regions.findRegionId("11", "110")).thenReturn(Optional.of(regionId + 100000));

    assertThatThrownBy(() -> service.upsert(normalized("001", "공원", null)))
        .isInstanceOf(RuntimeException.class);

    assertThat(count("place")).isZero();
    assertThat(count("place_source")).isZero();
    assertThat(count("region_place")).isZero();
  }

  /** 새 지역 연결 실패 시 기존 장소의 필드 변경도 롤백되는지 검증합니다. */
  @Test
  void existingValuesAreRolledBackIfNewRegionLinkFails() {
    int id = service.upsert(normalized("001", "기존 이름", "기존 소개")).placeId();
    when(regions.findRegionId("11", "110")).thenReturn(Optional.of(regionId + 100000));

    assertThatThrownBy(() -> service.upsert(normalized("001", "바뀌면 안 되는 이름", "바뀌면 안 되는 소개")))
        .isInstanceOf(RuntimeException.class);

    var row = jdbc.queryForMap("SELECT name, description FROM place WHERE place_id = ?", id);
    assertThat(row.get("name")).isEqualTo("기존 이름");
    assertThat(row.get("description")).isEqualTo("기존 소개");
    assertThat(count("place_source")).isEqualTo(1);
    assertThat(count("region_place")).isEqualTo(1);
  }

  /**
   * 동일 원천의 동시 첫 저장이 고아 장소나 중복 연결 없이 같은 장소를 반환하는지 검증합니다.
   *
   * @throws Exception 테스트 자원 준비 또는 비동기 실행에 실패할 때
   */
  @Test
  void concurrentFirstWritesReturnSamePlaceWithoutOrphansOrDuplicateLinks() throws Exception {
    var input = normalized("001", "공원", "소개");
    try (var executor = Executors.newFixedThreadPool(4)) {
      var futures =
          IntStream.range(0, 8)
              .mapToObj(index -> executor.submit(() -> service.upsert(input)))
              .toList();
      var ids = new HashSet<Integer>();
      for (var future : futures) {
        ids.add(future.get(30, TimeUnit.SECONDS).placeId());
      }
      assertThat(ids).hasSize(1);
    }
    assertThat(count("place")).isEqualTo(1);
    assertThat(count("place_source")).isEqualTo(1);
    assertThat(count("region_place")).isEqualTo(1);
  }

  /** 이름·좌표가 같아도 다른 원천 ID를 자동 병합하지 않는지 검증합니다. */
  @Test
  void neverMergesDifferentSourceIdsByNameOrCoordinates() {
    int first = service.upsert(normalized("001", "같은 이름", null)).placeId();
    int second = service.upsert(normalized("002", "같은 이름", null)).placeId();

    assertThat(second).isNotEqualTo(first);
    assertThat(count("place")).isEqualTo(2);
  }

  /** Service 트랜잭션 없이 Repository를 직접 쓰는 호출을 거부하는지 검증합니다. */
  @Test
  void repositoryCannotWriteWithoutServiceTransaction() {
    assertThatThrownBy(
            () ->
                repository.upsert(
                    "TOUR_API", "001", regionId, normalized("001", "공원", null).place()))
        .isInstanceOf(IllegalTransactionStateException.class);
    assertThat(count("place")).isZero();
  }

  /** 원본을 보존하며 실패 건 롤백 후 다음 건을 저장하고 재실행 중복을 방지하는지 검증합니다. */
  @Test
  void ingestsSuccessfulRawBatchAndContinuesAfterIndividualRollbackWithoutChangingRawRows() {
    successfulRaw("001", "VE030100", "110");
    successfulRaw("002", "VE030100", "140");
    successfulRaw("003", "EX050800", "110");
    successfulRaw("004", "EV020200", "110");
    successfulRaw("005", "VE030100", "150");
    successfulRaw("006", "VE030100", "110");
    when(regions.findRegionId("11", "140")).thenReturn(Optional.empty());
    when(regions.findRegionId("11", "150")).thenReturn(Optional.of(regionId + 100000));
    rawStore.saveFailure("TOUR_API", "failed-raw", null, Reason.TRANSIENT);
    rawStore.saveSuccess("CULTURE", "other-provider", "{}", Instant.parse("2026-10-25T00:00:00Z"));
    var before =
        jdbc.queryForList(
            "SELECT provider, source_id, payload::text, status, error_code, collected_at, updated_at FROM external_place_raw ORDER BY external_place_raw_id");

    var first = ingestionService.ingestTourApi(10);
    var second = ingestionService.ingestTourApi(10);

    assertThat(first).isEqualTo(new PlaceIngestionResult(6, 2, 1, 1, 1, 1));
    assertThat(second).isEqualTo(first);
    assertThat(count("place")).isEqualTo(2);
    assertThat(count("place_source")).isEqualTo(2);
    assertThat(count("region_place")).isEqualTo(2);
    assertThat(
            jdbc.queryForList(
                "SELECT source_id FROM place_source ORDER BY source_id", String.class))
        .containsExactly("001", "006");
    assertThat(
            jdbc.queryForList(
                "SELECT provider, source_id, payload::text, status, error_code, collected_at, updated_at FROM external_place_raw ORDER BY external_place_raw_id"))
        .isEqualTo(before);
  }

  /** 전체 적재를 하나의 외부 트랜잭션으로 감싸는 호출을 거부하는지 검증합니다. */
  @Test
  void ingestionCannotAccidentallyWrapAllPlacesInOneTransaction() {
    var transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () -> transaction.executeWithoutResult(status -> ingestionService.ingestTourApi(10)))
        .isInstanceOf(IllegalTransactionStateException.class);

    assertThat(count("place")).isZero();
  }

  /** PostgreSQL의 실제 지역 조회와 소개 정제를 거쳐 저장하고 미등록 지역은 보류하는지 확인합니다. */
  @Test
  void ingestsWithDatabaseRegionLookupAndPreservesNormalizedDescription() {
    var lookup = new DatabasePlaceRegionQueryService(jdbc);
    when(regions.findRegionId("11", "110")).thenAnswer(call -> lookup.findRegionId("11", "110"));
    when(regions.findRegionId("11", "140")).thenAnswer(call -> lookup.findRegionId("11", "140"));
    successfulRaw("001", "VE030100", "110");
    successfulRaw("002", "VE030100", "140");
    jdbc.update(
        "UPDATE external_place_raw SET payload = jsonb_set(payload, '{common,0,overview}', to_jsonb(CAST(? AS text))) WHERE source_id = ?",
        "<p>장소 소개 &amp; 안내</p>",
        "001");

    var result = ingestionService.ingestTourApi(10);

    assertThat(result).isEqualTo(new PlaceIngestionResult(2, 1, 0, 0, 1, 0));
    assertThat(jdbc.queryForObject("SELECT description FROM place", String.class))
        .isEqualTo("장소 소개 & 안내");
    assertThat(count("region_place")).isEqualTo(1);
  }

  /** 유일 제약을 미룬 지역에 중복이 있으면 장소를 저장하지 않고 다음 정상 지역을 처리하는지 확인합니다. */
  @Test
  void duplicateDatabaseRegionFailsOnePlaceAndContinuesWithNextRegion() {
    jdbc.update(
        "INSERT INTO region (sido_code, sigungu_code, sido_name, sigungu_name) VALUES ('11', '110', '서울특별시', '종로구'), ('11', '140', '서울특별시', '중구')");
    var lookup = new DatabasePlaceRegionQueryService(jdbc);
    when(regions.findRegionId("11", "110")).thenAnswer(call -> lookup.findRegionId("11", "110"));
    when(regions.findRegionId("11", "140")).thenAnswer(call -> lookup.findRegionId("11", "140"));
    successfulRaw("001", "VE030100", "110");
    successfulRaw("002", "VE030100", "140");

    var result = ingestionService.ingestTourApi(10);

    assertThat(result).isEqualTo(new PlaceIngestionResult(2, 1, 0, 0, 0, 1));
    assertThat(jdbc.queryForList("SELECT source_id FROM place_source", String.class))
        .containsExactly("002");
    assertThat(count("place")).isEqualTo(1);
  }

  /**
   * 분류와 지역 코드가 지정된 성공 원본을 테스트 DB에 준비합니다.
   *
   * @param sourceId 공급원 장소 ID
   * @param categoryCode 원천 소분류 코드
   * @param sigunguCode 시군구 코드
   */
  private void successfulRaw(String sourceId, String categoryCode, String sigunguCode) {
    String payload =
        """
        {"list":{"contentid":"%s","lclsSystm3":"%s","title":"공원","addr1":"서울","mapy":"37.5","mapx":"127.1","lDongRegnCd":"11","lDongSignguCd":"%s"},
        "common":[{"contentid":"%s"}],"intro":[{"contentid":"%s"}],"images":[]}
        """
            .formatted(sourceId, categoryCode, sigunguCode, sourceId, sourceId);
    rawStore.saveSuccess("TOUR_API", sourceId, payload, Instant.parse("2026-10-25T00:00:00Z"));
  }

  /**
   * 저장 검증에 사용할 정상 정제 결과를 생성합니다.
   *
   * @param sourceId 공급원 장소 ID
   * @param name 정제할 장소명
   * @param description 정제된 일반 텍스트 소개
   * @return 테스트 준비 또는 검증에 사용할 값
   */
  private PlaceNormalizationResult normalized(String sourceId, String name, String description) {
    var place =
        new NormalizedPlace(
            name,
            "서울 종로구",
            new BigDecimal("37.5"),
            new BigDecimal("127.1"),
            Category.NATURE_WALK,
            "11",
            "110",
            "VE030100",
            description,
            List.of(new Image("https://example.com/candidate.jpg", null, null, null, null)));
    return new PlaceNormalizationResult(1, sourceId, Status.NORMALIZED, null, place);
  }

  /**
   * 검증 대상 테이블 또는 조회문의 행 수를 반환합니다.
   *
   * @param table 행 수를 확인할 테스트 테이블
   * @return 조회한 행 수
   */
  private int count(String table) {
    return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
  }
}
