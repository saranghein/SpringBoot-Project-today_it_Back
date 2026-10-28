package com.todayit.place.raw.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.todayit.place.raw.service.command.TourApiCollectionScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 수집 지역의 기본값·법정동 코드·실제 HTTP 필터 구성을 검증합니다. */
class TourApiCollectionScopeTest {

  /** 기본 범위가 서울 전체 관광지이며 시군구 필터를 포함하지 않는지 확인합니다. */
  @Test
  void defaultsKeepSeoulAttractionsWithoutSigunguFilter() {
    var filters = TourApiCollectionScope.seoulAttractions().filters();
    assertThat(filters)
        .hasSize(2)
        .containsEntry("lDongRegnCd", "11")
        .containsEntry("contentTypeId", "12");
    assertThat(filters).doesNotContainKeys("lDongSignguCd", "areaCode", "sigunguCode");
  }

  /** 선택한 시군구와 타입을 원문 그대로 전달하고 필터의 후속 변경을 막는지 확인합니다. */
  @Test
  void buildsExactLegalDistrictAndTypeFilters() {
    var filters = new TourApiCollectionScope("26", "110", "39").filters();
    assertThat(filters)
        .hasSize(3)
        .containsEntry("lDongRegnCd", "26")
        .containsEntry("lDongSignguCd", "110")
        .containsEntry("contentTypeId", "39");
    assertThatThrownBy(() -> filters.put("serviceKey", "invalid"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /**
   * 빈 시도·잘못된 코드가 묵시적 전국 수집으로 확대되지 않도록 거부하는지 확인합니다.
   *
   * @param sido 시도 코드
   * @param sigungu 시군구 코드
   * @param type 관광타입 코드
   */
  @ParameterizedTest
  @CsvSource(
      value = {
        "NULL,'',12",
        "'','',12",
        "1,'',12",
        "00,'',12",
        "AA,'',12",
        "11,NULL,12",
        "11,11,12",
        "11,000,12",
        "11,1A0,12",
        "11,'',NULL",
        "11,'',''",
        "11,'',00",
        "11,'',1",
        "11,'',AB"
      },
      nullValues = "NULL")
  void rejectsInvalidCodes(String sido, String sigungu, String type) {
    assertThatThrownBy(() -> new TourApiCollectionScope(sido, sigungu, type))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
