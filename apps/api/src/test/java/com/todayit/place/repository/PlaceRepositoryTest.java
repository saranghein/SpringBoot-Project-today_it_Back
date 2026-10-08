package com.todayit.place.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.todayit.place.entity.Place;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL 장소 목록 조회 Repository의 조건과 페이징을 검증합니다. */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class PlaceRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRESQL =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("todayit")
          .withUsername("todayit_user")
          .withPassword("todayit1234")
          .withUrlParam("currentSchema", "todayit");

  @Autowired private PlaceRepository placeRepository;

  @Autowired private JdbcTemplate jdbcTemplate;

  /** 각 테스트가 독립적으로 실행되도록 장소 데이터를 초기화합니다. */
  @BeforeEach
  void setUp() {
    jdbcTemplate.update("DELETE FROM place_member_like");
    jdbcTemplate.update("DELETE FROM place_scrap");
    jdbcTemplate.update("DELETE FROM hours");
    jdbcTemplate.update("DELETE FROM place_image");
    jdbcTemplate.update("DELETE FROM place");
  }

  /** 활성화되고 삭제되지 않은 장소만 페이지 단위로 반환하는지 검증합니다. */
  @Test
  @DisplayName("활성화되고 삭제되지 않은 장소만 페이지 단위로 조회한다")
  @Transactional
  void findsActiveAndUndeletedPlaces() {
    // Given
    jdbcTemplate.update(
        """
        INSERT INTO place (
            name, latitude, longitude, address, category, view_count, is_active, is_deleted
        ) VALUES
            ('활성 장소', 37.50000000, 127.00000000, '주소 1', 'RESTAURANT', 10, TRUE, FALSE),
            ('비활성 장소', 37.50000000, 127.00000000, '주소 2', 'CAFE_DESSERT', 20, FALSE, FALSE),
            ('삭제 장소', 37.50000000, 127.00000000, '주소 3', 'BAR', 30, TRUE, TRUE)
        """);

    // When
    Page<Place> result = placeRepository.findByIsActiveTrueAndIsDeletedFalse(PageRequest.of(0, 10));

    // Then
    assertThat(result.getTotalElements()).isEqualTo(1);
    assertThat(result.getContent()).hasSize(1);
    assertThat(result.getContent().getFirst().getSnapshot().name()).isEqualTo("활성 장소");
  }
}
