package com.todayit.place.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** 기존 장소를 변경하지 않고 원천 연결 테이블을 추가하는 V14와 식별자/FK 제약을 검증합니다. */
@Testcontainers
class PlaceSourceLinkMigrationTest {

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private Connection connection;

  /**
   * 기존 서비스 데이터를 준비한 뒤 원천 연결 Migration을 적용합니다.
   *
   * @throws SQLException 테스트 DB 준비·조회·변경에 실패할 때
   */
  @BeforeAll
  static void migrateExistingDatabase() throws SQLException {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .target("13")
        .load()
        .migrate();
    try (var connection = open();
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          """
          INSERT INTO place (name, latitude, longitude, address, category, view_count, is_active, is_deleted)
          VALUES ('외부 장소', 37.5, 127.1, '원래 주소', 'NATURE_WALK', 17, true, false),
                 ('수동 장소', 37.5, 127.1, '수동 주소', 'NATURE_WALK', 3, false, false)
          """);
      statement.executeUpdate(
          """
          INSERT INTO place_image (place_id, image_url) VALUES (1, 'https://example.com/manual.jpg')
          """);
    }
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .load()
        .migrate();
    try (var connection = open();
        var statement = connection.createStatement()) {
      try (var rows = statement.executeQuery("SELECT count(*) FROM place_source")) {
        rows.next();
        assertThat(rows.getInt(1)).isZero();
      }
      statement.executeUpdate(
          "INSERT INTO place_source (place_id, provider, source_id) VALUES (1, 'TOUR_API', '001')");
    }
  }

  /**
   * 각 Migration 검증을 격리할 DB 트랜잭션을 시작합니다.
   *
   * @throws SQLException 테스트 DB 준비·조회·변경에 실패할 때
   */
  @BeforeEach
  void beginTransaction() throws SQLException {
    connection = open();
    connection.setAutoCommit(false);
  }

  /**
   * 테스트 변경을 롤백하여 다음 검증에 영향을 주지 않게 합니다.
   *
   * @throws SQLException 테스트 DB 준비·조회·변경에 실패할 때
   */
  @AfterEach
  void rollback() throws SQLException {
    if (connection != null) {
      try {
        connection.rollback();
      } finally {
        connection.close();
      }
    }
  }

  /**
   * Migration 이후 기존 장소와 이미지 관계가 보존되는지 검증합니다.
   *
   * @throws SQLException 테스트 DB 준비·조회·변경에 실패할 때
   */
  @Test
  void preservesExistingPlacesAndRelatedImages() throws SQLException {
    try (var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                """
        SELECT p.place_id, p.name, p.view_count, p.is_active, s.provider, s.source_id
        FROM place p JOIN place_source s ON s.place_id = p.place_id
        """)) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getInt("place_id")).isEqualTo(1);
      assertThat(rows.getString("name")).isEqualTo("외부 장소");
      assertThat(rows.getInt("view_count")).isEqualTo(17);
      assertThat(rows.getBoolean("is_active")).isTrue();
      assertThat(rows.getString("provider")).isEqualTo("TOUR_API");
      assertThat(rows.getString("source_id")).isEqualTo("001");
      assertThat(rows.next()).isFalse();
    }
    assertThat(count("SELECT count(*) FROM place")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM place_image WHERE place_id = 1")).isEqualTo(1);
    assertThat(count("SELECT count(*) FROM place_source WHERE place_id = 2")).isZero();
  }

  /**
   * 서비스 장소 테이블에 원천 식별 컬럼이 추가되지 않았는지 검증합니다.
   *
   * @throws SQLException 테스트 DB 준비·조회·변경에 실패할 때
   */
  @Test
  void servicePlaceNoLongerHasSourceColumns() throws SQLException {
    assertThat(
            count(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'place' AND column_name IN ('provider', 'source_id')"))
        .isZero();
  }

  /** 같은 공급원·원천 ID가 서로 다른 장소에 중복 연결될 수 없는지 검증합니다. */
  @Test
  void sourcePairCannotPointAtTwoServicePlaces() {
    assertThatThrownBy(() -> link(2, "TOUR_API", "001"))
        .isInstanceOfSatisfying(
            SQLException.class,
            exception -> assertThat(exception.getSQLState()).isEqualTo("23505"));
  }

  /**
   * 여러 공급원을 하나의 장소에 명시적으로 연결할 수 있는지 검증합니다.
   *
   * @throws SQLException 테스트 DB 준비·조회·변경에 실패할 때
   */
  @Test
  void differentProvidersMayReferToSameServicePlaceWithoutAutomaticMerging() throws SQLException {
    link(1, "CULTURE", "001");

    assertThat(count("SELECT count(*) FROM place_source WHERE place_id = 1")).isEqualTo(2);
    assertThat(count("SELECT count(*) FROM place")).isEqualTo(2);
  }

  /**
   * 다른 공급원의 같은 원천 ID를 별개 장소에 연결할 수 있는지 검증합니다.
   *
   * @throws SQLException 테스트 DB 준비·조회·변경에 실패할 때
   */
  @Test
  void sameSourceIdFromDifferentProvidersMayReferToDifferentPlaces() throws SQLException {
    link(2, "CULTURE", "001");

    assertThat(count("SELECT count(*) FROM place_source WHERE place_id = 2")).isEqualTo(1);
  }

  /**
   * 원천 ID의 선행 0이 식별자의 일부로 보존되는지 검증합니다.
   *
   * @throws SQLException 테스트 DB 준비·조회·변경에 실패할 때
   */
  @Test
  void preservesLeadingZeroAsPartOfSourceIdentity() throws SQLException {
    link(2, "TOUR_API", "1");

    assertThat(count("SELECT count(*) FROM place_source WHERE provider = 'TOUR_API'")).isEqualTo(2);
  }

  /**
   * 공급원 또는 원천 ID의 NULL을 DB 제약으로 거부하는지 검증합니다.
   *
   * @param provider 공급원 코드
   * @param sourceId 공급원 장소 ID
   */
  @ParameterizedTest
  @CsvSource(
      value = {"<null>,002", "TOUR_API,<null>"},
      nullValues = "<null>")
  void rejectsMissingSourceIdentity(String provider, String sourceId) {
    assertThatThrownBy(() -> link(2, provider, sourceId))
        .isInstanceOfSatisfying(
            SQLException.class,
            exception -> assertThat(exception.getSQLState()).isEqualTo("23502"));
  }

  /**
   * 빈 공급원 또는 원천 ID를 DB 제약으로 거부하는지 검증합니다.
   *
   * @param provider 공급원 코드
   * @param sourceId 공급원 장소 ID
   */
  @ParameterizedTest
  @CsvSource({"' ',002", "TOUR_API,' '"})
  void rejectsBlankSourceIdentity(String provider, String sourceId) {
    assertThatThrownBy(() -> link(2, provider, sourceId))
        .isInstanceOfSatisfying(
            SQLException.class,
            exception -> assertThat(exception.getSQLState()).isEqualTo("23514"));
  }

  /** 존재하지 않는 서비스 장소의 원천 연결을 FK로 거부하는지 검증합니다. */
  @Test
  void cannotLinkUnknownServicePlace() {
    assertThatThrownBy(() -> link(99999, "TOUR_API", "002"))
        .isInstanceOfSatisfying(
            SQLException.class,
            exception -> assertThat(exception.getSQLState()).isEqualTo("23503"));
  }

  /** 원천 연결이 있는 장소의 물리 삭제를 FK로 막는지 검증합니다. */
  @Test
  void linkedPlaceCannotBePhysicallyDeletedSilently() {
    assertThatThrownBy(
            () -> {
              try (var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM place_image WHERE place_id = 1");
                statement.executeUpdate("DELETE FROM place WHERE place_id = 1");
              }
            })
        .isInstanceOfSatisfying(
            SQLException.class,
            exception -> assertThat(exception.getSQLState()).isEqualTo("23503"));
  }

  /**
   * 지정한 서비스 장소에 공급원과 원천 ID를 연결합니다.
   *
   * @param placeId 연결할 서비스 장소 ID
   * @param provider 공급원 코드
   * @param sourceId 공급원 장소 ID
   * @throws SQLException 테스트 DB 준비·조회·변경에 실패할 때
   */
  private void link(int placeId, String provider, String sourceId) throws SQLException {
    try (var statement =
        connection.prepareStatement(
            "INSERT INTO place_source (place_id, provider, source_id) VALUES (?, ?, ?)")) {
      statement.setInt(1, placeId);
      statement.setString(2, provider);
      statement.setString(3, sourceId);
      statement.executeUpdate();
    }
  }

  /**
   * 검증 대상 테이블 또는 조회문의 행 수를 반환합니다.
   *
   * @param sql 테스트에서 실행할 행 수 조회문
   * @return 조회한 행 수
   * @throws SQLException 테스트 DB 준비·조회·변경에 실패할 때
   */
  private long count(String sql) throws SQLException {
    try (var statement = connection.createStatement();
        var rows = statement.executeQuery(sql)) {
      rows.next();
      return rows.getLong(1);
    }
  }

  /**
   * 격리된 PostgreSQL 컨테이너의 테스트 DB 연결을 엽니다.
   *
   * @return 테스트 DB 연결
   * @throws SQLException 테스트 DB 준비·조회·변경에 실패할 때
   */
  private static Connection open() throws SQLException {
    return DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
