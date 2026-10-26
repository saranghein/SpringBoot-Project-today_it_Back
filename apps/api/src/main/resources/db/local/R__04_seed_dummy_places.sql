INSERT INTO place (
    name,
    latitude,
    longitude,
    address,
    category,
    view_count,
    is_active,
    is_deleted
)
SELECT
    seed.name,
    seed.latitude,
    seed.longitude,
    seed.address,
    seed.category,
    seed.view_count,
    seed.is_active,
    seed.is_deleted
FROM (
    VALUES
        ('오늘의 식당', 37.57000000, 126.98500000, '서울특별시 종로구 종로 1', 'RESTAURANT', 120, TRUE, FALSE),
        ('종로 카페거리', 37.57200000, 126.98300000, '서울특별시 종로구 인사동길 10', 'CAFE_DESSERT', 95, TRUE, FALSE),
        ('달빛 와인바', 37.56900000, 126.99100000, '서울특별시 종로구 삼일대로 20', 'RESTAURANT', 80, TRUE, FALSE),
        ('서울 전시 공간', 37.57700000, 126.98100000, '서울특별시 종로구 율곡로 30', 'EXHIBITION_MUSEUM', 75, TRUE, FALSE),
        ('종로 공연장', 37.57000000, 126.97800000, '서울특별시 종로구 새문안로 40', 'PERFORMANCE', 65, TRUE, FALSE),
        ('도예 원데이 클래스', 37.57900000, 126.98900000, '서울특별시 종로구 창덕궁길 50', 'EXPERIENCE', 55, TRUE, FALSE),
        ('도심 스포츠 센터', 37.56800000, 126.98200000, '서울특별시 종로구 종로 60', 'PLAY_LEISURE', 45, TRUE, FALSE),
        ('북악산 산책로', 37.59200000, 126.98100000, '서울특별시 종로구 북악산로 70', 'NATURE_WALK', 40, TRUE, FALSE),
        ('인사동 소품 상점', 37.57400000, 126.98500000, '서울특별시 종로구 인사동길 80', 'SHOPPING', 35, TRUE, FALSE),
        ('남산 전망대', 37.55100000, 126.98800000, '서울특별시 중구 남산공원길 90', 'LANDMARK_VIEW', 150, TRUE, FALSE),
        ('도심 미술관', 37.56300000, 126.98900000, '서울특별시 중구 을지로 100', 'EXHIBITION_MUSEUM', 30, TRUE, FALSE),
        ('운영 중단 장소', 37.56500000, 126.98000000, '서울특별시 종로구 세종대로 110', 'RESTAURANT', 10, FALSE, FALSE),
        ('삭제된 장소', 37.56600000, 126.98100000, '서울특별시 종로구 광화문로 120', 'CAFE_DESSERT', 5, TRUE, TRUE)
) AS seed(name, latitude, longitude, address, category, view_count, is_active, is_deleted)
WHERE NOT EXISTS (
    SELECT 1
    FROM place
    WHERE place.name = seed.name
);

INSERT INTO place_image (
    place_id,
    image_url
)
SELECT
    place.place_id,
    seed.image_url
FROM (
    VALUES
        ('오늘의 식당', 'https://placehold.co/1200x800?text=Restaurant'),
        ('종로 카페거리', 'https://placehold.co/1200x800?text=Cafe'),
        ('달빛 와인바', 'https://placehold.co/1200x800?text=Wine+Bar'),
        ('서울 전시 공간', 'https://placehold.co/1200x800?text=Exhibition'),
        ('종로 공연장', 'https://placehold.co/1200x800?text=Theater'),
        ('도예 원데이 클래스', 'https://placehold.co/1200x800?text=Workshop'),
        ('도심 스포츠 센터', 'https://placehold.co/1200x800?text=Sports'),
        ('북악산 산책로', 'https://placehold.co/1200x800?text=Nature'),
        ('인사동 소품 상점', 'https://placehold.co/1200x800?text=Shopping'),
        ('남산 전망대', 'https://placehold.co/1200x800?text=Landmark'),
        ('도심 미술관', 'https://placehold.co/1200x800?text=Museum'),
        ('운영 중단 장소', 'https://placehold.co/1200x800?text=Inactive'),
        ('삭제된 장소', 'https://placehold.co/1200x800?text=Deleted')
) AS seed(place_name, image_url)
JOIN place ON place.name = seed.place_name
WHERE NOT EXISTS (
    SELECT 1
    FROM place_image
    WHERE place_image.place_id = place.place_id
      AND place_image.image_url = seed.image_url
);

INSERT INTO region_place (
    region_id,
    place_id
)
SELECT
    region.region_id,
    place.place_id
FROM (
    VALUES
        ('오늘의 식당', '110'),
        ('종로 카페거리', '110'),
        ('달빛 와인바', '110'),
        ('서울 전시 공간', '110'),
        ('종로 공연장', '110'),
        ('도예 원데이 클래스', '110'),
        ('도심 스포츠 센터', '110'),
        ('북악산 산책로', '110'),
        ('인사동 소품 상점', '110'),
        ('남산 전망대', '140'),
        ('도심 미술관', '140'),
        ('운영 중단 장소', '110'),
        ('삭제된 장소', '110')
) AS seed(place_name, sigungu_code)
JOIN place ON place.name = seed.place_name
JOIN region
  ON region.sido_code = '11'
 AND region.sigungu_code = seed.sigungu_code
WHERE NOT EXISTS (
    SELECT 1
    FROM region_place
    WHERE region_place.region_id = region.region_id
      AND region_place.place_id = place.place_id
);

INSERT INTO place (
    name,
    latitude,
    longitude,
    address,
    category,
    view_count,
    is_active,
    is_deleted
)
SELECT
    seed.region_name || ' 대표 장소',
    36.00000000 + MOD(seed.region_id, 200) * 0.01000000,
    126.00000000 + MOD(seed.region_id, 300) * 0.01000000,
    seed.region_name || ' 중앙로 1',
    CASE MOD(seed.region_id, 9)
        WHEN 0 THEN 'RESTAURANT'
        WHEN 1 THEN 'CAFE_DESSERT'
        WHEN 2 THEN 'EXHIBITION_MUSEUM'
        WHEN 3 THEN 'PERFORMANCE'
        WHEN 4 THEN 'EXPERIENCE'
        WHEN 5 THEN 'PLAY_LEISURE'
        WHEN 6 THEN 'NATURE_WALK'
        WHEN 7 THEN 'SHOPPING'
        ELSE 'LANDMARK_VIEW'
    END,
    MOD(seed.region_id * 7, 200),
    TRUE,
    FALSE
FROM (
    SELECT region_id, sido_name || ' ' || sigungu_name AS region_name
    FROM region
) AS seed
WHERE NOT EXISTS (
    SELECT 1
    FROM place
    WHERE place.name = seed.region_name || ' 대표 장소'
);

INSERT INTO place_image (
    place_id,
    image_url
)
SELECT
    place.place_id,
    'https://placehold.co/1200x800?text=TodayIt+Place'
FROM place
WHERE place.name LIKE '% 대표 장소'
  AND NOT EXISTS (
      SELECT 1
      FROM place_image
      WHERE place_image.place_id = place.place_id
        AND place_image.image_url = 'https://placehold.co/1200x800?text=TodayIt+Place'
  );

INSERT INTO region_place (
    region_id,
    place_id
)
SELECT
    region.region_id,
    place.place_id
FROM region
JOIN place ON place.name = region.sido_name || ' ' || region.sigungu_name || ' 대표 장소'
WHERE NOT EXISTS (
    SELECT 1
    FROM region_place
    WHERE region_place.region_id = region.region_id
      AND region_place.place_id = place.place_id
);
