package com.todayit.place.client.external.redtable;

import com.todayit.place.client.external.PlaceDataSource;
import com.todayit.place.client.external.PlaceSourceException;
import com.todayit.place.client.external.PlaceSourceException.Reason;
import com.todayit.place.client.external.support.PlaceSourceCallLogger;
import com.todayit.place.client.external.support.PlaceSourceCallLogger.Operation;
import com.todayit.place.client.external.support.PlaceSourceHttpClient;
import com.todayit.place.client.external.support.SourceResponses;
import java.util.Map;
import java.util.Set;

/** 서울 레드테이블 상품의 기본·운영·사진 페이지를 RSTR_ID를 보존해 조회합니다. */
public final class RedtablePlaceDataSource implements PlaceDataSource {

  private final PlaceSourceHttpClient http;

  /**
   * 서울 상품 HTTP 설정을 받습니다.
   *
   * @param http 공급원 HTTP Client
   */
  public RedtablePlaceDataSource(PlaceSourceHttpClient http) {
    this.http = http;
  }

  @Override
  public SourceKey sourceKey() {
    return new SourceKey("REDTABLE", "seoul-restaurants");
  }

  @Override
  public SourceCapabilities capabilities() {
    return new SourceCapabilities(Set.of(Component.values()), Set.of(), false);
  }

  @Override
  public SourcePage fetchPage(SourcePageRequest request) {
    return PlaceSourceCallLogger.call(
        sourceKey(), request.component(), Operation.PAGE, () -> fetchPageInternal(request));
  }

  /**
   * 기본·운영·이미지 데이터를 각각 페이지로 조회합니다. 요청 크기·ID 필터는 공급원에 보내지 않고 응답이 알려주는 페이지 크기로 다음 cursor를 계산합니다.
   *
   * @param request 별도 필터가 없는 페이지 요청
   * @return RSTR_ID를 보존한 원본 목록; 사진은 같은 ID가 반복될 수 있음
   * @throws PlaceSourceException 미지원 조건·외부 호출·업무 코드 또는 목록 구조 검증 실패 시
   */
  private SourcePage fetchPageInternal(SourcePageRequest request) {
    SourceResponses.validateFilters(request.filters(), Set.of());
    // 공식 요청 명세에는 pageNo만 있으며 numOfRows와 ID 필터는 없습니다.
    String path =
        switch (request.component()) {
          case BASIC -> "/api/rstr";
          case INTRODUCTION -> "/api/rstr/oprt";
          case IMAGES -> "/api/rstr/img";
        };
    var response =
        http.get(
            path, Map.of("pageNo", Integer.toString(SourceResponses.pageNumber(request.cursor()))));
    var header = response.path("header");
    PlaceSourceHttpClient.checkCode(header.path("resultCode").asText());
    var body = response.path("body");
    if (!body.isArray()) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    return SourceResponses.page(
        request,
        body,
        "RSTR_ID",
        header.path("totalCount"),
        header.path("numOfRows"),
        header.path("pageNo"));
  }

  @Override
  public SourceComponent fetchComponent(SourceComponentRequest request) {
    return PlaceSourceCallLogger.call(
        sourceKey(),
        request.component(),
        Operation.COMPONENT,
        () -> {
          throw new PlaceSourceException(Reason.UNSUPPORTED);
        });
  }
}
