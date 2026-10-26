package com.todayit.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.todayit.common.pagination.PageResult;
import com.todayit.course.service.RegionPlaceQueryService;
import com.todayit.place.entity.Category;
import com.todayit.place.entity.Place;
import com.todayit.place.repository.PlaceImageRepository;
import com.todayit.place.repository.PlaceMemberLikeRepository;
import com.todayit.place.repository.PlaceRepository;
import com.todayit.place.repository.PlaceScrapRepository;
import com.todayit.place.service.model.PlaceLocationResult;
import com.todayit.place.service.model.PlaceResult;
import com.todayit.place.service.model.PlaceScrapSort;
import com.todayit.place.service.model.PlaceSort;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@ExtendWith(MockitoExtension.class)
class PlaceQueryServiceTest {

  private static final Logger LOG = LoggerFactory.getLogger(PlaceQueryServiceTest.class);

  @BeforeEach
  void logTestStart(TestInfo testInfo) {
    LOG.info("테스트 시작: {}", testInfo.getDisplayName());
  }

  @AfterEach
  void logTestEnd(TestInfo testInfo) {
    LOG.info("테스트 종료: {}", testInfo.getDisplayName());
  }

  @Mock private PlaceMemberLikeRepository placeMemberLikeRepository;
  @Mock private PlaceRepository placeRepository;

  @Mock private PlaceImageRepository placeImageRepository;

  @Mock private PlaceScrapRepository placeScrapRepository;

  @Mock private RegionPlaceQueryService regionPlaceQueryService;

  @Mock private Place place;

  @Test
  @DisplayName("회원의 좋아요 여부를 페이지 내 장소 기준으로 일괄 조회한다")
  void returnsLikesForAuthenticatedMemberInOneQuery() {
    Place otherPlace = org.mockito.Mockito.mock(Place.class);
    when(place.getPlaceId()).thenReturn(1);
    when(otherPlace.getPlaceId()).thenReturn(2);
    when(place.getSnapshot(anyList()))
        .thenReturn(
            new Place.PlaceSnapshot(
                1,
                "Liked",
                BigDecimal.ONE,
                BigDecimal.ONE,
                "Address",
                Category.RESTAURANT,
                0,
                List.of()));
    when(otherPlace.getSnapshot(anyList()))
        .thenReturn(
            new Place.PlaceSnapshot(
                2,
                "Unliked",
                BigDecimal.ONE,
                BigDecimal.ONE,
                "Address",
                Category.RESTAURANT,
                0,
                List.of()));
    PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
    when(placeRepository.findPlaces(List.of(0), false, List.of(Category.values()), false, pageable))
        .thenReturn(new PageImpl<>(List.of(place, otherPlace), pageable, 2));
    when(placeMemberLikeRepository.findLikedPlaceIds("member-1", List.of(1, 2)))
        .thenReturn(java.util.Set.of(1));
    PlaceQueryService service =
        new PlaceQueryService(
            placeMemberLikeRepository,
            placeRepository,
            placeImageRepository,
            placeScrapRepository,
            regionPlaceQueryService);

    PageResult<PlaceResult> result =
        service.findPlaces(null, null, null, 0, 20, PlaceSort.LATEST, "member-1");

    assertThat(result.content()).extracting(PlaceResult::liked).containsExactly(true, false);
    verify(placeMemberLikeRepository).findLikedPlaceIds("member-1", List.of(1, 2));
  }

  @Test
  @DisplayName("인증된 회원의 빈 장소 목록은 좋아요를 조회하지 않는다")
  void skipsLikesForEmptyAuthenticatedPage() {
    PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
    when(placeRepository.findPlaces(List.of(0), false, List.of(Category.values()), false, pageable))
        .thenReturn(Page.empty(pageable));
    PlaceQueryService service =
        new PlaceQueryService(
            placeMemberLikeRepository,
            placeRepository,
            placeImageRepository,
            placeScrapRepository,
            regionPlaceQueryService);

    assertThat(service.findPlaces(null, null, null, 0, 20, PlaceSort.LATEST, "member-1").content())
        .isEmpty();
    org.mockito.Mockito.verifyNoInteractions(placeMemberLikeRepository, regionPlaceQueryService);
  }

  @Test
  @DisplayName("장소 목록을 인기순으로 조회하고 페이지 정보와 함께 반환한다")
  void findsPlacesByPopularity() {
    // Given
    when(place.getPlaceId()).thenReturn(1);
    Place.PlaceSnapshot snapshot =
        new Place.PlaceSnapshot(
            1,
            "오늘의 식당",
            new BigDecimal("37.5"),
            new BigDecimal("127.0"),
            "서울특별시 종로구 종로 1",
            Category.RESTAURANT,
            15,
            List.of("https://placehold.co/1200x800?text=Restaurant"));
    when(place.getSnapshot(anyList())).thenReturn(snapshot);
    PageRequest pageable = PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "viewCount"));
    when(placeRepository.findPlaces(List.of(0), false, List.of(Category.values()), false, pageable))
        .thenReturn(new PageImpl<>(List.of(place), PageRequest.of(1, 2), 3));
    PlaceQueryService placeQueryService =
        new PlaceQueryService(
            placeMemberLikeRepository,
            placeRepository,
            placeImageRepository,
            placeScrapRepository,
            regionPlaceQueryService);

    // When
    PageResult<PlaceResult> result =
        placeQueryService.findPlaces(null, null, List.of(), 1, 2, PlaceSort.POPULAR, null);

    // Then
    assertThat(result.content())
        .containsExactly(
            new PlaceResult(
                1,
                "오늘의 식당",
                new BigDecimal("37.5"),
                new BigDecimal("127.0"),
                "서울특별시 종로구 종로 1",
                Category.RESTAURANT,
                15,
                List.of("https://placehold.co/1200x800?text=Restaurant"),
                false));
    org.mockito.Mockito.verifyNoInteractions(placeMemberLikeRepository, regionPlaceQueryService);
    assertThat(result.page()).isEqualTo(1);
    assertThat(result.size()).isEqualTo(2);
    assertThat(result.totalElements()).isEqualTo(3);
    verify(placeRepository)
        .findPlaces(
            List.of(0),
            false,
            List.of(Category.values()),
            false,
            PageRequest.of(1, 2, Sort.by(Sort.Direction.DESC, "viewCount")));
    verify(placeImageRepository).findByPlacePlaceIdInOrderByPlacePlaceIdAscCreatedAtAsc(List.of(1));
  }

  @Test
  @DisplayName("지역과 카테고리 필터를 조합해 장소를 조회한다")
  void findsPlacesWithFilters() {
    // Given
    PageRequest pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
    when(regionPlaceQueryService.findRegionIds("11", "230")).thenReturn(List.of(10));
    when(placeRepository.findPlaces(
            List.of(10), true, List.of(Category.RESTAURANT, Category.CAFE_DESSERT), true, pageable))
        .thenReturn(Page.empty(pageable));
    PlaceQueryService placeQueryService =
        new PlaceQueryService(
            placeMemberLikeRepository,
            placeRepository,
            placeImageRepository,
            placeScrapRepository,
            regionPlaceQueryService);

    // When
    PageResult<PlaceResult> result =
        placeQueryService.findPlaces(
            "11",
            "230",
            List.of(Category.RESTAURANT, Category.CAFE_DESSERT),
            0,
            20,
            PlaceSort.LATEST,
            null);

    // Then
    assertThat(result.content()).isEmpty();
    verify(placeRepository)
        .findPlaces(
            List.of(10), true, List.of(Category.RESTAURANT, Category.CAFE_DESSERT), true, pageable);
  }

  @Test
  @DisplayName("장소 식별자로 좌표와 주소를 조회한다")
  void findsPlaceLocation() {
    // Given
    when(place.getSnapshot())
        .thenReturn(
            new Place.PlaceSnapshot(
                1,
                "오늘의 식당",
                new BigDecimal("37.57000000"),
                new BigDecimal("126.98500000"),
                "서울특별시 종로구 종로 1",
                Category.RESTAURANT,
                15,
                List.of("https://placehold.co/1200x800?text=Restaurant")));
    when(placeRepository.findByPlaceIdAndIsActiveTrueAndIsDeletedFalse(1))
        .thenReturn(Optional.of(place));
    PlaceQueryService placeQueryService =
        new PlaceQueryService(
            placeMemberLikeRepository,
            placeRepository,
            placeImageRepository,
            placeScrapRepository,
            regionPlaceQueryService);

    // When
    PlaceLocationResult result = placeQueryService.findPlaceLocation(1);

    // Then
    assertThat(result)
        .isEqualTo(
            new PlaceLocationResult(
                1,
                new BigDecimal("37.57000000"),
                new BigDecimal("126.98500000"),
                "서울특별시 종로구 종로 1"));
  }

  @Test
  @DisplayName("존재하지 않는 장소의 지도 조회는 장소 없음 예외를 발생시킨다")
  void throwsWhenPlaceLocationDoesNotExist() {
    // Given
    when(placeRepository.findByPlaceIdAndIsActiveTrueAndIsDeletedFalse(999))
        .thenReturn(Optional.empty());
    PlaceQueryService placeQueryService =
        new PlaceQueryService(
            placeMemberLikeRepository,
            placeRepository,
            placeImageRepository,
            placeScrapRepository,
            regionPlaceQueryService);

    // When
    var exception = assertThatThrownBy(() -> placeQueryService.findPlaceLocation(999));

    // Then
    exception.isInstanceOf(com.todayit.place.exception.PlaceNotFoundException.class);
  }

  @Test
  @DisplayName("회원이 스크랩한 장소를 오래된 순서로 페이지 조회한다")
  void findsScrappedPlacesOldest() {
    // Given
    when(place.getPlaceId()).thenReturn(1);
    when(place.getSnapshot(anyList()))
        .thenReturn(
            new Place.PlaceSnapshot(
                1,
                "오늘의 식당",
                new BigDecimal("37.5"),
                new BigDecimal("127.0"),
                "서울특별시 종로구 종로 1",
                Category.RESTAURANT,
                15,
                List.of("https://example.com/place.jpg")));
    PageRequest pageable = PageRequest.of(1, 2);
    when(placeScrapRepository.findScrappedPlacesOldest("member-1", pageable))
        .thenReturn(new PageImpl<>(List.of(place), pageable, 3));
    PlaceQueryService placeQueryService =
        new PlaceQueryService(
            placeMemberLikeRepository,
            placeRepository,
            placeImageRepository,
            placeScrapRepository,
            regionPlaceQueryService);

    // When
    PageResult<PlaceResult> result =
        placeQueryService.findScrappedPlaces("member-1", 1, 2, PlaceScrapSort.OLDEST);

    // Then
    assertThat(result.content()).hasSize(1);
    assertThat(result.content().getFirst().placeId()).isEqualTo(1);
    assertThat(result.page()).isEqualTo(1);
    assertThat(result.totalElements()).isEqualTo(3);
    verify(placeScrapRepository).findScrappedPlacesOldest("member-1", pageable);
    verify(placeImageRepository).findByPlacePlaceIdInOrderByPlacePlaceIdAscCreatedAtAsc(List.of(1));
  }
}
