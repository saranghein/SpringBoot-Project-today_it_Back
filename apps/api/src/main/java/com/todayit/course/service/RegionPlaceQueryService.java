package com.todayit.course.service;

import com.todayit.course.exception.InvalidRegionPathException;
import java.util.List;

/** 장소 필터용 지역 코드를 검증하고 조회하는 서비스 계약입니다. */
public interface RegionPlaceQueryService {

  /**
   * 전달한 지역 코드가 모두 일치하는 지역 식별자를 조회합니다.
   *
   * @param sidoCode 시·도 코드 2자리, 생략 가능
   * @param sigunguCode 시·군·구 코드 3자리, 생략 가능
   * @return 조건에 해당하는 지역 식별자 목록
   * @throws InvalidRegionPathException 코드 형식이 잘못되었거나 해당 지역이 없을 때
   */
  List<Integer> findRegionIds(String sidoCode, String sigunguCode);
}
