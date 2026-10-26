package com.todayit.place.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** 실제 SQL로 코드 쌍 조회와 DB 유일 제약이 없는 상태의 중복 방어를 검증합니다. */
class DatabasePlaceRegionQueryServiceTest {

  private EmbeddedDatabase database;
  private JdbcTemplate jdbc;
  private DatabasePlaceRegionQueryService service;

  /** 테스트마다 외부 접속이 없는 지역 테이블을 준비합니다. */
  @BeforeEach
  void setUp() {
    database =
        new EmbeddedDatabaseBuilder()
            .generateUniqueName(true)
            .setType(EmbeddedDatabaseType.H2)
            .build();
    jdbc = new JdbcTemplate(database);
    jdbc.execute(
        "CREATE TABLE region (region_id INTEGER PRIMARY KEY, sido_code VARCHAR(5), sigungu_code VARCHAR(5), sido_name VARCHAR(10), sigungu_name VARCHAR(10))");
    jdbc.update(
        "INSERT INTO region VALUES (1, '11', '110', '서울특별시', '종로구'), (2, '26', '110', '부산광역시', '중구'), (3, '11', '140', '서울특별시', '중구')");
    service = new DatabasePlaceRegionQueryService(jdbc);
  }

  /** 테스트 전용 메모리 DB를 종료합니다. */
  @AfterEach
  void tearDown() {
    database.shutdown();
  }

  @Test
  void matchesBothCodesAndPreservesLeadingZeros() {
    assertThat(service.findRegionId("11", "110")).contains(1);
    assertThat(service.findRegionId("26", "110")).contains(2);
    jdbc.update("INSERT INTO region VALUES (4, '01', '010', '테스트', '테스트')");
    assertThat(service.findRegionId("01", "010")).contains(4);
  }

  @Test
  void missingRegionDoesNotCreateOrFallbackToAnotherRegion() {
    assertThat(service.findRegionId("11", "999")).isEmpty();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM region", Integer.class)).isEqualTo(3);
  }

  @Test
  void duplicateCodesFailInsteadOfSelectingAnArbitraryRegion() {
    jdbc.update("INSERT INTO region VALUES (4, '11', '110', '서울특별시', '종로구')");
    assertThatThrownBy(() -> service.findRegionId("11", "110"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void nonPositiveIdentifierFails() {
    jdbc.update("INSERT INTO region VALUES (0, '11', '999', '테스트', '테스트')");
    assertThatThrownBy(() -> service.findRegionId("11", "999"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  /** 실제 조회 구현이 적재 기능을 켰을 때만 Spring Bean으로 연결되는지 확인합니다. */
  @Test
  void registersOnlyWhenUpsertIsEnabled() {
    var contextRunner =
        new ApplicationContextRunner()
            .withUserConfiguration(DatabasePlaceRegionQueryService.class)
            .withBean(JdbcTemplate.class, () -> jdbc);
    contextRunner.run(
        context -> assertThat(context).doesNotHaveBean(PlaceRegionQueryService.class));
    contextRunner
        .withPropertyValues("todayit.place.upsert.enabled=true")
        .run(
            context -> {
              assertThat(context).hasSingleBean(PlaceRegionQueryService.class);
              assertThat(context.getBean(PlaceRegionQueryService.class).findRegionId("11", "110"))
                  .contains(1);
            });
  }

  @ParameterizedTest
  @CsvSource(
      value = {"NULL,110", "11,NULL", "1,110", "11,11", "AA,110", "11,1A0"},
      nullValues = "NULL")
  void rejectsMalformedCodes(String sidoCode, String sigunguCode) {
    assertThatThrownBy(() -> service.findRegionId(sidoCode, sigunguCode))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
