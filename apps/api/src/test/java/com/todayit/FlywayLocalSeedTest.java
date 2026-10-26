package com.todayit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@ActiveProfiles({"test", "local"})
@SpringBootTest
class FlywayLocalSeedTest {

  @ServiceConnection
  static final PostgreSQLContainer POSTGRESQL =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("todayit")
          .withUsername("todayit_user")
          .withPassword("todayit1234")
          .withUrlParam("currentSchema", "todayit");

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("지역과 회원 생성 후 회원별 권한을 연결한다")
  void seedsOneDummyMemberForEachRole() {
    // Given
    List<String> expectedRoleNames = List.of("ADMIN", "DEV", "USER");

    // When
    List<String> roleNames =
        jdbcTemplate.queryForList(
            """
            SELECT roles.name
            FROM "todayit".member_roles
            JOIN "todayit".roles ON roles.roles_id = member_roles.roles_id
            JOIN "todayit".member ON member.member_id = member_roles.member_id
            WHERE member.email IN (
                'user@todayit.local',
                'admin@todayit.local',
                'dev@todayit.local'
            )
            ORDER BY roles.name
            """,
            String.class);

    // Then
    assertThat(roleNames).containsExactlyElementsOf(expectedRoleNames);
  }

  @Test
  @DisplayName("전국 지역 생성 후 모든 더미 장소를 지역에 연결한다")
  void seedsNationwideRegionsAndConnectsEveryDummyPlace() {
    // Given
    int expectedRegionCount = 269;

    // When
    Integer regionCount =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \"todayit\".region", Integer.class);
    Integer placeCount =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \"todayit\".place", Integer.class);
    Integer unmappedPlaceCount =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM "todayit".place
            LEFT JOIN "todayit".region_place ON region_place.place_id = place.place_id
            WHERE region_place.region_place_id IS NULL
            """,
            Integer.class);

    // Then
    assertThat(regionCount).isEqualTo(expectedRegionCount);
    assertThat(placeCount).isPositive();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT sigungu_name FROM todayit.region WHERE sido_code = '41' AND sigungu_code = '115'",
                String.class))
        .isEqualTo("수원시 팔달구");
    assertThat(unmappedPlaceCount).isZero();
    assertThat(hasRegion("11", "110")).isTrue();
    assertThat(hasRegion("41", "115")).isTrue();
    assertThat(hasRegion("36", "110")).isTrue();
  }

  @Test
  @DisplayName("회원과 장소 생성 후 더미 코스와 코스별 장소를 연결한다")
  void seedsCoursesAfterMembersAndPlaces() {
    // Given
    int expectedCourseCount = 2;
    int expectedCoursePlaceCount = 6;

    // When
    Integer courseCount =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \"todayit\".course", Integer.class);
    Integer coursePlaceCount =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM \"todayit\".course_place", Integer.class);

    // Then
    assertThat(courseCount).isEqualTo(expectedCourseCount);
    assertThat(coursePlaceCount).isEqualTo(expectedCoursePlaceCount);
  }

  private boolean hasRegion(String sidoCode, String sigunguCode) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM \"todayit\".region WHERE sido_code = ? AND sigungu_code = ?",
            Integer.class,
            sidoCode,
            sigunguCode);
    return count != null && count > 0;
  }
}
