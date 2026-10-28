package com.todayit.place.raw.service.command;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 한 번의 수집에서 유지할 법정동 지역 코드와 관광타입입니다.
 *
 * @param sidoCode 시도 코드 2자리
 * @param sigunguCode 선택 시군구 코드 3자리, 해당 시도 전체는 빈 문자열
 * @param contentTypeId 공급원 관광타입 코드 2자리
 */
public record TourApiCollectionScope(String sidoCode, String sigunguCode, String contentTypeId) {

  /**
   * 코드 형식을 검사합니다. 지역의 실제 존재와 관광타입 지원 여부는 공급원이 검증합니다.
   *
   * @param sidoCode 시도 코드
   * @param sigunguCode 선택 시군구 코드
   * @param contentTypeId 관광타입 코드
   * @throws IllegalArgumentException 코드 형식이 잘못됐을 때
   */
  public TourApiCollectionScope {
    if (sidoCode == null
        || !sidoCode.matches("[0-9]{2}")
        || sidoCode.equals("00")
        || sigunguCode == null
        || (!sigunguCode.isEmpty()
            && (!sigunguCode.matches("[0-9]{3}") || sigunguCode.equals("000")))
        || contentTypeId == null
        || !contentTypeId.matches("[0-9]{2}")
        || contentTypeId.equals("00")) {
      throw new IllegalArgumentException("시도 2자리·선택 시군구 3자리·관광타입 2자리 코드를 확인해야 합니다.");
    }
  }

  /**
   * 서울 전체의 관광지(contentTypeId=12) 수집 범위를 반환합니다.
   *
   * @return 시도 11·시군구 미지정·관광타입 12인 수집 범위
   */
  public static TourApiCollectionScope seoulAttractions() {
    return new TourApiCollectionScope("11", "", "12");
  }

  /**
   * 법정동 지역 코드와 관광타입의 목록 조회 조건을 생성합니다. 시군구를 지정하지 않으면 해당 필터를 생략합니다.
   *
   * @return lDongRegnCd·contentTypeId와 선택적 lDongSignguCd를 담은 불변 조회 조건
   */
  public Map<String, String> filters() {
    Map<String, String> filters = new LinkedHashMap<>();
    filters.put("lDongRegnCd", sidoCode);
    filters.put("contentTypeId", contentTypeId);
    if (!sigunguCode.isEmpty()) {
      filters.put("lDongSignguCd", sigunguCode);
    }
    return Map.copyOf(filters);
  }
}
