package com.todayit.place.client.external.support;

import com.todayit.place.client.external.PlaceDataSource.SourceItem;
import com.todayit.place.client.external.PlaceDataSource.SourcePage;
import com.todayit.place.client.external.PlaceDataSource.SourcePageRequest;
import com.todayit.place.client.external.PlaceSourceException;
import com.todayit.place.client.external.PlaceSourceException.Reason;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** 공급원 응답의 구조와 페이지 진행을 검증하는 내부 지원 코드입니다. */
public final class SourceResponses {

  private SourceResponses() {}

  /**
   * 버전이 명시된 페이지 cursor를 해석합니다.
   *
   * @param cursor 최초 빈값 또는 v1:페이지번호
   * @return 1부터 시작하는 페이지 번호
   * @throws PlaceSourceException cursor 형식·범위 오류 시
   */
  public static int pageNumber(String cursor) {
    if (cursor.isEmpty()) {
      return 1;
    }
    if (!cursor.matches("v1:[1-9][0-9]{0,8}")) {
      throw new PlaceSourceException(Reason.INVALID_REQUEST);
    }
    return Integer.parseInt(cursor.substring(3));
  }

  /**
   * 인증·페이지 파라미터가 조회 조건으로 덮어써지지 않도록 허용 목록을 확인합니다.
   *
   * @param filters 조회 조건
   * @param allowed 허용한 키
   * @throws PlaceSourceException 미지원 조건 또는 빈값일 때
   */
  public static void validateFilters(Map<String, String> filters, Set<String> allowed) {
    if (!allowed.containsAll(filters.keySet())
        || filters.values().stream().anyMatch(String::isBlank)) {
      throw new PlaceSourceException(Reason.INVALID_REQUEST);
    }
  }

  /**
   * 명시적인 전체 건수와 현재 페이지로 다음 cursor를 계산합니다.
   *
   * @param request 요청
   * @param rows 항목 배열·단일 객체·정상 빈값
   * @param idField 원천 식별 필드
   * @param total 전체 건수
   * @param actualPageSize 공급원 실제 페이지 크기
   * @param actualPage 응답 페이지 번호
   * @return 검증한 성공 페이지
   * @throws PlaceSourceException 식별자·페이지·건수 불일치 시
   */
  public static SourcePage page(
      SourcePageRequest request,
      JsonNode rows,
      String idField,
      JsonNode total,
      JsonNode actualPageSize,
      JsonNode actualPage) {
    long count = nonNegativeNumber(total);
    long size = nonNegativeNumber(actualPageSize);
    int page = pageNumber(request.cursor());
    if (size < 1 || size > 1000 || nonNegativeNumber(actualPage) != page) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    List<SourceItem> items = new ArrayList<>();
    for (JsonNode row : rows(rows)) {
      JsonNode id = row.path(idField);
      if (!id.isValueNode() || id.isNull() || id.asText().isBlank()) {
        throw new PlaceSourceException(Reason.INVALID_RESPONSE);
      }
      items.add(new SourceItem(id.asText(), row.toString()));
    }
    long offset = (page - 1L) * size;
    // 마지막 페이지도 전체 건수와 다르면 정상 종료하지 않습니다.
    if (items.size() != Math.min(size, Math.max(0, count - offset))) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    String next = offset + size < count ? "v1:" + (page + 1) : "";
    return new SourcePage(items, next, count, Instant.now());
  }

  /**
   * JSON 배열과 XML 단일 item 표현을 같은 목록으로 읽습니다.
   *
   * @param value 원본 항목
   * @return 원본 노드 목록
   * @throws PlaceSourceException 지원하지 않는 응답 모양일 때
   */
  public static List<JsonNode> rows(JsonNode value) {
    if (value.isMissingNode()
        || value.isNull()
        || (value.isTextual() && value.asText().isBlank())) {
      return List.of();
    }
    if (value.isObject()) {
      return List.of(value);
    }
    if (!value.isArray()) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    List<JsonNode> result = new ArrayList<>();
    value.forEach(result::add);
    if (result.stream().anyMatch(node -> !node.isObject())) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
    return List.copyOf(result);
  }

  /**
   * 누락과 형식 오류를 0으로 해석하지 않고 숫자를 읽습니다.
   *
   * @param node 응답 숫자
   * @return 0 이상의 정수
   * @throws PlaceSourceException 누락·음수·비정수·범위 초과 시
   */
  public static long nonNegativeNumber(JsonNode node) {
    try {
      String text = node.asText();
      if (!text.matches("[0-9]{1,18}")) {
        throw new PlaceSourceException(Reason.INVALID_RESPONSE);
      }
      return Long.parseLong(text);
    } catch (RuntimeException exception) {
      throw new PlaceSourceException(Reason.INVALID_RESPONSE);
    }
  }
}
