package com.todayit.dataworker;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.todayit.place.client.external.PlaceDataSource.Component;
import com.todayit.place.client.external.PlaceDataSource.SourceComponent;
import com.todayit.place.client.external.PlaceDataSource.SourceComponentRequest;
import com.todayit.place.client.external.PlaceDataSource.SourceKey;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 조회 결과를 검사하되 실패 메시지에 원본 응답을 포함하지 않습니다. */
final class PlaceSourceSmokeVerifier {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  /** 실제 응답과 합성 응답이 동일한 정적 검증 경로를 사용하도록 인스턴스 생성을 막습니다. */
  private PlaceSourceSmokeVerifier() {}

  /**
   * 기본 상세의 실제 항목·장소명과 모든 상세 항목의 ID를 검사하되 사진의 빈 배열은 허용합니다. 실패 메시지에는 공급원만 남기고 파싱 실패의 원문과 cause는 전달하지
   * 않습니다.
   *
   * @param key 응답 필드 계약을 선택할 공급원·데이터셋
   * @param request 기대한 원천 ID와 구성 요소
   * @param detail 검증할 상세 원본
   * @throws AssertionError JSON 구조·ID·구성 요소·필수 장소명이 계약에 맞지 않을 때
   */
  static void verify(SourceKey key, SourceComponentRequest request, SourceComponent detail) {
    String provider = key.provider();
    assertTrue(request.sourceId().equals(detail.sourceId()), "상세 원천 ID 불일치: " + provider);
    assertTrue(request.component() == detail.component(), "상세 구성 요소 불일치: " + provider);
    JsonNode items;
    try {
      items = MAPPER.readTree(detail.payloadJson());
    } catch (JacksonException exception) {
      fail("상세 JSON 형식 오류: " + provider);
      return;
    }
    assertTrue(items != null && items.isArray(), "상세 응답은 배열이어야 합니다: " + provider);
    if (request.component() == Component.BASIC) {
      assertTrue(items.size() == 1, "기본 상세 항목이 없거나 여러 건입니다: " + provider);
    }
    String idField;
    String nameField;
    switch (provider) {
      case "TOUR_API" -> {
        idField = "contentid";
        nameField = "title";
      }
      case "CULTURE" -> {
        idField = "seq";
        nameField = "culName";
      }
      default -> {
        fail("상세 검증 계약이 없는 공급원입니다.");
        return;
      }
    }
    for (JsonNode item : items) {
      assertTrue(item.isObject(), "상세 항목 형식 오류: " + provider);
      assertTrue(
          request.sourceId().equals(item.path(idField).asText()), "상세 본문 원천 ID 불일치: " + provider);
      if (request.component() == Component.BASIC) {
        JsonNode name = item.path(nameField);
        assertTrue(name.isTextual() && !name.asText().isBlank(), "상세 장소명 누락: " + provider);
      }
    }
  }
}
