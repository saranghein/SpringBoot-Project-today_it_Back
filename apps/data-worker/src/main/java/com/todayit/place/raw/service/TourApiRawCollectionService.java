package com.todayit.place.raw.service;

import com.todayit.place.client.external.PlaceDataSource;
import com.todayit.place.client.external.PlaceDataSource.Component;
import com.todayit.place.client.external.PlaceDataSource.SourceComponentRequest;
import com.todayit.place.client.external.PlaceDataSource.SourceItem;
import com.todayit.place.client.external.PlaceDataSource.SourceKey;
import com.todayit.place.client.external.PlaceDataSource.SourcePageRequest;
import com.todayit.place.client.external.PlaceDataSourceRegistry;
import com.todayit.place.client.external.PlaceSourceException;
import com.todayit.place.client.external.PlaceSourceException.Reason;
import com.todayit.place.raw.service.model.RawCollectionResult;
import com.todayit.place.raw.service.model.RawRetryTarget;
import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** TourAPI 표본 수집과 실패 원본 재수집을 최대 10건씩 수행합니다. 정제나 서비스 장소 반영은 하지 않습니다. */
@Service
public class TourApiRawCollectionService {

  private static final Logger LOG = LoggerFactory.getLogger(TourApiRawCollectionService.class);
  private static final SourceKey SOURCE = new SourceKey("TOUR_API", "kor-service2");
  private static final Map<String, String> SAMPLE_FILTERS =
      Map.of("lDongRegnCd", "11", "contentTypeId", "12");
  private final PlaceDataSourceRegistry registry;
  private final ExternalPlaceRawCommandService commandService;
  private final ExternalPlaceRawQueryService queryService;
  private final JsonMapper json = JsonMapper.builder().build();

  /**
   * 공급원 선택과 원본 저장 서비스를 주입합니다.
   *
   * @param registry 활성 공급원 Registry
   * @param commandService 별도 트랜잭션의 저장 서비스
   * @param queryService 별도 읽기 트랜잭션의 재수집 대상 조회 서비스
   */
  public TourApiRawCollectionService(
      PlaceDataSourceRegistry registry,
      ExternalPlaceRawCommandService commandService,
      ExternalPlaceRawQueryService queryService) {
    this.registry = registry;
    this.commandService = commandService;
    this.queryService = queryService;
  }

  /**
   * 목록과 공통·소개·이미지를 묶어 저장합니다. 인증·쿼터 실패는 기록 후 남은 외부 호출을 중단합니다.
   *
   * @param limit 수집할 첫 페이지 크기, 1~10
   * @return 실제 시도·성공·실패 건수
   * @throws IllegalArgumentException 수집 크기가 범위를 벗어날 때
   * @throws PlaceSourceException 공급원이 꺼져 있거나 목록 수집에 실패할 때
   */
  @Transactional(propagation = Propagation.NEVER)
  public RawCollectionResult collectSample(int limit) {
    if (limit < 1 || limit > 10) {
      throw new IllegalArgumentException("표본 수집 크기는 1~10이어야 합니다.");
    }
    PlaceDataSource source = registry.get(SOURCE);
    var page = source.fetchPage(new SourcePageRequest(Component.BASIC, "", limit, SAMPLE_FILTERS));
    if (page.items().size() > limit
        || page.items().stream().map(SourceItem::sourceId).distinct().count()
            != page.items().size()) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    int succeeded = 0;
    int failed = 0;
    for (SourceItem item : page.items()) {
      Reason reason = collectAndSave(source, item.sourceId(), () -> item);
      if (reason == null) {
        succeeded++;
      } else {
        failed++;
      }
      if (shouldStop(reason)) {
        break;
      }
    }
    LOG.info("PLACE_RAW_COLLECTION 원본 수집 완료 공급원=TOUR_API 성공={} 실패={}", succeeded, failed);
    return new RawCollectionResult(succeeded + failed, succeeded, failed);
  }

  /**
   * 저장된 TourAPI FAILED 원본만 한 번씩 재수집합니다. 목록 API는 다시 호출하지 않습니다.
   *
   * @param limit 실행당 최대 재수집 건수, 1~10
   * @return 실제 시도·성공·실패 건수, 대상이 없으면 모두 0
   * @throws IllegalArgumentException 재수집 크기가 범위를 벗어날 때
   * @throws PlaceSourceException 대상이 있으나 공급원이 비활성일 때
   */
  @Transactional(propagation = Propagation.NEVER)
  public RawCollectionResult retryFailed(int limit) {
    var targets = queryService.findFailed(SOURCE.provider(), limit);
    if (targets.isEmpty()) {
      LOG.info("PLACE_RAW_RETRY 재수집 대상 없음 공급원=TOUR_API");
      return new RawCollectionResult(0, 0, 0);
    }
    PlaceDataSource source = registry.get(SOURCE);
    int succeeded = 0;
    int failed = 0;
    for (RawRetryTarget target : targets) {
      Reason reason = collectAndSave(source, target.sourceId(), () -> restoreList(target));
      if (reason == null) {
        succeeded++;
      } else {
        failed++;
      }
      if (shouldStop(reason)) {
        break;
      }
    }
    LOG.info(
        "PLACE_RAW_RETRY 원본 재수집 완료 공급원=TOUR_API 시도={} 성공={} 실패={}",
        succeeded + failed,
        succeeded,
        failed);
    return new RawCollectionResult(succeeded + failed, succeeded, failed);
  }

  /**
   * 장소 한 건의 원본 복원·외부 수집과 저장을 분리하고 수집 실패만 안전한 분류로 반환합니다.
   *
   * @param source TourAPI 구현체
   * @param sourceId 공급원 장소 ID
   * @param itemSupplier 목록 원본 또는 DB 원본에서 복원할 목록 항목
   * @return 성공 시 null, 수집 실패 시 안전한 오류 분류
   */
  private Reason collectAndSave(
      PlaceDataSource source, String sourceId, Supplier<SourceItem> itemSupplier) {
    SourceItem item = null;
    String payload;
    try {
      item = itemSupplier.get();
      payload = collectPayload(source, item);
    } catch (PlaceSourceException exception) {
      commandService.saveFailure(
          SOURCE.provider(),
          sourceId,
          item == null ? null : listPayload(item),
          exception.getReason());
      return exception.getReason();
    }
    // DB 오류는 수집 실패로 치환하지 않고 호출자에게 전파합니다.
    commandService.saveSuccess(SOURCE.provider(), sourceId, payload, Instant.now());
    return null;
  }

  /**
   * 재수집에 필요한 목록을 복원합니다. 원본이 없으면 ID나 관광타입을 추정하지 않습니다.
   *
   * @param target 저장된 실패 원본
   * @return 목록 원문으로 복원한 항목
   * @throws PlaceSourceException 저장된 JSON 또는 list 객체가 없거나 잘못됐을 때
   */
  private SourceItem restoreList(RawRetryTarget target) {
    JsonNode list = read(target.payloadJson()).path("list");
    if (!list.isObject()) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    return new SourceItem(target.sourceId(), list.toString());
  }

  /**
   * 인증·쿼터 오류 이후에는 추가 외부 호출을 하지 않습니다.
   *
   * @param reason 수집 실패 분류, 성공 시 null
   * @return 남은 대상 처리를 중단해야 하는지 여부
   */
  private boolean shouldStop(Reason reason) {
    return reason == Reason.AUTHENTICATION || reason == Reason.RATE_LIMIT;
  }

  /**
   * 목록 원문과 검증한 상세 배열을 그대로 묶습니다. 이미지가 없어도 성공이며 공통·소개는 필수입니다.
   *
   * @param source TourAPI 인터페이스 구현
   * @param item 목록의 장소 원본
   * @return list 객체와 common·intro·images 배열을 가진 JSON
   * @throws PlaceSourceException 원본 형태·식별자·필수 값이 잘못되거나 상세 조회가 실패할 때
   */
  private String collectPayload(PlaceDataSource source, SourceItem item) {
    JsonNode list = read(item.payloadJson());
    if (!list.isObject()
        || !item.sourceId().equals(list.path("contentid").asText())
        || list.path("title").asText().isBlank()
        || list.path("contenttypeid").asText().isBlank()) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    ObjectNode payload = json.createObjectNode();
    payload.set("list", list);
    for (Component component : Component.values()) {
      Map<String, String> filters =
          component == Component.INTRODUCTION
              ? Map.of("contentTypeId", list.path("contenttypeid").asText())
              : Map.of();
      var detail =
          source.fetchComponent(new SourceComponentRequest(item.sourceId(), component, filters));
      JsonNode rows = read(detail.payloadJson());
      if (!item.sourceId().equals(detail.sourceId())
          || detail.component() != component
          || !rows.isArray()
          || (component != Component.IMAGES && rows.size() != 1)) {
        throw new PlaceSourceException(Reason.INVALID_RESPONSE);
      }
      for (JsonNode row : rows) {
        if (!row.isObject() || !item.sourceId().equals(row.path("contentid").asText())) {
          throw new PlaceSourceException(Reason.INVALID_RESPONSE);
        }
      }
      if (component == Component.BASIC && rows.get(0).path("title").asText().isBlank()) {
        throw new PlaceSourceException(Reason.INVALID_RESPONSE);
      }
      String field =
          switch (component) {
            case BASIC -> "common";
            case INTRODUCTION -> "intro";
            case IMAGES -> "images";
          };
      payload.set(field, rows);
    }
    return payload.toString();
  }

  /**
   * 첫 실패 재수집에 필요한 contenttypeid를 잃지 않도록 목록만 보관합니다. 파싱 실패는 NULL 원본으로 남깁니다.
   *
   * @param item 실패한 목록 항목
   * @return 목록 JSON 또는 파싱 불가능할 때 null
   */
  private String listPayload(SourceItem item) {
    try {
      ObjectNode payload = json.createObjectNode();
      payload.set("list", read(item.payloadJson()));
      return payload.toString();
    } catch (PlaceSourceException exception) {
      return null;
    }
  }

  /**
   * 파싱 오류 본문을 저장·로그에 노출하지 않고 안정적인 실패 분류로 치환합니다.
   *
   * @param value 원본 JSON
   * @return 파싱한 JSON 값
   * @throws PlaceSourceException JSON이 없거나 파싱에 실패할 때
   */
  private JsonNode read(String value) {
    if (value == null || value.isBlank()) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    try {
      JsonNode node = json.readTree(value);
      if (node == null) {
        throw new PlaceSourceException(Reason.INVALID_RESPONSE);
      }
      return node;
    } catch (JacksonException exception) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
  }
}
