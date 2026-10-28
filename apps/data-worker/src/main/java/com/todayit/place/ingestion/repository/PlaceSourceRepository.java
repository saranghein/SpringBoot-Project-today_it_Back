package com.todayit.place.ingestion.repository;

import com.todayit.place.service.model.NormalizedPlace;

/** 원천 연결·서비스 장소·지역 연결·이미지 URL을 같은 트랜잭션에서 저장하는 계약입니다. */
@FunctionalInterface
public interface PlaceSourceRepository {

  /**
   * 공급원·원천 ID로 같은 장소를 찾아 기본정보·지역 연결·누락된 이미지 URL을 한 트랜잭션에서 반영합니다.
   *
   * <p>원천 연결의 UNIQUE를 보장하고 좋아요·스크랩·조회수·운영 상태를 보존합니다. 이미지는 같은 장소·URL을 중복 추가하지 않으며 기존 이미지와 메타데이터를
   * 변경·삭제하지 않습니다. 이미지 파일을 다운로드하거나 권리 코드로 공개 이용 허가를 추정하지 않습니다.
   *
   * @param provider 공급원 코드
   * @param sourceId 공급원 장소 ID
   * @param regionId 검증된 지역 ID
   * @param place 정제한 장소
   * @return 생성 또는 갱신한 같은 서비스 장소 ID
   */
  int upsert(String provider, String sourceId, int regionId, NormalizedPlace place);
}
