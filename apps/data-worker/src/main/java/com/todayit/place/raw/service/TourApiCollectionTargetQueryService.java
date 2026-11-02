package com.todayit.place.raw.service;

import com.todayit.place.raw.config.NationwideCollectionProperties;
import com.todayit.place.raw.config.RawCollectionProperties;
import com.todayit.place.raw.service.command.TourApiCollectionScope;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 수동 대상 또는 DB 시도별 수집 대상을 외부 호출 전에 확정합니다. 지역 데이터는 변경하지 않습니다. */
@Service
@EnableConfigurationProperties(NationwideCollectionProperties.class)
@ConditionalOnExpression(
    "${todayit.place.collection.enabled:false} or ${todayit.place.schedule.enabled:false}")
public class TourApiCollectionTargetQueryService {

  private static final Logger LOG =
      LoggerFactory.getLogger(TourApiCollectionTargetQueryService.class);
  private final JdbcTemplate jdbc;
  private final NationwideCollectionProperties nationwide;

  /**
   * @param jdbc 시도 목록 조회 도구
   * @param nationwide 자동 생성 여부와 관광타입
   */
  public TourApiCollectionTargetQueryService(
      JdbcTemplate jdbc, NationwideCollectionProperties nationwide) {
    this.jdbc = jdbc;
    this.nationwide = nationwide;
  }

  /**
   * 비활성일 때는 기존 수동 대상을 그대로 사용합니다. 활성일 때는 시도 코드 오름차순·관광타입 설정 순서로 조합하며 시군구는 지정하지 않습니다.
   *
   * @param properties 기존 단일 범위와 수동 대상 목록
   * @return 실행 중 변경되지 않는 검증된 수집 범위
   * @throws IllegalArgumentException 전국 수집과 수동 targets를 함께 지정하거나 코드 형식이 잘못됐을 때
   * @throws IllegalStateException 전국 수집을 켰으나 등록된 시도가 없을 때
   */
  @Transactional(readOnly = true)
  public List<TourApiCollectionScope> findScopes(RawCollectionProperties properties) {
    if (!nationwide.enabled()) {
      return properties.toScopes();
    }
    if (!properties.targets().isEmpty()) {
      throw new IllegalArgumentException("전국 수집과 수동 targets를 함께 지정할 수 없습니다.");
    }
    List<String> codes =
        jdbc.queryForList("SELECT DISTINCT sido_code FROM region ORDER BY sido_code", String.class);
    if (codes.isEmpty()) {
      throw new IllegalStateException("전국 수집에 필요한 지역 기준 데이터가 없습니다.");
    }
    ArrayList<TourApiCollectionScope> scopes = new ArrayList<TourApiCollectionScope>();
    for (String code : codes) {
      for (String type : nationwide.contentTypeIds()) {
        scopes.add(new TourApiCollectionScope(code, "", type));
      }
    }
    LOG.info(
        "PLACE_RAW_NATIONWIDE_TARGETS 전국 대상 생성 시도수={} 관광타입수={} 전체대상={}",
        codes.size(),
        nationwide.contentTypeIds().size(),
        scopes.size());
    return List.copyOf(scopes);
  }
}
