package com.todayit.place.raw.service;

import com.todayit.place.raw.service.model.PlaceNormalizationResult;
import com.todayit.place.raw.service.model.PlaceNormalizationResult.Status;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 성공 원본을 읽어서 정제 결과만 반환합니다. 공급원 호출과 DB 쓰기는 하지 않습니다. */
@Service
public class PlaceNormalizationQueryService {

  private static final Logger LOG = LoggerFactory.getLogger(PlaceNormalizationQueryService.class);
  private final ExternalPlaceRawQueryService rawQueryService;
  private final TourApiPlaceNormalizationService normalizer;

  /**
   * 읽기 전용 원본 조회와 순수 변환 서비스를 주입합니다.
   *
   * @param rawQueryService 원본 조회
   * @param normalizer TourAPI 정제 규칙
   */
  public PlaceNormalizationQueryService(
      ExternalPlaceRawQueryService rawQueryService, TourApiPlaceNormalizationService normalizer) {
    this.rawQueryService = rawQueryService;
    this.normalizer = normalizer;
  }

  /**
   * TourAPI 성공 원본의 정제 결과를 미리 봅니다. 검수·제외가 있어도 실행 자체는 정상이며 요약으로 구분합니다.
   *
   * @param limit 최대 조회 건수, 1~10
   * @return 불변 정제 결과 목록, 대상이 없으면 빈 목록
   * @throws IllegalArgumentException 조회 건수가 범위를 벗어날 때
   */
  @Transactional(propagation = Propagation.NEVER)
  public List<PlaceNormalizationResult> previewTourApi(int limit) {
    var results =
        rawQueryService.findSucceeded("TOUR_API", limit).stream()
            .map(normalizer::normalize)
            .toList();
    long normalized =
        results.stream().filter(result -> result.status() == Status.NORMALIZED).count();
    long excluded = results.stream().filter(result -> result.status() == Status.EXCLUDED).count();
    LOG.info(
        "PLACE_NORMALIZATION_PREVIEW 정제 미리보기 공급원=TOUR_API 전체={} 정상={} 제외={} 검수={}",
        results.size(),
        normalized,
        excluded,
        results.size() - normalized - excluded);
    results.stream()
        .filter(result -> result.reason() != null)
        .collect(Collectors.groupingBy(PlaceNormalizationResult::reason, Collectors.counting()))
        .forEach(
            (reason, count) ->
                LOG.info("PLACE_NORMALIZATION_REASON 정제 사유={} 건수={}", reason, count));
    return results;
  }
}
