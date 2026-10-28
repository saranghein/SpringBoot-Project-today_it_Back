package com.todayit.place.ingestion.service;

import com.todayit.place.ingestion.config.PlaceUpsertProperties;
import com.todayit.place.ingestion.repository.PlaceSourceRepository;
import com.todayit.place.ingestion.service.model.PlaceUpsertResult;
import com.todayit.place.ingestion.service.model.PlaceUpsertResult.Status;
import com.todayit.place.raw.service.model.PlaceNormalizationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 정제와 지역 조회를 통과한 장소를 건별 트랜잭션으로 반영합니다. 저장 기능을 켰을 때만 등록합니다. */
@Service
@EnableConfigurationProperties(PlaceUpsertProperties.class)
@ConditionalOnProperty(name = "todayit.place.upsert.enabled", havingValue = "true")
public class PlaceUpsertCommandService {

  private static final Logger LOG = LoggerFactory.getLogger(PlaceUpsertCommandService.class);
  private final PlaceRegionQueryService regionQueryService;
  private final PlaceSourceRepository repository;
  private final PlaceIngestionFailureService failureService;

  /**
   * 지역 조회 서비스와 원천 장소 저장소를 주입합니다.
   *
   * @param regionQueryService 시도·시군구 코드로 실제 지역 ID를 찾는 조회 구현
   * @param repository 원천 식별자 기반 멱등 저장 구현
   * @param failureService 반영 성공과 함께 실패 목록을 해소할 서비스
   */
  public PlaceUpsertCommandService(
      PlaceRegionQueryService regionQueryService,
      PlaceSourceRepository repository,
      PlaceIngestionFailureService failureService) {
    this.regionQueryService = regionQueryService;
    this.repository = repository;
    this.failureService = failureService;
  }

  /**
   * 정상 정제 결과의 장소·지역 연결·이미지 URL을 반영하고 해당 원본의 적재 실패 기록을 같은 트랜잭션에서 제거합니다. 외부 API와 이미지 파일은 요청하지 않습니다.
   *
   * @param result TourAPI 정제 결과
   * @return 반영 또는 보류 결과
   * @throws IllegalArgumentException 정상 정제 결과에 장소 또는 원천 ID가 없을 때
   * @throws IllegalStateException 지역 또는 저장 구현이 유효하지 않은 ID를 반환할 때
   */
  @Transactional
  public PlaceUpsertResult upsert(PlaceNormalizationResult result) {
    if (result.status() != PlaceNormalizationResult.Status.NORMALIZED) {
      return new PlaceUpsertResult(result.sourceId(), Status.NORMALIZATION_SKIPPED, null);
    }
    if (result.place() == null || result.sourceId() == null || result.sourceId().isBlank()) {
      throw new IllegalArgumentException("정상 정제 결과의 필수 값이 없습니다.");
    }
    var place = result.place();
    var regionId = regionQueryService.findRegionId(place.sidoCode(), place.sigunguCode());
    if (regionId.isEmpty()) {
      LOG.info("PLACE_UPSERT_DEFERRED 장소 적재 보류 공급원=TOUR_API 사유=지역 미등록");
      return new PlaceUpsertResult(result.sourceId(), Status.REGION_NOT_FOUND, null);
    }
    if (regionId.orElseThrow() <= 0) {
      throw new IllegalStateException("지역 조회 구현이 유효하지 않은 ID를 반환했습니다.");
    }
    int placeId = repository.upsert("TOUR_API", result.sourceId(), regionId.orElseThrow(), place);
    if (placeId <= 0) {
      throw new IllegalStateException("장소 저장 구현이 유효하지 않은 ID를 반환했습니다.");
    }
    failureService.clearFailure(result.rawId());
    LOG.debug("PLACE_UPSERT_STAGED 장소 저장 처리 완료 공급원=TOUR_API 커밋 대기");
    return new PlaceUpsertResult(result.sourceId(), Status.APPLIED, placeId);
  }
}
