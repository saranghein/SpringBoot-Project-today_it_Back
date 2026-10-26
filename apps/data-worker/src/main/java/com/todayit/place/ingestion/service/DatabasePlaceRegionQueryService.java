package com.todayit.place.ingestion.service;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 같은 DB의 지역 코드를 조회합니다. 지역 중복을 임의로 한 건으로 축소하지 않습니다. */
@Service
@ConditionalOnProperty(name = "todayit.place.upsert.enabled", havingValue = "true")
public class DatabasePlaceRegionQueryService implements PlaceRegionQueryService {

  private final JdbcTemplate jdbc;

  /**
   * worker와 동일한 DataSource의 조회 도구를 받습니다.
   *
   * @param jdbc 지역 조회에 사용할 JDBC 도구
   */
  public DatabasePlaceRegionQueryService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * 코드가 정확히 일치하는 지역 하나를 반환합니다. 기준 데이터를 생성하거나 변경하지 않습니다.
   *
   * @param sidoCode 시도 코드 2자리
   * @param sigunguCode 시군구 코드 3자리
   * @return 일치하는 지역 식별자, 미등록이면 빈 값
   * @throws IllegalArgumentException 코드 형식이 잘못됐을 때
   * @throws DataIntegrityViolationException 같은 코드의 지역이 여러 건이거나 식별자가 양수가 아닐 때
   */
  @Override
  @Transactional(readOnly = true)
  public Optional<Integer> findRegionId(String sidoCode, String sigunguCode) {
    if (sidoCode == null
        || !sidoCode.matches("[0-9]{2}")
        || sigunguCode == null
        || !sigunguCode.matches("[0-9]{3}")) {
      throw new IllegalArgumentException("지역 코드는 시도 2자리·시군구 3자리여야 합니다.");
    }
    var ids =
        jdbc.queryForList(
            "SELECT region_id FROM region WHERE sido_code = ? AND sigungu_code = ? LIMIT 2",
            Integer.class,
            sidoCode,
            sigunguCode);
    if (ids.size() > 1 || (!ids.isEmpty() && ids.getFirst() <= 0)) {
      throw new DataIntegrityViolationException("지역 코드 조회 결과가 유일한 양수 식별자가 아닙니다.");
    }
    return ids.stream().findFirst();
  }
}
