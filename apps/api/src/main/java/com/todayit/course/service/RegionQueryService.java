package com.todayit.course.service;

import com.todayit.course.exception.InvalidRegionPathException;
import com.todayit.course.repository.RegionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 행정 지역 코드를 검증하고 조회하는 Query Service입니다. */
@Service
public class RegionQueryService implements RegionPlaceQueryService {

  private final RegionRepository regionRepository;

  /**
   * 지역 조회에 필요한 Repository를 받습니다.
   *
   * @param regionRepository 지역 Repository
   */
  public RegionQueryService(RegionRepository regionRepository) {
    this.regionRepository = regionRepository;
  }

  /**
   * 전달한 지역 코드가 모두 일치하는 지역 식별자를 조회합니다.
   *
   * @param sidoCode 시·도 코드 2자리, 생략 가능
   * @param sigunguCode 시·군·구 코드 3자리, 생략 가능
   * @return 조건에 해당하는 지역 식별자 목록
   * @throws InvalidRegionPathException 코드 형식이 잘못되었거나 해당 지역이 없을 때
   */
  @Transactional(readOnly = true)
  @Override
  public List<Integer> findRegionIds(String sidoCode, String sigunguCode) {
    if ((sidoCode != null && !sidoCode.matches("[0-9]{2}"))
        || (sigunguCode != null && !sigunguCode.matches("[0-9]{3}"))) {
      throw new InvalidRegionPathException();
    }
    List<Integer> regionIds = regionRepository.findIdsByCodes(sidoCode, sigunguCode);
    if (regionIds.isEmpty()) {
      throw new InvalidRegionPathException();
    }
    return List.copyOf(regionIds);
  }
}
