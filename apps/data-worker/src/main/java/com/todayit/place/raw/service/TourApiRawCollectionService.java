package com.todayit.place.raw.service;

import com.todayit.place.client.external.PlaceDataSource;
import com.todayit.place.client.external.PlaceDataSource.Component;
import com.todayit.place.client.external.PlaceDataSource.SourceComponentRequest;
import com.todayit.place.client.external.PlaceDataSource.SourceItem;
import com.todayit.place.client.external.PlaceDataSource.SourceKey;
import com.todayit.place.client.external.PlaceDataSource.SourcePage;
import com.todayit.place.client.external.PlaceDataSource.SourcePageRequest;
import com.todayit.place.client.external.PlaceDataSourceRegistry;
import com.todayit.place.client.external.PlaceSourceException;
import com.todayit.place.client.external.PlaceSourceException.Reason;
import com.todayit.place.raw.service.command.TourApiCollectionScope;
import com.todayit.place.raw.service.model.RawCollectionResult;
import com.todayit.place.raw.service.model.RawRetryTarget;
import java.time.Instant;
import java.util.HashSet;
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

/** TourAPI 선택 지역·관광타입의 표본·전체 수집과 실패 재수집을 수행합니다. 서비스 장소 반영은 하지 않습니다. */
@Service
public class TourApiRawCollectionService {

  private static final Logger LOG = LoggerFactory.getLogger(TourApiRawCollectionService.class);
  private static final SourceKey SOURCE = new SourceKey("TOUR_API", "kor-service2");
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
   * 서울 관광지의 첫 페이지와 공통·소개·이미지 상세를 묶어 저장합니다. 인증·호출 한도 오류가 발생하면 실패를 기록한 뒤 외부 호출을 중단합니다.
   *
   * @param limit 수집할 첫 페이지 크기, 1~10
   * @return 실제 시도·성공·실패 건수
   * @throws IllegalArgumentException 수집 크기가 범위를 벗어날 때
   * @throws PlaceSourceException 공급원이 꺼져 있거나 목록 수집에 실패할 때
   */
  @Transactional(propagation = Propagation.NEVER)
  public RawCollectionResult collectSample(int limit) {
    return collectSample(limit, TourApiCollectionScope.seoulAttractions());
  }

  /**
   * 선택한 지역·관광타입의 첫 페이지와 상세를 수집합니다. 개별 상세 실패 후에는 다음 장소를 처리하고, 인증·호출 한도 오류가 발생하면 중단 결과를 반환합니다.
   *
   * @param limit 첫 페이지 크기, 1~10
   * @param scope 검증한 수집 범위
   * @return 실제 시도·성공·실패 건수
   * @throws IllegalArgumentException 페이지 크기가 잘못됐을 때
   * @throws PlaceSourceException 공급원이 꺼져 있거나 목록 조회에 실패할 때
   */
  @Transactional(propagation = Propagation.NEVER)
  public RawCollectionResult collectSample(int limit, TourApiCollectionScope scope) {
    if (limit < 1 || limit > 10) {
      throw new IllegalArgumentException("표본 수집 크기는 1~10이어야 합니다.");
    }
    PlaceDataSource source = registry.get(SOURCE);
    logScope(scope);
    var page = source.fetchPage(new SourcePageRequest(Component.BASIC, "", limit, scope.filters()));
    if (page.items().size() > limit
        || page.items().stream().map(SourceItem::sourceId).distinct().count()
            != page.items().size()) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    int succeeded = 0;
    int failed = 0;
    boolean stoppedEarly = false;
    for (SourceItem item : page.items()) {
      Reason reason = collectAndSave(source, item.sourceId(), () -> item);
      if (reason == null) {
        succeeded++;
      } else {
        failed++;
      }
      if (shouldStop(reason)) {
        stoppedEarly = true;
        break;
      }
    }
    LOG.info("PLACE_RAW_COLLECTION 원본 수집 완료 공급원=TOUR_API 성공={} 실패={}", succeeded, failed);
    return new RawCollectionResult(succeeded + failed, succeeded, failed, stoppedEarly);
  }

  /**
   * 서울 관광지의 전체 페이지와 상세를 수집합니다. 실패 처리와 페이지 진행 검증은 수집 범위를 받는 collectAll 메서드와 같습니다.
   *
   * @param pageSize 페이지당 항목 수, 1~10
   * @return 전체 실행에서 실제 시도·성공·실패한 장소 수
   * @throws IllegalArgumentException 페이지 크기가 범위를 벗어날 때
   * @throws PlaceSourceException 공급원이 비활성이거나 목록 조회·진행 검증에 실패할 때
   */
  @Transactional(propagation = Propagation.NEVER)
  public RawCollectionResult collectAll(int pageSize) {
    return collectAll(pageSize, TourApiCollectionScope.seoulAttractions());
  }

  /**
   * 선택 범위의 모든 페이지에 동일한 필터를 사용합니다. 개별 상세 실패 후에는 다음 장소로 진행하고, 인증·호출 한도 오류가 발생하면 중단 결과를 반환합니다. 전체 건수
   * 변경·원천 ID 중복·cursor 반복·마지막 페이지의 누락을 감지하면 예외를 전파합니다.
   *
   * @param pageSize 페이지당 항목 수, 1~10
   * @param scope 검증한 수집 범위
   * @return 실제 시도·성공·실패 건수
   * @throws IllegalArgumentException 페이지 크기가 잘못됐을 때
   * @throws PlaceSourceException 공급원이 비활성이거나 목록 조회·진행 검증에 실패할 때
   */
  @Transactional(propagation = Propagation.NEVER)
  public RawCollectionResult collectAll(int pageSize, TourApiCollectionScope scope) {
    if (pageSize < 1 || pageSize > 10) {
      throw new IllegalArgumentException("전체 수집 페이지 크기는 1~10이어야 합니다.");
    }
    PlaceDataSource source = registry.get(SOURCE);
    logScope(scope);
    var cursors = new HashSet<String>();
    var sourceIds = new HashSet<String>();
    String cursor = "";
    long expectedTotal = -1;
    int pages = 0;
    int succeeded = 0;
    int failed = 0;
    try {
      do {
        if (!cursors.add(cursor)) {
          throw new PlaceSourceException(Reason.INVALID_RESPONSE);
        }
        SourcePage page =
            source.fetchPage(
                new SourcePageRequest(Component.BASIC, cursor, pageSize, scope.filters()));
        if (expectedTotal < 0) {
          expectedTotal = page.totalCount();
        }
        if (page.totalCount() < 0
            || page.totalCount() != expectedTotal
            || page.items().size() > pageSize
            || (!page.nextCursor().isEmpty()
                && (page.items().isEmpty() || cursors.contains(page.nextCursor())))) {
          throw new PlaceSourceException(Reason.INVALID_RESPONSE);
        }
        for (SourceItem item : page.items()) {
          if (!sourceIds.add(item.sourceId())) {
            throw new PlaceSourceException(Reason.INVALID_RESPONSE);
          }
        }
        if (sourceIds.size() > expectedTotal) {
          throw new PlaceSourceException(Reason.INVALID_RESPONSE);
        }
        pages++;
        for (SourceItem item : page.items()) {
          Reason reason = collectAndSave(source, item.sourceId(), () -> item);
          if (reason == null) {
            succeeded++;
          } else {
            failed++;
          }
          if (shouldStop(reason)) {
            logFullCollection(pages, succeeded, failed, false);
            return new RawCollectionResult(succeeded + failed, succeeded, failed, true);
          }
        }
        LOG.info(
            "PLACE_RAW_FULL_PAGE 전체 수집 진행 공급원=TOUR_API 페이지수={} 누적성공={} 누적실패={}",
            pages,
            succeeded,
            failed);
        cursor = page.nextCursor();
      } while (!cursor.isEmpty());
      if (sourceIds.size() != expectedTotal) {
        throw new PlaceSourceException(Reason.INVALID_RESPONSE);
      }
    } catch (PlaceSourceException exception) {
      LOG.warn(
          "PLACE_RAW_FULL_ABORTED 전체 수집 중단 공급원=TOUR_API 페이지수={} 성공={} 실패={} 사유={}",
          pages,
          succeeded,
          failed,
          exception.getReason());
      throw exception;
    }
    logFullCollection(pages, succeeded, failed, true);
    return new RawCollectionResult(succeeded + failed, succeeded, failed);
  }

  /**
   * 실제로 마지막 페이지까지 처리했는지와 전체 수집 결과를 함께 기록합니다.
   *
   * @param pages 처리한 페이지 수
   * @param succeeded 성공 장소 수
   * @param failed 실패 장소 수
   * @param completed 목록 끝까지 처리했는지 여부
   */
  private void logFullCollection(int pages, int succeeded, int failed, boolean completed) {
    LOG.info(
        "PLACE_RAW_FULL 전체 수집 결과 공급원=TOUR_API 페이지수={} 성공={} 실패={} 순회완료={}",
        pages,
        succeeded,
        failed,
        completed);
  }

  /**
   * 인증 정보 없이 선택한 수집 범위를 기록합니다.
   *
   * @param scope 수집 범위
   */
  private void logScope(TourApiCollectionScope scope) {
    LOG.info(
        "PLACE_RAW_SCOPE 수집 범위 공급원=TOUR_API 시도={} 시군구={} 관광타입={}",
        scope.sidoCode(),
        scope.sigunguCode().isEmpty() ? "전체" : scope.sigunguCode(),
        scope.contentTypeId());
  }

  /**
   * PK가 작은 TourAPI FAILED 원본부터 최대 limit건을 한 번씩 재수집합니다. 저장된 목록을 복원해 사용하며 목록 API는 호출하지 않습니다. 복원할 수 없는
   * 원본은 FAILED로 유지합니다.
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
    boolean stoppedEarly = false;
    for (RawRetryTarget target : targets) {
      Reason reason = collectAndSave(source, target.sourceId(), () -> restoreList(target));
      if (reason == null) {
        succeeded++;
      } else {
        failed++;
      }
      if (shouldStop(reason)) {
        stoppedEarly = true;
        break;
      }
    }
    LOG.info(
        "PLACE_RAW_RETRY 원본 재수집 완료 공급원=TOUR_API 시도={} 성공={} 실패={}",
        succeeded + failed,
        succeeded,
        failed);
    return new RawCollectionResult(succeeded + failed, succeeded, failed, stoppedEarly);
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
