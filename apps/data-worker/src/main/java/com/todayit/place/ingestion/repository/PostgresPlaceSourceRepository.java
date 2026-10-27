package com.todayit.place.ingestion.repository;

import com.todayit.place.service.model.NormalizedPlace;
import com.todayit.place.service.model.NormalizedPlace.Image;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 원천 연결·장소 기본값·지역 연결·이미지 URL을 호출자의 PostgreSQL 트랜잭션에서 저장합니다. */
@Repository
@ConditionalOnProperty(name = "todayit.place.upsert.enabled", havingValue = "true")
public class PostgresPlaceSourceRepository implements PlaceSourceRepository {

  private static final Logger LOG = LoggerFactory.getLogger(PostgresPlaceSourceRepository.class);
  private final EntityManager entityManager;

  /**
   * 현재 트랜잭션에 참여하는 EntityManager를 주입합니다.
   *
   * @param entityManager worker의 JPA 저장 세션
   */
  public PostgresPlaceSourceRepository(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /**
   * 같은 원천의 동시 쓰기를 직렬화하고 장소·지역·새 이미지 URL을 반영합니다. 기존 이미지 행은 유지합니다.
   *
   * @param provider 공급원 코드
   * @param sourceId 공급원 장소 ID
   * @param regionId 조회 계약에서 찾은 지역 ID
   * @param place 정제한 장소 값
   * @return 생성·갱신한 서비스 장소 ID
   * @throws IllegalArgumentException 공급원·원천 ID가 없거나 지역 ID가 양수가 아닐 때
   * @throws IllegalStateException 연결된 서비스 장소가 존재하지 않을 때
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int upsert(String provider, String sourceId, int regionId, NormalizedPlace place) {
    if (provider == null
        || provider.isBlank()
        || sourceId == null
        || sourceId.isBlank()
        || regionId <= 0) {
      throw new IllegalArgumentException("원천 식별자와 지역 ID가 필요합니다.");
    }
    lockSource(provider, sourceId);
    List<?> linkedIds =
        entityManager
            .createNativeQuery(
                """
        SELECT place_id FROM place_source
        WHERE provider = :provider AND source_id = :sourceId
        FOR UPDATE
        """)
            .setParameter("provider", provider)
            .setParameter("sourceId", sourceId)
            .getResultList();
    int placeId;
    if (linkedIds.isEmpty()) {
      placeId = insertPlace(place);
      entityManager
          .createNativeQuery(
              """
          INSERT INTO place_source (place_id, provider, source_id)
          VALUES (:placeId, :provider, :sourceId)
          """)
          .setParameter("placeId", placeId)
          .setParameter("provider", provider)
          .setParameter("sourceId", sourceId)
          .executeUpdate();
    } else {
      placeId = ((Number) linkedIds.getFirst()).intValue();
      updatePlace(placeId, place);
    }
    // 장소 행의 쓰기 잠금을 잡은 상태이므로 다른 원천이 같은 장소를 참조해도 중복 지역을 삽입하지 않습니다.
    entityManager
        .createNativeQuery(
            """
        INSERT INTO region_place (place_id, region_id)
        SELECT :placeId, :regionId
        WHERE NOT EXISTS (
            SELECT 1 FROM region_place WHERE place_id = :placeId AND region_id = :regionId
        )
        """)
        .setParameter("placeId", placeId)
        .setParameter("regionId", regionId)
        .executeUpdate();
    insertMissingImages(placeId, place.images());
    return placeId;
  }

  /**
   * 장소 쓰기 잠금을 보유한 상태에서 동일 장소·URL이 없을 때만 원본 URL을 추가합니다. 이미지 파일은 요청하지 않으며 기존 URL·식별자·시각·다른 출처의 이미지는
   * 변경하지 않습니다.
   *
   * @param placeId 저장한 서비스 장소 ID
   * @param images 정제된 이미지 목록, 빈 목록 허용
   */
  private void insertMissingImages(int placeId, List<Image> images) {
    int added = 0;
    for (Image image : images) {
      added +=
          entityManager
              .createNativeQuery(
                  """
          INSERT INTO place_image (place_id, image_url)
          SELECT :placeId, :imageUrl
          WHERE NOT EXISTS (
              SELECT 1 FROM place_image WHERE place_id = :placeId AND image_url = :imageUrl
          )
          """)
              .setParameter("placeId", placeId)
              .setParameter("imageUrl", image.url())
              .executeUpdate();
    }
    LOG.debug(
        "PLACE_IMAGES_STAGED 장소 이미지 저장 처리 장소번호={} 후보={} 추가={} 커밋대기=true",
        placeId,
        images.size(),
        added);
  }

  /**
   * 아직 연결 행이 없는 첫 저장도 직렬화합니다. 해시 충돌은 불필요한 대기를 만들 뿐 다른 원천을 병합하지 않습니다.
   *
   * @param provider 공급원 코드
   * @param sourceId 원천 ID
   */
  private void lockSource(String provider, String sourceId) {
    String identity = provider.length() + ":" + provider + sourceId;
    entityManager
        .createNativeQuery("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:identity, 0))")
        .setParameter("identity", identity)
        .getSingleResult();
  }

  /**
   * 자동 검증과 지역 조회를 통과한 신규 장소를 활성 생성합니다. 조회수·생성 시각은 DB 기본값을 사용합니다.
   *
   * @param place 정제한 장소
   * @return 신규 장소 ID
   */
  private int insertPlace(NormalizedPlace place) {
    return ((Number)
            bindPlace(
                    entityManager.createNativeQuery(
                        """
        INSERT INTO place (name, latitude, longitude, address, category, description, is_active, is_deleted)
        VALUES (:name, :latitude, :longitude, :address, :category, :description, true, false)
        RETURNING place_id
        """),
                    place)
                .getSingleResult())
        .intValue();
  }

  /**
   * 원천 기본정보만 갱신합니다. 소개 누락·조회수·활성/삭제 상태·생성 시각과 사용자 관계를 보존합니다.
   *
   * @param placeId 연결된 서비스 장소 ID
   * @param place 새 정제 값
   * @throws IllegalStateException 연결된 장소가 없을 때
   */
  private void updatePlace(int placeId, NormalizedPlace place) {
    int changed =
        bindPlace(
                entityManager.createNativeQuery(
                    """
        UPDATE place
        SET name = :name, latitude = :latitude, longitude = :longitude,
            address = :address, category = :category,
            description = COALESCE(:description, description), updated_at = CURRENT_TIMESTAMP
        WHERE place_id = :placeId
        """),
                place)
            .setParameter("placeId", placeId)
            .executeUpdate();
    if (changed != 1) {
      throw new IllegalStateException("연결된 서비스 장소가 없습니다.");
    }
  }

  /**
   * 생성·갱신이 같은 원천 기본정보를 반영하도록 공통 매개변수를 바인딩합니다.
   *
   * @param query 생성 또는 갱신 SQL
   * @param place 정제된 장소 기본정보
   * @return 공통 매개변수를 바인딩한 SQL
   */
  private Query bindPlace(Query query, NormalizedPlace place) {
    return query
        .setParameter("name", place.name())
        .setParameter("latitude", place.latitude())
        .setParameter("longitude", place.longitude())
        .setParameter("address", place.address())
        .setParameter("category", place.category().name())
        .setParameter("description", place.description());
  }
}
