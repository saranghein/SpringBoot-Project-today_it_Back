package com.todayit.place.ingestion.repository;

import com.todayit.place.service.model.NormalizedPlace;

/** place_source 연결과 서비스 장소를 같은 트랜잭션에서 저장하는 계약입니다. */
@FunctionalInterface
public interface PlaceSourceRepository {

  /**
   * 공급원·원천 ID로 같은 장소를 찾아 원천 소유 필드와 지역 연결을 한 트랜잭션에서 반영합니다.
   *
   * <p>구현은 원천 연결의 UNIQUE를 보장하고 좋아요·스크랩·조회수·운영 상태를 보존해야 합니다. 출처·권리 확인 없는 이미지 공개와 다른 원천·운영자가 등록한 이미지
   * 일괄 삭제는 금지합니다. 구현 완료 전 이 계약을 운영 저장으로 사용하지 않습니다.
   *
   * @param provider 공급원 코드
   * @param sourceId 공급원 장소 ID
   * @param regionId 검증된 지역 ID
   * @param place 정제한 장소
   * @return 생성 또는 갱신한 같은 서비스 장소 ID
   */
  int upsert(String provider, String sourceId, int regionId, NormalizedPlace place);
}
