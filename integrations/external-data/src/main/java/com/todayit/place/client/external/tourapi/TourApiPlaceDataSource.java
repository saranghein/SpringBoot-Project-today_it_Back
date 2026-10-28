package com.todayit.place.client.external.tourapi;

import com.todayit.place.client.external.PlaceDataSource;
import com.todayit.place.client.external.PlaceSourceException;
import com.todayit.place.client.external.PlaceSourceException.Reason;
import com.todayit.place.client.external.support.PlaceSourceCallLogger;
import com.todayit.place.client.external.support.PlaceSourceCallLogger.Operation;
import com.todayit.place.client.external.support.PlaceSourceHttpClient;
import com.todayit.place.client.external.support.SourceResponses;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.IntNode;

/** TourAPI KorService2의 지역 기반 장소 목록과 contentid별 상세 원본을 조회합니다. 지역 코드 목록은 수집하지 않습니다. */
public final class TourApiPlaceDataSource implements PlaceDataSource {

  private static final Set<String> LIST_FILTERS =
      Set.of(
          "lDongRegnCd",
          "lDongSignguCd",
          "lclsSystm1",
          "lclsSystm2",
          "lclsSystm3",
          "contentTypeId");
  private final PlaceSourceHttpClient http;
  private final String mobileApp;

  /**
   * TourAPI HTTP 설정을 받습니다.
   *
   * @param http 공급원 HTTP Client
   * @param mobileApp 공공데이터포털에 전달할 앱 이름
   */
  public TourApiPlaceDataSource(PlaceSourceHttpClient http, String mobileApp) {
    this.http = http;
    this.mobileApp = mobileApp;
  }

  @Override
  public SourceKey sourceKey() {
    return new SourceKey("TOUR_API", "kor-service2");
  }

  @Override
  public SourceCapabilities capabilities() {
    return new SourceCapabilities(Set.of(Component.BASIC), Set.of(Component.values()), true);
  }

  @Override
  public SourcePage fetchPage(SourcePageRequest request) {
    return PlaceSourceCallLogger.call(
        sourceKey(), request.component(), Operation.PAGE, () -> fetchPageInternal(request));
  }

  /**
   * 지역 기반 기본 목록을 조회하며 지원하지 않는 조건이나 구성 요소는 호출 전에 거부합니다.
   *
   * @param request BASIC 구성 요소의 페이지 요청
   * @return 원천 ID와 페이지 진행 정보를 검증한 목록
   * @throws PlaceSourceException 미지원 작업·잘못된 조건·외부 호출·응답 검증 실패 시
   */
  private SourcePage fetchPageInternal(SourcePageRequest request) {
    if (request.component() != Component.BASIC) {
      throw new PlaceSourceException(Reason.UNSUPPORTED);
    }
    SourceResponses.validateFilters(request.filters(), LIST_FILTERS);
    Map<String, String> parameters = common();
    parameters.putAll(request.filters());
    parameters.put("pageNo", Integer.toString(SourceResponses.pageNumber(request.cursor())));
    parameters.put("numOfRows", Integer.toString(request.pageSize()));
    parameters.put("arrange", "C");
    JsonNode body = responseBody("/areaBasedList2", parameters);
    JsonNode rows = body.path("items").path("item");
    long reportedRows = SourceResponses.nonNegativeNumber(body.path("numOfRows"));
    if (reportedRows != request.pageSize() && reportedRows != SourceResponses.rows(rows).size()) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    // TourAPI는 마지막 페이지의 numOfRows에 실제 반환 건수를 넣을 수 있어 요청 크기로 위치를 계산합니다.
    return SourceResponses.page(
        request,
        rows,
        "contentid",
        body.path("totalCount"),
        IntNode.valueOf(request.pageSize()),
        body.path("pageNo"));
  }

  @Override
  public SourceComponent fetchComponent(SourceComponentRequest request) {
    return PlaceSourceCallLogger.call(
        sourceKey(),
        request.component(),
        Operation.COMPONENT,
        () -> fetchComponentInternal(request));
  }

  /**
   * 공통·소개·이미지 원본을 조회하고 본문의 contentid를 요청 ID와 대조합니다. 한 번에 조회한 1000건 범위를 넘어선 상세는 부분 성공으로 반환하지 않습니다.
   *
   * @param request 원천 ID·구성 요소·소개 조회에 필요한 contentTypeId
   * @return 빈 결과를 포함한 원본 항목의 JSON 배열
   * @throws PlaceSourceException 필수 조건 누락·외부 호출·ID 또는 건수 검증 실패 시
   */
  private SourceComponent fetchComponentInternal(SourceComponentRequest request) {
    // KorService2는 소개 조회에만 contentTypeId를 허용합니다.
    SourceResponses.validateFilters(
        request.filters(),
        request.component() == Component.INTRODUCTION ? Set.of("contentTypeId") : Set.of());
    Map<String, String> parameters = common();
    parameters.putAll(request.filters());
    parameters.put("contentId", request.sourceId());
    String path =
        switch (request.component()) {
          case BASIC -> "/detailCommon2";
          case INTRODUCTION -> "/detailIntro2";
          case IMAGES -> "/detailImage2";
        };
    if (request.component() == Component.INTRODUCTION && !parameters.containsKey("contentTypeId")) {
      throw new PlaceSourceException(Reason.INVALID_REQUEST);
    }
    if (request.component() == Component.IMAGES) {
      parameters.put("imageYN", "Y");
    }
    // 상세의 숨은 페이지를 누락하지 않도록 응답 totalCount를 검사합니다.
    parameters.put("numOfRows", "1000");
    parameters.put("pageNo", "1");
    JsonNode body = responseBody(path, parameters);
    var items = SourceResponses.rows(body.path("items").path("item"));
    if (SourceResponses.nonNegativeNumber(body.path("totalCount")) != items.size()) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    for (JsonNode item : items) {
      if (!request.sourceId().equals(item.path("contentid").asText())) {
        throw new PlaceSourceException(Reason.INVALID_RESPONSE);
      }
    }
    return new SourceComponent(
        request.sourceId(), request.component(), items.toString(), Instant.now());
  }

  /**
   * 호출마다 독립적인 공통 요청 조건을 생성합니다. 인증키는 HTTP Client가 별도로 추가합니다.
   *
   * @return 앱 식별 정보와 JSON 응답 형식을 담은 변경 가능한 Map
   */
  private Map<String, String> common() {
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("MobileOS", "ETC");
    parameters.put("MobileApp", mobileApp);
    parameters.put("_type", "json");
    return parameters;
  }

  /**
   * HTTP 성공과 별개로 TourAPI 업무 결과 코드와 body 구조를 검사합니다.
   *
   * @param path 구현체가 정한 상대 경로
   * @param parameters 인증키를 제외한 검증된 조회 조건
   * @return 성공 응답의 body 객체
   * @throws PlaceSourceException 외부 호출 실패·업무 오류·body 구조 오류 시
   */
  private JsonNode responseBody(String path, Map<String, String> parameters) {
    JsonNode response = http.get(path, parameters).path("response");
    PlaceSourceHttpClient.checkCode(response.path("header").path("resultCode").asText());
    JsonNode body = response.path("body");
    if (!body.isObject()) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    return body;
  }
}
