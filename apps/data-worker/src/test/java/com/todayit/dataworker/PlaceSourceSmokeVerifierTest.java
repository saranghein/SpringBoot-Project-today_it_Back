package com.todayit.dataworker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.todayit.place.client.external.PlaceDataSource.Component;
import com.todayit.place.client.external.PlaceDataSource.SourceComponent;
import com.todayit.place.client.external.PlaceDataSource.SourceComponentRequest;
import com.todayit.place.client.external.PlaceDataSource.SourceKey;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 공급원 상세 검증의 필수값·ID 일치·원문 비노출을 검증합니다. */
class PlaceSourceSmokeVerifierTest {

  private final SourceKey tourKey = new SourceKey("TOUR_API", "kor-service2");

  /**
   * 공통 상세에 요청 ID와 일치하는 실제 항목 및 장소명이 필요한지 검증합니다.
   *
   * @param json 검증할 JSON 응답
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "[]",
        "[{}]",
        "[{\"contentid\":\"7\"}]",
        "[{\"contentid\":\"7\",\"title\":\" \"}]",
        "[{\"contentid\":\"8\",\"title\":\"장소\"}]"
      })
  void basicDetailMustContainActualMatchingItemAndName(String json) {
    assertThrows(AssertionError.class, () -> verify(tourKey, Component.BASIC, json));
  }

  /** 유효한 TourAPI 공통 상세가 검증을 통과하는지 확인합니다. */
  @Test
  void validTourDetailPasses() {
    assertDoesNotThrow(
        () -> verify(tourKey, Component.BASIC, "[{\"contentid\":\"7\",\"title\":\"장소\"}]"));
  }

  /** 이미지가 없는 정상 빈 목록을 허용하는지 검증합니다. */
  @Test
  void emptyPhotoListIsAllowed() {
    assertDoesNotThrow(() -> verify(tourKey, Component.IMAGES, "[]"));
  }

  /** 문화시설의 시설 식별자와 이름 필드로 상세를 검증하는지 확인합니다. */
  @Test
  void cultureUsesFacilityFields() {
    var key = new SourceKey("CULTURE", "culture-art-spaces");
    assertDoesNotThrow(
        () -> verify(key, Component.BASIC, "[{\"seq\":\"7\",\"culName\":\"문화시설\"}]"));
    assertThrows(AssertionError.class, () -> verify(key, Component.BASIC, "[{\"seq\":\"7\"}]"));
  }

  /** 잘못된 응답의 원문이 실패 메시지에 포함되지 않는지 검증합니다. */
  @Test
  void invalidPayloadDoesNotAppearInFailureMessage() {
    var failure =
        assertThrows(
            AssertionError.class,
            () -> verify(tourKey, Component.BASIC, "invalid fixture-private-payload"));
    assertThat(failure.getMessage()).doesNotContain("fixture-private-payload");
    assertThat(failure.getCause()).isNull();
  }

  /**
   * 같은 요청 ID와 고정 수집 시각을 사용해 응답 본문 검증에만 영향을 주는 fixture를 만듭니다.
   *
   * @param key 검증할 공급원·데이터셋
   * @param component 기본 상세 또는 사진 구성 요소
   * @param json 검증할 합성 응답 배열
   * @throws AssertionError 원본 구조·식별자·필수 필드가 검증 계약에 맞지 않을 때
   */
  private void verify(SourceKey key, Component component, String json) {
    var request = new SourceComponentRequest("7", component, Map.of());
    var response = new SourceComponent("7", component, json, Instant.EPOCH);
    PlaceSourceSmokeVerifier.verify(key, request, response);
  }
}
