package com.todayit.place.ingestion.service;

import java.util.Optional;

/** 시도·시군구 코드에서 서비스 지역 식별자를 찾는 조회 계약입니다. */
@FunctionalInterface
public interface PlaceRegionQueryService {

  /**
   * 시도·시군구가 모두 일치하는 지역 하나를 조회합니다. 지역을 자동 생성하거나 상위 지역으로 대체하지 않습니다.
   *
   * @param sidoCode 법정동 시도 코드 2자리
   * @param sigunguCode 법정동 시군구 코드 3자리
   * @return 지역 식별자, 미등록이면 빈 값. 중복 매칭은 빈 값으로 숨기지 않고 구현에서 오류 처리
   */
  Optional<Integer> findRegionId(String sidoCode, String sigunguCode);
}
