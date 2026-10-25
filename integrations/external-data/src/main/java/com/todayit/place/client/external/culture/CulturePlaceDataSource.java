package com.todayit.place.client.external.culture;

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

/** 문화포털 문화시설 XML을 원천 seq와 함께 JSON 표현으로 조회합니다. 행사 seq는 취급하지 않습니다. */
public final class CulturePlaceDataSource implements PlaceDataSource {

  private static final Set<String> FACILITIES =
      Set.of("performingplace", "artgallery", "museum", "hall", "library");
  private static final Set<String> FILTERS =
      Set.of("facility", "keyword", "gpsxfrom", "gpsyfrom", "gpsxto", "gpsyto");
  private final PlaceSourceHttpClient http;

  /**
   * 문화시설 조회서비스 HTTP 설정을 받습니다.
   *
   * @param http 공급원 HTTP Client
   */
  public CulturePlaceDataSource(PlaceSourceHttpClient http) {
    this.http = http;
  }

  @Override
  public SourceKey sourceKey() {
    return new SourceKey("CULTURE", "culture-art-spaces");
  }

  @Override
  public SourceCapabilities capabilities() {
    return new SourceCapabilities(Set.of(Component.BASIC), Set.of(Component.BASIC), true);
  }

  @Override
  public SourcePage fetchPage(SourcePageRequest request) {
    return PlaceSourceCallLogger.call(
        sourceKey(), request.component(), Operation.PAGE, () -> fetchPageInternal(request));
  }

  /**
   * 허용한 시설 종류의 목록을 조회하며 경로를 만드는 facility 값은 서버로 전송하지 않습니다.
   *
   * @param request facility가 지정된 BASIC 목록 요청
   * @return 시설 seq와 응답 페이지 정보를 검증한 목록
   * @throws PlaceSourceException 시설 종류·조건 오류, 미지원 작업 또는 외부 응답 검증 실패 시
   */
  private SourcePage fetchPageInternal(SourcePageRequest request) {
    requireBasic(request.component());
    SourceResponses.validateFilters(request.filters(), FILTERS);
    String facility = request.filters().get("facility");
    if (!FACILITIES.contains(facility == null ? "" : facility)) {
      throw new PlaceSourceException(Reason.INVALID_REQUEST);
    }
    Map<String, String> parameters = new LinkedHashMap<>(request.filters());
    parameters.remove("facility");
    parameters.put("PageNo", Integer.toString(SourceResponses.pageNumber(request.cursor())));
    parameters.put("numOfrows", Integer.toString(request.pageSize()));
    var body = responseBody("/" + facility, parameters);
    return SourceResponses.page(
        request,
        body.path("items").path("item"),
        "seq",
        body.path("totalCount"),
        body.path("numOfrows"),
        body.path("PageNo"));
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
   * 행사 ID가 아닌 문화시설 seq로 상세를 조회하고 빈 결과와 ID 불일치를 거부합니다.
   *
   * @param request 추가 필터가 없는 BASIC 상세 요청
   * @return 문화시설 상세 항목의 JSON 배열
   * @throws PlaceSourceException 미지원 작업·추가 필터·외부 호출·상세 결과 검증 실패 시
   */
  private SourceComponent fetchComponentInternal(SourceComponentRequest request) {
    requireBasic(request.component());
    SourceResponses.validateFilters(request.filters(), Set.of());
    var body = responseBody("/detail", Map.of("seq", request.sourceId()));
    var items = SourceResponses.rows(body.path("items").path("item"));
    if (items.isEmpty()
        || items.stream().anyMatch(item -> !request.sourceId().equals(item.path("seq").asText()))) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    return new SourceComponent(
        request.sourceId(), request.component(), items.toString(), Instant.now());
  }

  /**
   * 문화시설 계약에 없는 소개·사진 전용 조회를 외부 호출 전에 차단합니다.
   *
   * @param component 요청한 구성 요소
   * @throws PlaceSourceException BASIC이 아닌 구성 요소일 때
   */
  private void requireBasic(Component component) {
    if (component != Component.BASIC) {
      throw new PlaceSourceException(Reason.UNSUPPORTED);
    }
  }

  /**
   * 문화시설 업무 결과 코드와 body를 확인합니다. 배포 가이드의 reseultCode 표기도 허용합니다.
   *
   * @param path 허용한 시설 목록 또는 상세 상대 경로
   * @param parameters 인증키를 제외한 검증된 조회 조건
   * @return 성공 응답의 body 객체
   * @throws PlaceSourceException 외부 호출·업무 코드·body 구조 검증 실패 시
   */
  private JsonNode responseBody(String path, Map<String, String> parameters) {
    var response = http.get(path, parameters).path("response");
    var header = response.path("header");
    // 2024.12.19 공식 배포 가이드에는 reseultCode로 기재돼 있습니다.
    String code =
        header.has("resultCode")
            ? header.path("resultCode").asText()
            : header.path("reseultCode").asText();
    PlaceSourceHttpClient.checkCode(code);
    var body = response.path("body");
    if (!body.isObject()) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    return body;
  }
}
